package fr.vergne.condominium;

import static java.util.Comparator.comparing;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.swing.AbstractAction;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import fr.vergne.condominium.Main.IssueId;
import fr.vergne.condominium.Main.MailId;
import fr.vergne.condominium.Main.QuestionId;
import fr.vergne.condominium.core.mail.Mail;
import fr.vergne.condominium.core.monitorable.Issue;
import fr.vergne.condominium.core.monitorable.Monitorable;
import fr.vergne.condominium.core.monitorable.Monitorable.History;
import fr.vergne.condominium.core.monitorable.Question;
import fr.vergne.condominium.core.parser.yaml.IssueYamlSerializer;
import fr.vergne.condominium.core.parser.yaml.QuestionYamlSerializer;
import fr.vergne.condominium.core.repository.FileRepository;
import fr.vergne.condominium.core.repository.Repository;
import fr.vergne.condominium.core.source.Source;
import fr.vergne.condominium.core.source.Source.Refiner;
import fr.vergne.condominium.core.source.Source.Track;
import fr.vergne.condominium.core.util.Persister;
import fr.vergne.condominium.core.util.RefinerIdSerializer;
import fr.vergne.condominium.core.util.Serializer;
import fr.vergne.condominium.gui.Configuration;
import fr.vergne.condominium.gui.Configuration.Builder;
import fr.vergne.condominium.gui.Configuration.MissingKeyException;
import fr.vergne.condominium.gui.JMailPanel;
import fr.vergne.condominium.gui.JMonitorableTitle;

@SuppressWarnings("serial")
public class Gui extends JFrame {

	public static void main(String[] args) {
		Path confPath = Path.of("condoGuiConfig.ini");
		Persister<Configuration> confPersister = Configuration.fromPropertiesFile(confPath);
		Configuration configuration = null;
		if (confPersister.hasSave()) {
			try {
				configuration = confPersister.load();
			} catch (MissingKeyException cause) {
				Persister<Configuration.Builder> partialPersister = Configuration
						.fromIncompletePropertiesFile(confPath);
				Configuration.Builder confBuilder = partialPersister.load();
				configuration = feedConfBuilder(confBuilder);
				confPersister.save(configuration);
			}
		} else {
			Configuration.Builder confBuilder = new Configuration.Builder();
			configuration = feedConfBuilder(confBuilder);
			confPersister.save(configuration);
		}

		launchGui(configuration, confPersister);
	}

	private static void launchGui(Configuration configuration, Persister<Configuration> confPersister) {
		new Gui(configuration, confPersister).setVisible(true);
	}

	private static Configuration feedConfBuilder(Configuration.Builder confBuilder) {
		JPanel settingsPanel = createSettingsPanel(confBuilder);
		int answer = JOptionPane.showConfirmDialog(null, settingsPanel, "Initialize settings",
				JOptionPane.OK_CANCEL_OPTION);
		if (answer == JOptionPane.CANCEL_OPTION) {
			System.exit(0);
			return null;// Inform compiler nothing occurs after System.exit()
		}
		Configuration configuration;
		try {
			configuration = confBuilder.build();
		} catch (Exception cause2) {
			JOptionPane.showMessageDialog(null, cause2.getLocalizedMessage(), "Invalid configuration",
					JOptionPane.ERROR_MESSAGE);
			System.exit(1);
			return null;// Inform compiler nothing occurs after System.exit()
		}
		return configuration;
	}

	public Gui(Configuration configuration, Persister<Configuration> confPersister) {
		// TODO Sync cache of each supplier: if repo is reset, others should be
		Path mailRepositoryPath = configuration.mailsRepositoryPath();
		FileRepository<MailId, Mail> mailRepository = Main.createMailRepository(mailRepositoryPath);

		Source.Tracker sourceTracker = Source.Tracker.create(Source::create, Source.Refiner::create);

		Source<Repository<MailId, Mail>> mailRepoSource = sourceTracker.createSource(mailRepository);
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

		Serializer<Source<?>, String> sourceSerializer = Serializer.createFromMap(Map.of(mailRepoSource, "mails"));
		Serializer<Refiner<?, ?, ?>, String> refinerSerializer = Serializer.createFromMap(Map.of(mailRefiner, "id"));
		RefinerIdSerializer refinerIdSerializer = Main.createRefinerIdSerializer(mailRefiner);

		Path issueRepositoryPath = configuration.issuesRepositoryPath();
		Serializer<Issue, String> issueSerializer = IssueYamlSerializer.create(sourceTracker::trackOf, sourceSerializer,
				refinerSerializer, refinerIdSerializer);
		Repository.Updatable<IssueId, Issue> issueRepository = Main.createIssueRepository(issueRepositoryPath,
				issueSerializer);

		Path questionsRepositoryPath = configuration.questionsRepositoryPath();
		Serializer<Question, String> questionSerializer = QuestionYamlSerializer.create(sourceTracker::trackOf,
				sourceSerializer, refinerSerializer, refinerIdSerializer);
		Repository.Updatable<QuestionId, Question> questionRepository = Main
				.createQuestionRepository(questionsRepositoryPath, questionSerializer);

		DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT);
		FrameContext ctx = new FrameContext(configuration, confPersister, this, new DialogController(this),
				mailRepository, issueRepository, questionRepository, mailTracker, mailUntracker, dateTimeFormatter);

