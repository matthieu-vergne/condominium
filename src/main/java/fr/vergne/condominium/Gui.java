package fr.vergne.condominium;

import static java.util.Comparator.comparing;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.LineBorder;

import fr.vergne.condominium.Main.IssueId;
import fr.vergne.condominium.Main.MailId;
import fr.vergne.condominium.core.issue.Issue;
import fr.vergne.condominium.core.mail.Mail;
import fr.vergne.condominium.core.mail.Mail.Address;
import fr.vergne.condominium.core.parser.yaml.IssueYamlSerializer;
import fr.vergne.condominium.core.repository.Repository;
import fr.vergne.condominium.core.repository.Repository.Updatable;
import fr.vergne.condominium.core.source.Source;
import fr.vergne.condominium.core.source.Source.Refiner;
import fr.vergne.condominium.core.source.Source.Track;
import fr.vergne.condominium.core.util.RefinerIdSerializer;
import fr.vergne.condominium.core.util.Serializer;

@SuppressWarnings("serial")
public class Gui extends JFrame {

	public static void main(String[] args) {
		new Gui().setVisible(true);
	}

	public Gui() {
		setTitle("Condominium");
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

		Properties[] globalConf = { null };
		Supplier<Optional<Properties>> confSupplier = () -> {
			return Optional.ofNullable(globalConf[0]);
		};
		// TODO Sync the cache of each supplier: if the repo is reset, the others should
		// be
		Supplier<Optional<Repository<MailId, Mail>>> mailRepositorySupplier = cache(() -> {
			return confSupplier.get()//
					.map(conf -> conf.getProperty("outFolder"))//
					.map(outFolderConf -> {
						System.out.println("Load mails from: " + outFolderConf);
						Path outFolderPath = Paths.get(outFolderConf);
						Path mailRepositoryPath = outFolderPath.resolve("mails");
						return Main.createMailRepository(mailRepositoryPath);
					});
		});
		record Context(Serializer<Issue, String> issueSerializer, Function<MailId, Source<Mail>> mailTracker,
				Function<Source<Mail>, MailId> mailUntracker) {
		}
		Supplier<Optional<Context>> ctxSupplier = cache(() -> {
			return mailRepositorySupplier.get().flatMap(mailRepo -> {
				Source.Tracker sourceTracker = Source.Tracker.create(Source::create, Source.Refiner::create);

				Source<Repository<MailId, Mail>> mailRepoSource = sourceTracker.createSource(mailRepo);
				Source.Refiner<Repository<MailId, Mail>, MailId, Mail> mailRefiner = sourceTracker
						.createRefiner(Repository<MailId, Mail>::mustGet);

				Function<MailId, Source<Mail>> mailTracker = mailId -> {
					return mailRepoSource.refine(mailRefiner, mailId);
				};
				Function<Source<Mail>, MailId> mailUntracker = source -> {
					@SuppressWarnings("unchecked")
					Track.Refined<MailId> track = (Track.Refined<MailId>) sourceTracker.trackOf(source);
					return track.refinedId();
				};

				Serializer<Source<?>, String> sourceSerializer = Serializer
						.createFromMap(Map.of(mailRepoSource, "mails"));
				Serializer<Refiner<?, ?, ?>, String> refinerSerializer = Serializer
						.createFromMap(Map.of(mailRefiner, "id"));
				RefinerIdSerializer refinerIdSerializer = Main.createRefinerIdSerializer(mailRefiner);
				Serializer<Issue, String> issueSerializer = IssueYamlSerializer.create(sourceTracker::trackOf,
						sourceSerializer, refinerSerializer, refinerIdSerializer);

				return Optional.of(new Context(issueSerializer, mailTracker, mailUntracker));
			});
		});
		Supplier<Optional<Serializer<Issue, String>>> issueSerializerSupplier = () -> ctxSupplier.get()
				.map(Context::issueSerializer);
		Supplier<Optional<Function<MailId, Source<Mail>>>> mailTrackerSupplier = () -> ctxSupplier.get()
				.map(Context::mailTracker);
		Supplier<Optional<Function<Source<Mail>, MailId>>> mailUntrackerSupplier = () -> ctxSupplier.get()
				.map(Context::mailUntracker);

		Function<MailId, Source<Mail>> mailTracker = mailId -> {
			return mailTrackerSupplier.get().map(tracker -> {
				return tracker.apply(mailId);
			}).orElseThrow(() -> {
				return new IllegalStateException("No mail tracker set");
			});
		};
		Function<Source<Mail>, MailId> mailUntracker = source -> {
			return mailUntrackerSupplier.get().map(tracker -> {
				return tracker.apply(source);
			}).orElseThrow(() -> {
				return new IllegalStateException("No mail untracker set");
			});
		};
		Supplier<Optional<Repository.Updatable<IssueId, Issue>>> issueRepositorySupplier = cache(() -> {
			System.out.println("Request issues");
			return issueSerializerSupplier.get().flatMap(issueSerializer -> {
				return confSupplier.get().map(conf -> conf.getProperty("outFolder")).map(outFolderConf -> {
					System.out.println("Load issues from: " + outFolderConf);
					Path outFolderPath = Paths.get(outFolderConf);
					Path issueRepositoryPath = outFolderPath.resolve("issues");

					Repository.Updatable<IssueId, Issue> issueRepository = Main
							.createIssueRepository(issueRepositoryPath, issueSerializer);
					return issueRepository;
				});
			});
		});
		FrameContext ctx = new FrameContext(this, new DialogController(this), mailRepositorySupplier,
				issueRepositorySupplier, mailTracker, mailUntracker);

		// TODO Create settings menu
		// TODO Configure mails repository path
		// TODO Configure issues repository path

		Container contentPane = getContentPane();
		JTabbedPane tabsPane = new JTabbedPane(JTabbedPane.TOP);
		contentPane.add(tabsPane);
		tabsPane.add("Check mails", createCheckMailTab(CheckMailContext.init(ctx)));

		pack();

		Path frameConfPath = Path.of("condoGuiConfig.ini");
		Properties frameConf = new Properties();
		String xConf = "x";
		String yConf = "y";
		String widthConf = "width";
		String heightConf = "height";
		if (Files.exists(frameConfPath)) {
			try {
				frameConf.load(Files.newBufferedReader(frameConfPath));
			} catch (IOException cause) {
				throw new RuntimeException("Cannot read: " + frameConfPath, cause);
			}
			Rectangle bounds = getBounds();
			applyPropertyIfPresent(frameConf, xConf, x -> bounds.x = x);
			applyPropertyIfPresent(frameConf, yConf, y -> bounds.y = y);
			applyPropertyIfPresent(frameConf, widthConf, width -> bounds.width = width);
			applyPropertyIfPresent(frameConf, heightConf, height -> bounds.height = height);
			setBounds(bounds);
		}
		addComponentListener(onBoundsUpdate(bounds -> {
			frameConf.setProperty(xConf, "" + bounds.x);
			frameConf.setProperty(yConf, "" + bounds.y);
			frameConf.setProperty(widthConf, "" + bounds.width);
			frameConf.setProperty(heightConf, "" + bounds.height);
			try {
				frameConf.store(Files.newBufferedWriter(frameConfPath), null);
			} catch (IOException cause) {
				throw new RuntimeException("Cannot write: " + frameConfPath, cause);
			}
		}));
		globalConf[0] = frameConf;
	}

