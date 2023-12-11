package fr.vergne.condominium;

import java.awt.BorderLayout;
import java.awt.Color;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;
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

import fr.vergne.condominium.Main.MailId;
import fr.vergne.condominium.core.issue.Issue;
import fr.vergne.condominium.core.mail.Mail;
import fr.vergne.condominium.core.repository.Repository;

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
		Supplier<Optional<Repository<MailId, Mail>>> mailRepositorySupplier = () -> {
			System.out.println("Request mails");
			return confSupplier.get()//
					.map(conf -> conf.getProperty("outFolder"))//
					.map(outFolderConf -> {
						System.out.println("Load mails from: " + outFolderConf);
						Path outFolderPath = Paths.get(outFolderConf);
						Path mailRepositoryPath = outFolderPath.resolve("mails");
						return Main.createMailRepository(mailRepositoryPath);
					});
		};
		FrameContext ctx = new FrameContext(new DialogController(this), mailRepositorySupplier);

		// TODO Create settings menu
		// TODO Configure mails repository path
		// TODO Configure issues repository path

		Container contentPane = getContentPane();
		JTabbedPane tabsPane = new JTabbedPane(JTabbedPane.TOP);
		contentPane.add(tabsPane);
		tabsPane.add("Check mails", createTab(ctx));

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

	private static JComponent createTab(FrameContext ctx) {
		JPanel panel = new JPanel();

		panel.setLayout(new GridLayout(1, 0));

		panel.add(new JScrollPane(createMailDisplay(ctx), JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER));
		panel.add(new JScrollPane(createIssuesArea(ctx), JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER));

		return panel;
	}

	private static JComponent createIssuesArea(FrameContext ctx) {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));

		// TODO Feed with actual issues
		panel.add(createIssueRow(ctx));
		panel.add(createIssueRow(ctx));
		panel.add(createIssueRow(ctx));

		return panel;
	}

	private static JComponent createIssueRow(FrameContext ctx) {
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
			constraints.gridx = 0;
			constraints.gridy = 0;
			constraints.fill = GridBagConstraints.HORIZONTAL;
			constraints.weightx = 1;
			issueRow.add(createIssueTitle(), constraints);
		}

		{
			record StatusButtonConf(String title) {
			}
			Map<Issue.Status, StatusButtonConf> statusButtonConfs = Map.of(//
					Issue.Status.REPORTED, new StatusButtonConf("📣"), // ⚡✋👀👁📢📣🚨🕬
					Issue.Status.CONFIRMED, new StatusButtonConf("👍"), // ✍👍👌
					Issue.Status.RESOLVING, new StatusButtonConf("🔨"), // ⛏⚒🔨
					Issue.Status.RESOLVED, new StatusButtonConf("✔")// ☑✅✓✔
			);
			GridBagConstraints constraints = new GridBagConstraints();
			constraints.gridx = 0;
			constraints.gridy = 0;
			constraints.fill = GridBagConstraints.NONE;
			for (Issue.Status status : Issue.Status.values()) {
				constraints.gridx++;
				StatusButtonConf conf = statusButtonConfs.get(status);
				issueRow.add(createStatusButton(ctx, status, conf.title(), buttonInsets), constraints);
			}
		}

		{
			GridBagConstraints constraints = new GridBagConstraints();
			constraints.gridx = 5;
			constraints.gridy = 0;
			constraints.fill = GridBagConstraints.NONE;
			constraints.insets = new Insets(0, 5, 0, 0);
			issueRow.add(createIssueDetailsButton(ctx, buttonInsets), constraints);
		}

		MouseAdapter mouseAdapter = new MouseAdapter() {

			Color defaultBackground;

			@Override
			public void mouseEntered(MouseEvent e) {
				defaultBackground = issueRow.getBackground();
				issueRow.setBackground(Color.YELLOW);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				issueRow.setBackground(defaultBackground);
			}
		};
		issueRow.addMouseListener(mouseAdapter);
		for (Component child : issueRow.getComponents()) {
			child.addMouseListener(mouseAdapter);
		}

		return issueRow;
	}

	private static JButton createIssueDetailsButton(FrameContext ctx, Insets buttonInsets) {
		JButton detailsButton = new JButton("📋");
		detailsButton.setMargin(buttonInsets);
		detailsButton.addActionListener(event -> {
			ctx.dialogController.showMessageDialog("Show issue details");
		});
		return detailsButton;
	}

	private static JLabel createIssueTitle() {
		return new JLabel("(issue title)");
	}

	private static JButton createStatusButton(FrameContext ctx, Issue.Status status, String title,
			Insets buttonInsets) {
		// TODO Show enabled if displayed mail is part of the issue history
		// TODO Add displayed mail to issue when enabling the button
		// TODO Remove displayed mail from issue when disabling the button
		// TODO When enabling this button, disable the others
		JButton statusButton = new JButton(title);
		statusButton.setMargin(buttonInsets);
		statusButton.addActionListener(event -> {
			ctx.dialogController.showMessageDialog(status);
		});
		return statusButton;
	}

	private static JComponent createMailDisplay(FrameContext ctx) {
		JPanel panel = new JPanel();

		panel.setLayout(new BorderLayout());

		panel.add(createMailNavigationBar(ctx), BorderLayout.PAGE_START);
		panel.add(createMailArea(), BorderLayout.CENTER);

		return panel;
	}

	private static JComponent createMailNavigationBar(FrameContext ctx) {
		JPanel navigationBar = new JPanel();
		navigationBar.setLayout(new BorderLayout());

		JButton previousButton = new JButton("<");
		previousButton.addActionListener(event -> {
			// TODO Go to previous mail
			ctx.dialogController.showMessageDialog("Previous mail");
		});
		JButton nextButton = new JButton(">");
		nextButton.addActionListener(event -> {
			// TODO Go to next mail
			ctx.dialogController.showMessageDialog("Next mail");
		});

		navigationBar.add(previousButton, BorderLayout.LINE_START);
		navigationBar.add(createMailSummary(ctx), BorderLayout.CENTER);
		navigationBar.add(nextButton, BorderLayout.LINE_END);

		return navigationBar;
	}

	private static JLabel createMailSummary(FrameContext ctx) {
		// TODO Display mail summary
		JLabel mailSummary = new JLabel("", JLabel.CENTER) {
			@Override
			public String getText() {
				Optional<Repository<MailId, Mail>> optional = ctx.mailRepositorySupplier.get();
				return optional.map(repo -> {
					Mail mail = repo.streamResources().findFirst().get();
					return DateTimeFormatter.ISO_DATE_TIME.format(mail.receivedDate());
				}).orElse("-");
			}
		};
		return mailSummary;
	}

	private static JTextArea createMailArea() {
		// TODO Feed with actual mail body
		JTextArea mailArea = new JTextArea("(mail content)");
		mailArea.setEditable(false);
		return mailArea;
	}

	static class FrameContext {

		private final DialogController dialogController;
		private final Supplier<Optional<Repository<MailId, Mail>>> mailRepositorySupplier;

		public FrameContext(DialogController dialogController,
				Supplier<Optional<Repository<MailId, Mail>>> mailRepositorySupplier) {
			this.dialogController = dialogController;
			this.mailRepositorySupplier = mailRepositorySupplier;
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