		JPanel footerPanel = createFooter(ctx);

		setJMenuBar(createMenuBar(ctx));

		Container contentPane = getContentPane();
		contentPane.setLayout(new BorderLayout());
		JTabbedPane tabsPane = new JTabbedPane(JTabbedPane.TOP);
		contentPane.add(tabsPane, BorderLayout.CENTER);
		contentPane.add(footerPanel, BorderLayout.PAGE_END);
		tabsPane.add("Check mails", createCheckMailTab(CheckMailContext.init(ctx)));

		setTitle("Condominium");
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

		pack();

		// Set full screen
		setExtendedState(JFrame.MAXIMIZED_BOTH);
	}

	private JMenuBar createMenuBar(FrameContext ctx) {
		JMenuBar menuBar = new JMenuBar();
		menuBar.add(createFileMenu(ctx));
		return menuBar;
	}

	private JMenu createFileMenu(FrameContext ctx) {
		JMenu fileMenu = new JMenu("File");
		{
			JMenuItem settingsMenuItem = new JMenuItem("Settings");
			/*
			 * menuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S,
			 * InputEvent.ALT_DOWN_MASK));
			 */
			settingsMenuItem.addActionListener(event -> {
				Configuration conf = ctx.configuration();
				Builder confBuilder = Configuration.Builder.fromConfiguration(conf);
				JPanel settingsPanel = createSettingsPanel(confBuilder);

				JButton applyButton = new JButton("Apply & Restart");
				JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
				buttonsPanel.add(applyButton);

				JPanel panel = new JPanel(new BorderLayout());
				panel.add(settingsPanel, BorderLayout.CENTER);
				panel.add(buttonsPanel, BorderLayout.PAGE_END);

				applyButton.addActionListener(event2 -> {
					Configuration newConf = confBuilder.build();
					Persister<Configuration> persister = ctx.configurationPersister();
					ctx.frame.dispose();
					persister.save(newConf);
					launchGui(newConf, persister);
				});

				ctx.addTabCloseable(new JLabel("Settings"), new JScrollPane(panel,
						JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER));
			});
			fileMenu.add(settingsMenuItem);
		}
		fileMenu.add(createQuitMenuItem());
		return fileMenu;
	}

	private static JPanel createSettingsPanel(Configuration.Builder confBuilder) {
		JPanel settingsPanel = new JPanel(new GridBagLayout());

		GridBagConstraints constraintsTitle = new GridBagConstraints();
		constraintsTitle.anchor = GridBagConstraints.PAGE_START;
		constraintsTitle.gridx = 0;
		constraintsTitle.gridy = GridBagConstraints.RELATIVE;
		constraintsTitle.fill = GridBagConstraints.HORIZONTAL;
		constraintsTitle.weightx = 1;
		constraintsTitle.gridwidth = GridBagConstraints.REMAINDER;
		constraintsTitle.insets = new Insets(10, 0, 10, 0);
		class JTitle extends JLabel {
			public JTitle(String title) {
				super(title.toUpperCase());
				setHorizontalAlignment(JLabel.CENTER);
			}
		}
		Consumer<String> titleAdder = title -> {
			settingsPanel.add(new JTitle(title), constraintsTitle);
		};

		GridBagConstraints constraintsKey = new GridBagConstraints();
		constraintsKey.anchor = GridBagConstraints.PAGE_START;
		constraintsKey.gridx = 0;
		constraintsKey.gridy = GridBagConstraints.RELATIVE;
		constraintsKey.fill = GridBagConstraints.HORIZONTAL;
		constraintsKey.weightx = 0;
		class JName extends JLabel {
			public JName(String name) {
				super(name);
			}
		}
		GridBagConstraints constraintsValue = new GridBagConstraints();
		constraintsValue.anchor = GridBagConstraints.PAGE_START;
		constraintsValue.gridx = 1;
		constraintsValue.gridy = GridBagConstraints.RELATIVE;
		constraintsValue.fill = GridBagConstraints.HORIZONTAL;
		constraintsValue.weightx = 1;
		constraintsValue.insets = new Insets(0, 5, 0, 0);
		class JPath extends JPanel {
			public JPath(Supplier<Path> pathSupplier, Consumer<Path> pathConsumer) {
				JTextField pathField = new JTextField() {
					// Trick to avoid being squashed when empty
					@Override
					public Dimension getPreferredSize() {
						return new Dimension(300, super.getPreferredSize().height);
					}
				};

				Consumer<Path> pathApplier = path -> {
					pathConsumer.accept(path);
					pathField.setText(Optional.ofNullable(path).map(Path::toString).orElse(""));
				};
				pathApplier.accept(pathSupplier.get());

				JButton browseButton = new JButton(UIManager.getIcon("FileView.directoryIcon"));
				browseButton.setMargin(new Insets(0, 0, 0, 0));
				JFileChooser chooser = new JFileChooser();
				chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
				browseButton.addActionListener(event -> {
					chooser.setCurrentDirectory(new File(pathField.getText()));
					int choice = chooser.showOpenDialog(JPath.this);
					if (choice == JFileChooser.APPROVE_OPTION) {
						pathApplier.accept(chooser.getSelectedFile().toPath());
					}
				});

				setLayout(new BorderLayout());
				add(pathField, BorderLayout.CENTER);
				add(browseButton, BorderLayout.LINE_END);
			}
		}
		BiConsumer<String, JComponent> settingAdder = (name, valueComponent) -> {
			settingsPanel.add(new JName(name), constraintsKey);
			settingsPanel.add(valueComponent, constraintsValue);
		};

		titleAdder.accept("Repository paths");
		settingAdder.accept("Mails repository path",
				new JPath(confBuilder::mailsRepositoryPath, confBuilder::setMailsRepositoryPath));
		settingAdder.accept("Issues repository path",
				new JPath(confBuilder::issuesRepositoryPath, confBuilder::setIssuesRepositoryPath));
		settingAdder.accept("Questions repository path",
				new JPath(confBuilder::questionsRepositoryPath, confBuilder::setQuestionsRepositoryPath));
		// TODO Feed builder

		return settingsPanel;
	}

	private JMenuItem createQuitMenuItem() {
		return new JMenuItem(new AbstractAction("Quit") {
			@Override
			public void actionPerformed(ActionEvent event) {
				System.exit(0);
			}
		});
	}

	private JPanel createFooter(FrameContext ctx) {
		JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.TRAILING));
		footerPanel.add(createFooterLogsButton(ctx));
		return footerPanel;
	}

	private JButton createFooterLogsButton(FrameContext ctx) {
		JButton logsButton = new JButton("-");
		logsButton.setEnabled(false);
		logsButton.setToolTipText("No error occurred so far");

		Path errorLogsPath;
		try {
			errorLogsPath = Files.createTempFile("gui", "error.log");
		} catch (IOException cause) {
			throw new RuntimeException("Cannot create error log file", cause);
		}
		FileOutputStream errorLogsStream;
		try {
			errorLogsStream = new FileOutputStream(errorLogsPath.toFile());
		} catch (FileNotFoundException cause) {
			throw new RuntimeException("Cannot open error log file: " + errorLogsPath, cause);
		}

		System.setErr(new PrintStream(System.err) {
			@Override
			public void write(byte[] buf, int off, int len) {
				try {
					errorLogsStream.write(buf, off, len);
					if (!logsButton.isEnabled()) {
						logsButton.setToolTipText("Some errors occurred");
						logsButton.setText("!");
						logsButton.setBackground(Color.RED);
						logsButton.setEnabled(true);
					}
				} catch (IOException cause) {
					throw new RuntimeException("Cannot write error log file: " + errorLogsPath, cause);
				}
				super.write(buf, off, len);
			}
		});

		logsButton.addActionListener(event -> {
			String errors;
			try {
				errors = Files.readString(errorLogsPath);
			} catch (IOException cause) {
				throw new RuntimeException("Cannot read error log file: " + errorLogsPath, cause);
			}

			JTextArea logs = new JTextArea(errors);
			logs.setEditable(false);
			ctx.addTabCloseable(new JLabel("Errors"), new JScrollPane(logs, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
					JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED));
		});

		return logsButton;
	}

	private static JComponent createCheckMailTab(CheckMailContext ctx) {
		JPanel panel = new JPanel();
		panel.setLayout(new GridLayout(1, 0));
		panel.add(createMailDisplay(ctx));
		JTabbedPane monitorableTabs = new JTabbedPane(JTabbedPane.TOP);
		panel.add(monitorableTabs);

		{
			Repository.Updatable<IssueId, Issue> repo = ctx.frameContext().issueRepository();
			Monitorable.Factory<Issue, Issue.State> factory = Issue::create;
			List<Issue.State> states = Arrays.asList(Issue.State.values());
			Map<Issue.State, String> issueStateIcons = Map.of(//
					Issue.State.INFO, "ⓘ", // 🛈ⓘ
					Issue.State.RENEW, "⟳", // ⟳
					Issue.State.REPORTED, "📣", // ⚡✋👀👁📢📣🚨🕬
					Issue.State.REJECTED, "👎", // 👎
					Issue.State.CONFIRMED, "👍", // ✍👍👌
					Issue.State.RESOLVING, "🔨", // ⛏⚒🔨
					Issue.State.RESOLVED, "✔"// ☑✅✓✔
			);
			monitorableTabs.add("Issues", createMonitorableArea(ctx, repo, factory, states, issueStateIcons));
		}
		{
			Repository.Updatable<QuestionId, Question> repo = ctx.frameContext().questionRepository();
			Monitorable.Factory<Question, Question.State> factory = Question::create;
			List<Question.State> states = Arrays.asList(Question.State.values());
			Map<Question.State, String> stateIcons = Map.of(//
					Question.State.INFO, "ⓘ", // 🛈ⓘ
					Question.State.RENEW, "⟳", // ⟳
					Question.State.REQUEST, "?", // ⚡✋👀👁📢📣🚨🕬
					Question.State.ANSWER, "✔"// ☑✅✓✔
			);
			monitorableTabs.add("Questions", createMonitorableArea(ctx, repo, factory, states, stateIcons));
		}

		return panel;
	}

	private static <S, I, M extends Monitorable<S>> JComponent createMonitorableArea(CheckMailContext ctx,
			Repository.Updatable<I, M> repository, Monitorable.Factory<M, S> monitorableFactory, List<S> states,
			Map<S, String> stateIcons) {
		JPanel listPanel = new JPanel();
		BiConsumer<I, M> rowAdder;
		{
			rowAdder = (issueId, issue) -> {
				// TODO Use row sorter
				listPanel.add(createMonitorableRow(ctx, issueId, issue, repository, states, stateIcons));
			};
			listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.PAGE_AXIS));
			new SwingWorker<Void, Map.Entry<I, M>>() {

				@Override
				protected Void doInBackground() throws Exception {
					repository.stream().forEach(this::publish);
					return null;
				}

				@Override
				protected void process(List<Entry<I, M>> entries) {
					entries.forEach(entry -> {
						rowAdder.accept(entry.getKey(), entry.getValue());
					});
					listPanel.revalidate();
				}
			}.execute();
		}

		JPanel addPanel = new JPanel();
		{
			JTextField newMonitorableTitle = new JTextField();
			JButton newMonitorableButton = new JButton("+");

			newMonitorableButton.setEnabled(false);
			newMonitorableTitle.getDocument().addDocumentListener(onAnyUpdate(event -> {
				newMonitorableButton.setEnabled(!newMonitorableTitle.getText().isBlank());
			}));

			Runnable monitorableCreator = () -> {
				String monitorableTitle = newMonitorableTitle.getText();
				if (monitorableTitle.isBlank()) {
					throw new IllegalStateException("No title, button should be disabled");
				}

				ZonedDateTime dateTime = ZonedDateTime.now();
				History<S> history = History.createEmpty();
				M monitorable = monitorableFactory.createMonitorable(monitorableTitle, dateTime, history);
				I id = repository.add(monitorable);
				rowAdder.accept(id, monitorable);
				newMonitorableTitle.setText("");
				listPanel.revalidate();
			};
			newMonitorableButton.addActionListener(event -> monitorableCreator.run());
			newMonitorableTitle.addKeyListener(new KeyAdapter() {
				@Override
				public void keyReleased(KeyEvent event) {
					if (event.getKeyCode() == KeyEvent.VK_ENTER && newMonitorableButton.isEnabled()) {
						monitorableCreator.run();
					}
				}
			});

			addPanel.setLayout(new BorderLayout());
			addPanel.add(newMonitorableTitle, BorderLayout.CENTER);
			addPanel.add(newMonitorableButton, BorderLayout.LINE_END);
		}

		JPanel panel = new JPanel();
		panel.setLayout(new BorderLayout());
		panel.add(new JScrollPane(listPanel, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);
		panel.add(addPanel, BorderLayout.PAGE_END);

		return panel;
	}

	private static DocumentListener onAnyUpdate(Consumer<DocumentEvent> updater) {
		return new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent event) {
				updateButton(event);
			}

			@Override
			public void changedUpdate(DocumentEvent event) {
				updateButton(event);
			}

			@Override
			public void removeUpdate(DocumentEvent event) {
				updateButton(event);
			}

			private void updateButton(DocumentEvent event) {
				updater.accept(event);
			}
		};
	}

	private static <S, I, M extends Monitorable<S>> JComponent createMonitorableRow(CheckMailContext ctx,
			I monitorableId, M monitorable, Repository.Updatable<I, M> repository, List<S> states,
			Map<S, String> stateIcons) {
		JPanel monitorableRow = new JPanel() {
			// Trick to avoid the panel to grow in height as much as it can.
			// Partially inspired from: https://stackoverflow.com/a/55345497
			@Override
			public Dimension getMaximumSize() {
				return new Dimension(super.getMaximumSize().width, super.getPreferredSize().height);
			}
		};
		monitorableRow.setLayout(new GridBagLayout());
		Insets buttonInsets = new Insets(0, 5, 0, 5);

		{
			GridBagConstraints constraints = new GridBagConstraints();
			// No gridx, placed after the previous one
			constraints.gridy = 0;
			constraints.fill = GridBagConstraints.HORIZONTAL;
			constraints.weightx = 1;
			monitorableRow.add(createMonitorableTitle(ctx, monitorableId, monitorable, repository, stateIcons),
					constraints);
		}

		{
			GridBagConstraints constraints = new GridBagConstraints();
			// No gridx, placed after the previous one
			constraints.gridy = 0;
			constraints.fill = GridBagConstraints.NONE;
			states.forEach(state -> {
				monitorableRow.add(createMonitorableStateButton(ctx, monitorableId, monitorable, state,
						stateIcons.get(state), buttonInsets, repository), constraints);
			});
		}

		Color defaultColor = monitorableRow.getBackground();
		LineBorder visibleBorder = new LineBorder(defaultColor.darker());
		LineBorder invisibleBorder = new LineBorder(defaultColor);
		monitorableRow.setBorder(invisibleBorder);
		ctx.frameContext().addEnterLeaveActions(monitorableRow, //
				() -> monitorableRow.setBorder(visibleBorder), //
				() -> monitorableRow.setBorder(invisibleBorder));

		return monitorableRow;
	}

	private static <I, M extends Monitorable<S>, S> JComponent createMonitorableTitle(CheckMailContext ctx,
			I monitorableId, M monitorable, Repository.Updatable<I, M> repository, Map<S, String> stateIcons) {

		JMonitorableTitle monitorableTitle = new JMonitorableTitle(repository, monitorableId, monitorable);

		Runnable detailsDisplayer = () -> {
			JComponent component = createMonitorableDetails(ctx, monitorableId, monitorable, repository, stateIcons);
			JLabel tabTitle = new JLabel(monitorable.title());
			monitorable.observeTitle((oldTitle, newTitle) -> tabTitle.setText(newTitle));
			ctx.frameContext().addTabCloseable(tabTitle, component);
		};

		JPopupMenu popupMenu;
		{
			JMenuItem renameItem = new JMenuItem("Rename");
			renameItem.addActionListener(ev -> monitorableTitle.edit());

			JMenuItem detailsItem = new JMenuItem("Details");
			detailsItem.addActionListener(ev -> detailsDisplayer.run());

			popupMenu = new JPopupMenu();
			popupMenu.add(detailsItem);
			popupMenu.add(renameItem);
		}

		// Switch to mutable upon double-click
		monitorableTitle.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent event) {
				if (event.getButton() == MouseEvent.BUTTON1 && event.getClickCount() == 2) {
					detailsDisplayer.run();
				}
			};

			@Override
			public void mousePressed(MouseEvent event) {
				// Popup triggers on mouse press for some OSs (cf. javadoc)
				if (event.isPopupTrigger()) {
					popup(popupMenu, event);
				}
			}

			@Override
			public void mouseReleased(MouseEvent event) {
				// Popup triggers on mouse release for some OSs (cf. javadoc)
				if (event.isPopupTrigger()) {
					popup(popupMenu, event);
				}
			}

			private void popup(JPopupMenu popupMenu, MouseEvent event) {
				popupMenu.show(event.getComponent(), event.getX(), event.getY());
			}
		});

		return monitorableTitle;
	}

	private static <I, M extends Monitorable<S>, S> JComponent createMonitorableDetails(CheckMailContext ctx,
			I monitorableId, M monitorable, Repository.Updatable<I, M> repository, Map<S, String> stateIcons) {
		JPanel sourcePanel = new JPanel(new GridLayout(1, 1));
		Consumer<Source<?>> sourceUpdater = source -> {
			@SuppressWarnings("unchecked")
			Source<Mail> mailSource = (Source<Mail>) source;
			JComponent mailArea = createMailArea2(ctx, mailSource.resolve());

			sourcePanel.removeAll();
			sourcePanel.add(mailArea);
			sourcePanel.revalidate();
		};

		JComponent monitorableArea = createMonitorableArea2(ctx, repository, monitorableId, monitorable, stateIcons,
				sourceUpdater);

		JPanel panel = new JPanel();
		panel.setLayout(new GridLayout(1, 2));
		panel.add(monitorableArea);
		panel.add(new JScrollPane(sourcePanel, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER));

		return panel;
	}

	private static JComponent createMailArea2(CheckMailContext ctx, Mail mail) {
		JMailPanel mailPanel = new JMailPanel(ctx.frameContext().dateTimeFormatter());

		// TODO Use buttons to move in history
		mailPanel.setPreviousButtonEnabled(false);
		mailPanel.setNextButtonEnabled(false);

		mailPanel.setMail(mail);

		return mailPanel;
	}

	private static <I, M extends Monitorable<S>, S> JComponent createMonitorableArea2(CheckMailContext ctx,
			Repository.Updatable<I, M> repository, I monitorableId, M monitorable, Map<S, String> stateIcons,
			Consumer<Source<?>> sourceUpdater) {
		JMonitorableTitle monitorableTitle = new JMonitorableTitle(repository, monitorableId, monitorable);
		monitorableTitle.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent event) {
				if (event.getButton() == MouseEvent.BUTTON1 && event.getClickCount() == 2) {
					monitorableTitle.edit();
				}
			};
		});

		JComponent monitorableArea;
		monitorableArea = new JPanel();
		monitorableArea.setLayout(new GridBagLayout());
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = 0;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.weightx = 1;
		monitorableArea.add(monitorableTitle, constraints);
		DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
		monitorableArea.add(new JLabel(dateTimeFormatter.format(monitorable.dateTime())), constraints);
		monitorableArea.add(new JLabel("History:"), constraints);
		constraints.fill = GridBagConstraints.BOTH;
		constraints.weighty = 1;
		monitorableArea
				.add(new JScrollPane(createHistoryArea(ctx, monitorable, dateTimeFormatter, stateIcons, sourceUpdater),
						JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER), constraints);
		return monitorableArea;
	}

	private static <M extends Monitorable<S>, S> JPanel createHistoryArea(CheckMailContext ctx, M monitorable,
			DateTimeFormatter dateTimeFormatter, Map<S, String> stateIcons, Consumer<Source<?>> sourceUpdater) {
		JPanel historyArea;
		historyArea = new JPanel();
		historyArea.setLayout(new BoxLayout(historyArea, BoxLayout.PAGE_AXIS));
		History<S> history = monitorable.history();
		history.stream().sorted(comparing(History.Item::dateTime)).forEach(item -> {
			JPanel historyRow = createHistoryRow(ctx, item, dateTimeFormatter, stateIcons);
			historyRow.setBackground(null);
			historyRow.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent event) {
					for (Component rows : historyArea.getComponents()) {
						rows.setBackground(null);
					}
					historyRow.setBackground(Color.YELLOW);
					sourceUpdater.accept(item.source());
				}
			});
			historyArea.add(historyRow);
		});
		return historyArea;
	}

	private static <S> JPanel createHistoryRow(CheckMailContext ctx, History.Item<S> item,
			DateTimeFormatter dateTimeFormatter, Map<S, String> stateIcons) {
		JPanel historyRow = new JPanel() {
			// Trick to avoid the panel to grow in height as much as it can.
			// Partially inspired from: https://stackoverflow.com/a/55345497
			@Override
			public Dimension getMaximumSize() {
				return new Dimension(super.getMaximumSize().width, super.getPreferredSize().height);
			}
		};
		historyRow.setLayout(new FlowLayout(FlowLayout.LEADING));
		historyRow.add(new JLabel(stateIcons.get(item.state())));
		@SuppressWarnings("unchecked")
		MailId mailId = ctx.untrackMail((Source<Mail>) item.source());
		historyRow.add(new JLabel(dateTimeFormatter.format(mailId.datetime())));
		historyRow.add(new JLabel("📧"));
		historyRow.add(new JLabel(mailId.sender()));

		Color defaultColor = historyRow.getBackground();
		LineBorder visibleBorder = new LineBorder(defaultColor.darker());
		LineBorder invisibleBorder = new LineBorder(defaultColor);
		historyRow.setBorder(invisibleBorder);
		ctx.frameContext().addEnterLeaveActions(historyRow, //
				() -> historyRow.setBorder(visibleBorder), //
				() -> historyRow.setBorder(invisibleBorder));

		return historyRow;
	}

	private static <S, I, M extends Monitorable<S>> JToggleButton createMonitorableStateButton(CheckMailContext ctx,
			I monitorableId, M monitorable, S state, String title, Insets buttonInsets,
			Repository.Updatable<I, M> repository) {
		JToggleButton stateButton = new JToggleButton(title);
		stateButton.setMargin(buttonInsets);
		stateButton.addActionListener(event -> {
			ctx.mailId().ifPresent(mailId -> {
				ctx.mail().ifPresentOrElse(mail -> {
					if (stateButton.isSelected()) {
						Source<Mail> source = ctx.trackMail(mailId);
						monitorable.notify(state, mail.receivedDate(), source);
						repository.update(monitorableId, monitorable);
					} else {
						monitorable.denotify(state, mail.receivedDate());
						repository.update(monitorableId, monitorable);
					}
				}, () -> {
					throw new IllegalStateException("No mail selected, should not be able to toggle the button");
				});
			});
		});

		stateButton.setEnabled(ctx.mail().isPresent());
		ctx.addPropertyChangeListener(event -> {
			if (event.getPropertyName().equals(CheckMailContext.MAIL_ID)) {
				ctx.mail().ifPresentOrElse(mail -> {
					monitorable.history().stream()//
							.filter(item -> state.equals(item.state()))//
							.map(Monitorable.History.Item::source)//
							.filter(source -> mail.equals(source.resolve()))//
							.findFirst().ifPresentOrElse(src -> {
								stateButton.setSelected(true);
							}, () -> {
								stateButton.setSelected(false);
							});
					stateButton.setEnabled(true);
				}, () -> {
					stateButton.setEnabled(false);
				});
			}
		});

		return stateButton;
	}

	private static JComponent createMailDisplay(CheckMailContext ctx) {
		JMailPanel mailPanel = new JMailPanel(ctx.frameContext().dateTimeFormatter());

		// Set the state of the buttons and their update logics
		mailPanel.setPreviousButtonEnabled(false);
		mailPanel.setNextButtonEnabled(false);
		Runnable updateNavigationButtonsState = () -> {
			Optional<MailId> mailId = ctx.mailId();
			TreeSet<MailId> mailIds = ctx.mailIds();
			mailPanel.setPreviousButtonEnabled(mailId.map(mailIds::lower).isPresent());
			mailPanel.setNextButtonEnabled(mailId.map(mailIds::higher).isPresent());
		};

		// Disable and re-enable the buttons on limit cases
		mailPanel.addPreviousButtonListener(event -> updateNavigationButtonsState.run());
		mailPanel.addNextButtonListener(event -> updateNavigationButtonsState.run());

		// Update the buttons if more IDs are loaded, the limits may have changed
		ctx.addPropertyChangeListener(event -> {
			if (event.getPropertyName().equals(CheckMailContext.MAIL_ID_SET)) {
				updateNavigationButtonsState.run();
			}
		});

		// Change the mail ID from the button actions
		mailPanel.addPreviousButtonListener(event -> {
			Optional<MailId> mailId = ctx.mailId();
			TreeSet<MailId> mailIds = ctx.mailIds();
			ctx.setMailId(mailId.map(mailIds::lower).or(() -> {
				throw new IllegalStateException("No lower ID, button should not be enabled");
			}));
		});
		mailPanel.addNextButtonListener(event -> {
			Optional<MailId> mailId = ctx.mailId();
			TreeSet<MailId> mailIds = ctx.mailIds();
			ctx.setMailId(mailId.map(mailIds::higher).or(() -> {
				throw new IllegalStateException("No higher ID, button should not be enabled");
			}));
		});

		// Change the mail display from the mail ID
		ctx.addPropertyChangeListener(event -> {
			if (event.getPropertyName().equals(CheckMailContext.MAIL_ID)) {
				@SuppressWarnings("unchecked")
				Optional<MailId> mailId = (Optional<MailId>) event.getNewValue();
				mailId.map(ctx.frameContext().mailRepository()::mustGet)//
						.ifPresent(mailPanel::setMail);
			}
		});

		return mailPanel;
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
			return mailId.map(frameContext.mailRepository()::mustGet);
		}

		public static CheckMailContext init(FrameContext ctx) {
			CheckMailContext checkMailCtx = new CheckMailContext(ctx);

			SwingWorker<Void, MailId> loader = createMailIdsLoader(ctx.mailRepository(), mailIds -> {
				checkMailCtx.addMailIds(mailIds);
			});
			loader.execute();

			// Upon creating the window, initialize the ID to be the first mail
			ctx.frame.addWindowListener(new WindowAdapter() {
				@Override
				public void windowOpened(WindowEvent event) {
					SwingUtilities.invokeLater(new Runnable() {
						@Override
						public void run() {
							Repository<MailId, Mail> mailRepo = ctx.mailRepository();
							Repository.Updatable<IssueId, Issue> issueRepo = ctx.issueRepository();
							System.out.println("Searching mail to display...");
							// XXX Consider other monitorable repos (question, etc.)
							Optional<MailId> lastMailIdNotified = issueRepo.streamResources()//
									.map(Issue::history)//
									.flatMap(Monitorable.History::stream)//
									.map(Monitorable.History.Item::source)//
									.map(Source::resolve)//
									.filter(Mail.class::isInstance).map(srcObject -> (Mail) srcObject)//
									.distinct()//
									.sorted(comparing(Mail::receivedDate).reversed())//
									.findFirst()//
									.flatMap(mail -> mailRepo.key(mail));

							Optional<MailId> currentMailId = lastMailIdNotified
									.or(() -> mailRepo.streamKeys().findFirst());
							System.out.println("Set mail to display: " + currentMailId);

							checkMailCtx.setMailId(currentMailId);
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
		private final Repository<MailId, Mail> mailRepository;
		private final Repository.Updatable<IssueId, Issue> issueRepository;
		private final Repository.Updatable<QuestionId, Question> questionRepository;
		private final Function<MailId, Source<Mail>> mailTracker;
		private final Function<Source<Mail>, MailId> mailUntracker;
		private final DateTimeFormatter dateTimeFormatter;
		private final Configuration configuration;
		private final Persister<Configuration> confPersister;

		public FrameContext(Configuration configuration, Persister<Configuration> confPersister, JFrame frame,
				DialogController dialogController, Repository<MailId, Mail> mailRepository,
				Repository.Updatable<IssueId, Issue> issueRepository,
				Repository.Updatable<QuestionId, Question> questionRepository,
				Function<MailId, Source<Mail>> mailTracker, Function<Source<Mail>, MailId> mailUntracker,
				DateTimeFormatter dateTimeFormatter) {
			this.dialogController = dialogController;
			this.mailRepository = mailRepository;
			this.issueRepository = issueRepository;
			this.questionRepository = questionRepository;
			this.frame = frame;
			this.mailTracker = mailTracker;
			this.mailUntracker = mailUntracker;
			this.dateTimeFormatter = dateTimeFormatter;
			this.configuration = configuration;
			this.confPersister = confPersister;
		}

		public Configuration configuration() {
			return configuration;
		}

		public Persister<Configuration> configurationPersister() {
			return confPersister;
		}

		public DateTimeFormatter dateTimeFormatter() {
			return dateTimeFormatter;
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

		public Repository<MailId, Mail> mailRepository() {
			return mailRepository;
		}

		public Repository.Updatable<IssueId, Issue> issueRepository() {
			return issueRepository;
		}

		public Repository.Updatable<QuestionId, Question> questionRepository() {
			return questionRepository;
		}

		public void addTabCloseable(JComponent tabTitle, JComponent tabContent) {
			JTabbedPane tabbedPane = (JTabbedPane) frame.getContentPane().getComponent(0);
			tabbedPane.add(tabContent);
			int tabIndex = tabbedPane.getTabCount() - 1;

			JButton tabClose = new JButton(UIManager.getIcon("InternalFrame.closeIcon"));
			tabClose.setMargin(new Insets(0, 0, 0, 0));
			tabClose.setBorder(null);
			tabClose.setOpaque(false);
			tabClose.addActionListener(event -> tabbedPane.remove(tabIndex));

			JPanel tabPanel = new JPanel(new BorderLayout(5, 0));
			tabPanel.setOpaque(false);
			tabPanel.add(tabTitle, BorderLayout.CENTER);
			tabPanel.add(tabClose, BorderLayout.LINE_END);

			tabbedPane.setTabComponentAt(tabIndex, tabPanel);

			tabbedPane.setSelectedIndex(tabIndex);
		}

		/**
		 * Set a behavior to execute upon entering or leaving a target component.
		 * <p>
		 * Setting a {@link MouseListener#mouseEntered(MouseEvent)} is unfortunately
		 * unreliable, since we might leave the component when entering one of its
		 * children (and entering it again when leaving the children). This method takes
		 * a more global perspective to reliably identify when we actually enter or
		 * leave the component boundaries, independently of its children.
		 * <p>
		 * Source: <a href=
		 * "https://stackoverflow.com/a/28729833">https://stackoverflow.com/a/28729833</a>
		 * 
		 * @param target      the component to monitor
		 * @param enterAction the action to do upon entering the target boundaries
		 * @param leaveAction the action to do upon leaving the target boundaries
		 */
		public void addEnterLeaveActions(JPanel target, Runnable enterAction, Runnable leaveAction) {
			var wrapper = new Object() {
				boolean isOver = false;
			};
			// TODO Remove listener upon target disposal
			Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
				@Override
				public void eventDispatched(AWTEvent event) {
					Object source = event.getSource();
					if (source instanceof JComponent) {
						JComponent comp = (JComponent) source;
						if (SwingUtilities.isDescendingFrom(comp, target)) {
							if (!wrapper.isOver) {
								enterAction.run();
								wrapper.isOver = true;
							}
						} else {
							if (wrapper.isOver) {
								leaveAction.run();
								wrapper.isOver = false;
							}
						}

					}
				}
			}, AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
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