	private static <T> Supplier<T> cache(Supplier<T> supplier) {
		AtomicReference<T> value = new AtomicReference<>();
		return () -> {
			return value.updateAndGet(v -> v != null ? v : supplier.get());
		};
	}

	private static ComponentAdapter onBoundsUpdate(Consumer<Rectangle> boundsConsumer) {
		return new ComponentAdapter() {

			@Override
			public void componentResized(ComponentEvent event) {
				updateBounds(event.getComponent().getBounds());
			}

			@Override
			public void componentMoved(ComponentEvent event) {
				updateBounds(event.getComponent().getBounds());
			}

			private void updateBounds(Rectangle bounds) {
				boundsConsumer.accept(bounds);
			}
		};
	}

	private static void applyPropertyIfPresent(Properties frameConf, String key, Consumer<? super Integer> action) {
		Optional.ofNullable(frameConf.getProperty(key))//
				.map(Integer::parseInt)//
				.ifPresent(action);
	}

	private static JComponent createCheckMailTab(CheckMailContext ctx) {
		JPanel panel = new JPanel();
		panel.setLayout(new GridLayout(1, 0));
		panel.add(createMailDisplay(ctx));
		panel.add(new JScrollPane(createIssuesArea(ctx), JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER));

		return panel;
	}

	private static JComponent createIssuesArea(CheckMailContext ctx) {
		// TODO Make so we can add new issue

		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));

		// TODO Feed with actual issues
		ctx.addPropertyChangeListener(event -> {
			if (event.getPropertyName().equals(CheckMailContext.MAIL_REPOSITORY)) {
				Optional<Updatable<IssueId, Issue>> issueRepository = ctx.issueRepository();
				issueRepository.ifPresent(repo -> {
					repo.stream().forEach(entry -> {
						// TODO Set row sorter
						panel.add(createIssueRow(ctx, entry.getKey(), entry.getValue()));
					});
				});
			}
		});

		return panel;
	}

	private static JComponent createIssueRow(CheckMailContext ctx, IssueId issueId, Issue issue) {
		JPanel issueRow = new JPanel() {
			// Trick to avoid the panel to grow in height as much as it can.
			// Partially inspired from: https://stackoverflow.com/a/55345497
			@Override
			public Dimension getMaximumSize() {
				return new Dimension(super.getMaximumSize().width, super.getPreferredSize().height);
			}
		};
		issueRow.setLayout(new GridBagLayout());
		Insets buttonInsets = new Insets(0, 5, 0, 5);

		{
			GridBagConstraints constraints = new GridBagConstraints();
			// No gridx, placed after the previous one
			constraints.gridy = 0;
			constraints.fill = GridBagConstraints.HORIZONTAL;
			constraints.weightx = 1;
			// TODO Make so we can rename issue
			issueRow.add(createIssueTitle(issue.title()), constraints);
		}

		{
			record StatusButtonConf(String title) {
			}
			Map<Issue.Status, StatusButtonConf> statusButtonConfs = Map.of(//
					Issue.Status.INFO, new StatusButtonConf("ⓘ"), // 🛈ⓘ
					Issue.Status.RENEW, new StatusButtonConf("⟳"), // ⟳
					Issue.Status.REPORTED, new StatusButtonConf("📣"), // ⚡✋👀👁📢📣🚨🕬
					Issue.Status.REJECTED, new StatusButtonConf("👎"), // 👎
					Issue.Status.CONFIRMED, new StatusButtonConf("👍"), // ✍👍👌
					Issue.Status.RESOLVING, new StatusButtonConf("🔨"), // ⛏⚒🔨
					Issue.Status.RESOLVED, new StatusButtonConf("✔")// ☑✅✓✔
			);
			GridBagConstraints constraints = new GridBagConstraints();
			// No gridx, placed after the previous one
			constraints.gridy = 0;
			constraints.fill = GridBagConstraints.NONE;
			for (Issue.Status status : Issue.Status.values()) {
				StatusButtonConf conf = statusButtonConfs.get(status);
				issueRow.add(createStatusButton(ctx, issueId, issue, status, conf.title(), buttonInsets), constraints);
			}
		}

		{
			JButton detailsButton = new JButton("📋");
			detailsButton.setMargin(buttonInsets);
			detailsButton.addActionListener(event -> {
				// TODO Create new tab in frame
				ctx.frameContext().dialogController().showMessageDialog("Show issue details");
			});

			GridBagConstraints constraints = new GridBagConstraints();
			// No gridx, placed after the previous one
			constraints.gridy = 0;
			constraints.fill = GridBagConstraints.NONE;
			constraints.insets = new Insets(0, 5, 0, 0);
			issueRow.add(detailsButton, constraints);
		}

		issueRow.setBorder(new LineBorder(issueRow.getBackground()));
		MouseAdapter mouseAdapter = new MouseAdapter() {

			@Override
			public void mouseEntered(MouseEvent e) {
				issueRow.setBorder(new LineBorder(issueRow.getBackground().darker()));
			}

			@Override
			public void mouseExited(MouseEvent e) {
				issueRow.setBorder(new LineBorder(issueRow.getBackground()));
			}
		};
		issueRow.addMouseListener(mouseAdapter);
		for (Component child : issueRow.getComponents()) {
			child.addMouseListener(mouseAdapter);
		}

		return issueRow;
	}

	private static JLabel createIssueTitle(String title) {
		return new JLabel(title);
	}

	private static JToggleButton createStatusButton(CheckMailContext ctx, IssueId issueId, Issue issue,
			Issue.Status status, String title, Insets buttonInsets) {
		JToggleButton statusButton = new JToggleButton(title);
		statusButton.setEnabled(false);
		statusButton.setMargin(buttonInsets);
		statusButton.addActionListener(event -> {
			ctx.mailId().ifPresent(mailId -> {
				ctx.mail().ifPresentOrElse(mail -> {
					ctx.issueRepository().ifPresentOrElse(issueRepo -> {
						if (statusButton.isSelected()) {
							Source<Mail> source = ctx.trackMail(mailId);
							issue.notify(status, mail.receivedDate(), source);
							issueRepo.update(issueId, issue);
						} else {
							issue.denotify(status, mail.receivedDate());
							issueRepo.update(issueId, issue);
						}
					}, () -> {
						throw new IllegalStateException("No issue repository, should not be able to toggle the button");
					});
				}, () -> {
					throw new IllegalStateException("No mail selected, should not be able to toggle the button");
				});
			});
		});

		ctx.addPropertyChangeListener(event -> {
			if (event.getPropertyName().equals(CheckMailContext.MAIL_ID)) {
				ctx.mail().ifPresentOrElse(mail -> {
					issue.history().stream()//
							.filter(item -> status.equals(item.status()))//
							.map(Issue.History.Item::source)//
							.filter(source -> mail.equals(source.resolve()))//
							.findFirst().ifPresentOrElse(src -> {
								statusButton.setSelected(true);
							}, () -> {
								statusButton.setSelected(false);
							});
					statusButton.setEnabled(true);
				}, () -> {
					statusButton.setEnabled(false);
				});
			}
		});

		return statusButton;
	}

	private static JComponent createMailDisplay(CheckMailContext ctx) {
		JLabel mailDate = new JLabel("", JLabel.CENTER);
		JLabel mailSender = new JLabel("", JLabel.CENTER);
		JPanel mailSummary = new JPanel();
		mailSummary.setLayout(new GridLayout());
		mailSummary.add(mailDate);
		mailSummary.add(mailSender);

		JButton previousButton = new JButton("<");
		JButton nextButton = new JButton(">");
		JPanel navigationBar = new JPanel();
		navigationBar.setLayout(new BorderLayout());
		navigationBar.add(previousButton, BorderLayout.LINE_START);
		navigationBar.add(mailSummary, BorderLayout.CENTER);
		navigationBar.add(nextButton, BorderLayout.LINE_END);

		JTextArea mailArea = new JTextArea();
		mailArea.setEditable(false);
		mailArea.setLineWrap(true);

		JPanel panel = new JPanel();
		panel.setLayout(new BorderLayout());
		panel.add(navigationBar, BorderLayout.PAGE_START);
		panel.add(new JScrollPane(mailArea, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);

		// Set the state of the buttons and their update logics
		previousButton.setEnabled(false);
		nextButton.setEnabled(false);
		Runnable updateNavigationButtonsState = () -> {
			Optional<MailId> mailId = ctx.mailId();
			TreeSet<MailId> mailIds = ctx.mailIds();
			previousButton.setEnabled(mailId.map(mailIds::lower).isPresent());
			nextButton.setEnabled(mailId.map(mailIds::higher).isPresent());
		};

		// Disable and re-enabled the buttons on limit cases
		previousButton.addActionListener(event -> updateNavigationButtonsState.run());
		nextButton.addActionListener(event -> updateNavigationButtonsState.run());

		// Update the buttons if more IDs are loaded, the limits may have changed
		ctx.addPropertyChangeListener(event -> {
			if (event.getPropertyName().equals(CheckMailContext.MAIL_ID_SET)) {
				updateNavigationButtonsState.run();
			}
		});

		// Change the mail ID from the button actions
		previousButton.addActionListener(event -> {
			Optional<MailId> mailId = ctx.mailId();
			TreeSet<MailId> mailIds = ctx.mailIds();
			ctx.setMailId(mailId.map(mailIds::lower).or(() -> {
				throw new IllegalStateException("No lower ID, button should not be enabled");
			}));
		});
		nextButton.addActionListener(event -> {
			Optional<MailId> mailId = ctx.mailId();
			TreeSet<MailId> mailIds = ctx.mailIds();
			ctx.setMailId(mailId.map(mailIds::higher).or(() -> {
				throw new IllegalStateException("No higher ID, button should not be enabled");
			}));
		});

		// Change the mail display from the mail ID
		DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT);
		ctx.addPropertyChangeListener(event -> {
			if (event.getPropertyName().equals(CheckMailContext.MAIL_ID)) {
				@SuppressWarnings("unchecked")
				Optional<MailId> mailId = (Optional<MailId>) event.getNewValue();

				mailId.flatMap(id -> ctx.mailRepository()//
						.map(repository -> repository.mustGet(id)))//
						.ifPresentOrElse(mail -> {
							String dateText = dateTimeFormatter.format(mail.receivedDate());
							mailDate.setText(dateText);

							Address address = mail.sender();
							String email = address.email();
							mailSender.setText(address.name().orElse(email));
							mailSender.setToolTipText(email);

							String bodyText = Main.getPlainOrHtmlBody(mail).text();
							mailArea.setText(bodyText);
							mailArea.setCaretPosition(0);
						}, () -> {
							mailDate.setText("-");
							mailSender.setText("-");
							mailSender.setToolTipText(null);
							mailArea.setText("<no mail displayed>");
						});
			}
		});

		return panel;
	}

	static class CheckMailContext {

		private final FrameContext frameContext;
		private final PropertyChangeSupport support = new PropertyChangeSupport(this);

		public CheckMailContext(FrameContext frameContext) {
			this.frameContext = frameContext;
		}

		public Source<Mail> trackMail(MailId mailId) {
			return frameContext.trackMail(mailId);
		}

		public MailId untrackMail(Source<Mail> source) {
			return frameContext.untrackMail(source);
		}

		public FrameContext frameContext() {
			return frameContext;
		}

		public void addPropertyChangeListener(PropertyChangeListener listener) {
			support.addPropertyChangeListener(listener);
		}

		public void removePropertyChangeListener(PropertyChangeListener listener) {
			support.removePropertyChangeListener(listener);
		}

		public static final String MAIL_REPOSITORY = "mailRepository";
		private Optional<Repository<MailId, Mail>> mailRepository = Optional.empty();

		public Optional<Repository<MailId, Mail>> mailRepository() {
			if (mailRepository.isEmpty()) {
				replaceMailRepository(frameContext.mailRepository());
			}
			return mailRepository;
		}

		public void replaceMailRepository(Optional<Repository<MailId, Mail>> newRepository) {
			Optional<Repository<MailId, Mail>> oldRepository = this.mailRepository;
			this.mailRepository = newRepository;
			support.firePropertyChange(MAIL_REPOSITORY, oldRepository, newRepository);
		}

		public static final String ISSUE_REPOSITORY = "issueRepository";
		private Optional<Repository.Updatable<IssueId, Issue>> issueRepository = Optional.empty();

		public Optional<Repository.Updatable<IssueId, Issue>> issueRepository() {
			if (issueRepository.isEmpty()) {
				replaceIssueRepository(frameContext.issueRepository());
			}
			return issueRepository;
		}

		public void replaceIssueRepository(Optional<Repository.Updatable<IssueId, Issue>> newRepository) {
			Optional<Repository.Updatable<IssueId, Issue>> oldRepository = this.issueRepository;
			this.issueRepository = newRepository;
			support.firePropertyChange(ISSUE_REPOSITORY, oldRepository, newRepository);
		}

		public static final String MAIL_ID_SET = "mailIdSet";
		private final TreeSet<MailId> mailIds = new TreeSet<>(
				Comparator.comparing(MailId::datetime).thenComparing(MailId::sender));

		public TreeSet<MailId> mailIds() {
			return mailIds;
		}

		public void addMailIds(List<MailId> addedMailIds) {
			this.mailIds.addAll(addedMailIds);
			// We don't want to compute an extra set just to have the old state
			// Let the listeners compute it if required by providing full set + added set
			support.firePropertyChange(MAIL_ID_SET, this.mailIds, addedMailIds);
		}

		public static final String MAIL_ID = "mailId";
		private Optional<MailId> mailId = Optional.empty();

		public Optional<MailId> mailId() {
			return mailId;
		}

		public void setMailId(Optional<MailId> newMailId) {
			Optional<MailId> oldMailId = this.mailId;
			this.mailId = newMailId;
			support.firePropertyChange(MAIL_ID, oldMailId, newMailId);
		}

		public Optional<Mail> mail() {
			return mailRepository().flatMap(repo -> this.mailId.map(repo::mustGet));
		}

		public static CheckMailContext init(FrameContext ctx) {
			CheckMailContext checkMailCtx = new CheckMailContext(ctx);

			// Upon mail repository change, initialize the set of IDs for easy browsing
			checkMailCtx.addPropertyChangeListener(event -> {
				// TODO Cancel & reload if repo is changed again
				if (event.getPropertyName().equals(CheckMailContext.MAIL_REPOSITORY)) {
					@SuppressWarnings("unchecked")
					Optional<Repository<MailId, Mail>> newRepo = (Optional<Repository<MailId, Mail>>) event
							.getNewValue();
					newRepo.ifPresent(repo -> {
						SwingWorker<Void, MailId> loader = createMailIdsLoader(repo, mailIds -> {
							checkMailCtx.addMailIds(mailIds);
						});
						loader.execute();
					});
				}
			});

			// Upon creating the window, initialize the ID to be the first mail
			ctx.frame.addWindowListener(new WindowAdapter() {
				@Override
				public void windowOpened(WindowEvent event) {
					SwingUtilities.invokeLater(new Runnable() {
						@Override
						public void run() {
							checkMailCtx.mailRepository().ifPresentOrElse(mailRepo -> {
								checkMailCtx.issueRepository().ifPresentOrElse(issueRepo -> {
									Optional<MailId> lastMailIdNotified = issueRepo.streamResources()//
											.map(Issue::history)//
											.flatMap(Issue.History::stream)//
											.map(Issue.History.Item::source)//
											.map(Source::resolve)//
											.filter(Mail.class::isInstance).map(srcObject -> (Mail) srcObject)//
											.distinct()//
											.sorted(comparing(Mail::receivedDate).reversed())//
											.findFirst()//
											.flatMap(mail -> mailRepo.key(mail));

									Optional<MailId> currentMailId = lastMailIdNotified
											.or(() -> mailRepo.streamKeys().findFirst());

									checkMailCtx.setMailId(currentMailId);
								}, () -> {
									SwingUtilities.invokeLater(this);
								});
							}, () -> {
								SwingUtilities.invokeLater(this);
							});
						}
					});
				}
			});

			return checkMailCtx;
		}

		private static SwingWorker<Void, MailId> createMailIdsLoader(Repository<MailId, Mail> repo,
				Consumer<List<MailId>> mailIdsLoader) {
			return new SwingWorker<>() {

				@Override
				protected Void doInBackground() throws Exception {
					repo.streamKeys()//
							.takeWhile(id -> !isCancelled())//
							.forEach(id -> publish(id));
					return null;
				}

				@Override
				protected void process(List<MailId> mailIds) {
					mailIdsLoader.accept(mailIds);
				}
			};
		}
	}

	static class FrameContext {

		private final JFrame frame;
		private final DialogController dialogController;
		private final Supplier<Optional<Repository<MailId, Mail>>> mailRepositorySupplier;
		private final Supplier<Optional<Repository.Updatable<IssueId, Issue>>> issueRepositorySupplier;
		private final Function<MailId, Source<Mail>> mailTracker;
		private final Function<Source<Mail>, MailId> mailUntracker;

		public FrameContext(JFrame frame, DialogController dialogController,
				Supplier<Optional<Repository<MailId, Mail>>> mailRepositorySupplier,
				Supplier<Optional<Repository.Updatable<IssueId, Issue>>> issueRepositorySupplier,
				Function<MailId, Source<Mail>> mailTracker, Function<Source<Mail>, MailId> mailUntracker) {
			this.dialogController = dialogController;
			this.mailRepositorySupplier = mailRepositorySupplier;
			this.issueRepositorySupplier = issueRepositorySupplier;
			this.frame = frame;
			this.mailTracker = mailTracker;
			this.mailUntracker = mailUntracker;
		}

		public Source<Mail> trackMail(MailId mailId) {
			return mailTracker.apply(mailId);
		}

		public MailId untrackMail(Source<Mail> source) {
			return mailUntracker.apply(source);
		}

		public DialogController dialogController() {
			return dialogController;
		}

		public Optional<Repository<MailId, Mail>> mailRepository() {
			return mailRepositorySupplier.get();
		}

		public Optional<Repository.Updatable<IssueId, Issue>> issueRepository() {
			return issueRepositorySupplier.get();
		}
	}

	static class DialogController {

		private final JFrame frame;

		public DialogController(JFrame frame) {
			this.frame = frame;
		}

		public void showMessageDialog(Object message) {
			JOptionPane.showMessageDialog(frame, message);
		}
	}

}
