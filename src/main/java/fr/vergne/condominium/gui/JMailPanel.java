package fr.vergne.condominium.gui;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.event.ActionListener;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import fr.vergne.condominium.Main;
import fr.vergne.condominium.core.mail.Mail;
import fr.vergne.condominium.core.mail.Mail.Address;
import fr.vergne.condominium.core.mail.Mail.Body;
import fr.vergne.condominium.core.mail.MimeType;
import fr.vergne.condominium.core.parser.mbox.MBoxParser.ImageBody;
import fr.vergne.condominium.core.parser.mbox.MBoxParser.NamedBinaryBody;

@SuppressWarnings("serial")
public class JMailPanel extends JPanel {
	private final DateTimeFormatter dateTimeFormatter;

	private final JButton previousButton = new JButton("<");
	private final JButton nextButton = new JButton(">");

	private final JLabel mailDate = new JLabel("", JLabel.CENTER);
	private final JLabel mailSender = new JLabel("", JLabel.TRAILING);
	private final JLabel mailReceivers = new JLabel("", JLabel.LEADING);
	private final JLabel mailSubject = new JLabel("", JLabel.LEADING);

	private final JPanel mailAttachments = new JPanel(new FlowLayout(FlowLayout.LEADING));

	private final JTextArea mailArea = new JTextArea();

	public JMailPanel(DateTimeFormatter dateTimeFormatter) {
		this.dateTimeFormatter = dateTimeFormatter;

		JPanel mailSummary = new JPanel();
		{
			mailSummary.setLayout(new GridBagLayout());
			GridBagConstraints constraints = new GridBagConstraints();
			constraints.insets = new Insets(0, 5, 0, 5);
			constraints.fill = GridBagConstraints.HORIZONTAL;
			constraints.weightx = 0;
			mailSummary.add(mailDate, constraints);
			constraints.weightx = 1;
			mailSummary.add(mailSender, constraints);
			constraints.weightx = 0;
			mailSummary.add(new JLabel("→", JLabel.CENTER), constraints);
			constraints.weightx = 1;
			mailSummary.add(mailReceivers, constraints);
			constraints.gridy = 1;
			constraints.gridwidth = 4;
			mailSummary.add(mailSubject, constraints);
		}

		JPanel navigationBar = new JPanel();
		navigationBar.setLayout(new BorderLayout());
		navigationBar.add(previousButton, BorderLayout.LINE_START);
		navigationBar.add(mailSummary, BorderLayout.CENTER);
		navigationBar.add(nextButton, BorderLayout.LINE_END);

		mailArea.setEditable(false);
		mailArea.setLineWrap(true);

		setLayout(new BorderLayout());
		add(navigationBar, BorderLayout.PAGE_START);
		add(new JScrollPane(mailArea, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER),
				BorderLayout.CENTER);
		add(new JScrollPane(mailAttachments, JScrollPane.VERTICAL_SCROLLBAR_NEVER,
				JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED), BorderLayout.PAGE_END);

		clearMail();
	}

	public void setNextButtonEnabled(boolean isEnabled) {
		nextButton.setEnabled(isEnabled);
	}

	public void setPreviousButtonEnabled(boolean isEnabled) {
		previousButton.setEnabled(isEnabled);
	}

	public void addPreviousButtonListener(ActionListener l) {
		previousButton.addActionListener(l);
	}

	public void addNextButtonListener(ActionListener l) {
		nextButton.addActionListener(l);
	}

	public void setMail(Mail mail) {
		requireNonNull(mail, "No mail provided");

		BodyVisitor bodyVisitor = new BodyVisitor();
		try {
			mail.body().visit(bodyVisitor);
		} catch (Exception cause) {
			// Ignore error to display faulty mail on GUI
			// TODO Don't ignore error
			String bodyTree = Main.flattenRecursively(mail.body())//
					.map(Body::mimeType)//
					.map(MimeType::id)//
					.collect(joining(" + "));
			new RuntimeException(bodyTree, cause).printStackTrace();
			System.err.println("--------------------");
			System.err.println(mail.lines().stream().collect(joining("\n")));
		}

		String dateText = dateTimeFormatter.format(mail.receivedDate());
		mailDate.setText(dateText);

		Address address = mail.sender();
		mailSender.setText(formatName(address));
		mailSender.setToolTipText(formatToolTip(address));

		List<Address> receivers = mail.receivers().toList();
		if (receivers.size() == 1) {
			Address firstAddress = mail.receivers().findFirst().get();
			mailReceivers.setText(formatName(firstAddress));
			mailReceivers.setToolTipText(formatToolTip(firstAddress));
		} else {
			Address firstAddress = mail.receivers().findFirst().get();
			mailReceivers.setText(formatName(firstAddress) + " (...)");
			String allReceivers = "<html>" + receivers.stream()//
					.map(this::formatToolTip)//
					.map(text -> text.replace("<", "&lt;"))//
					.map(text -> text.replace(">", "&gt;"))//
					.collect(joining("<br>")) //
					+ "</html>";
			mailReceivers.setToolTipText(allReceivers);
		}

		mailSubject.setText(mail.subject());

		String bodyText = bodyVisitor.plainText.or(() -> bodyVisitor.html).orElseThrow();
		mailArea.setText(bodyText);
		mailArea.setCaretPosition(0);

		mailAttachments.removeAll();
		bodyVisitor.attachedPdfs.forEach((key, pdf) -> {
			JButton button = new JButton(pdf.name());
			button.addActionListener(event -> {
				Path path;
				String pdfName = pdf.name();
				try {
					path = Files.createTempFile("", pdfName);
				} catch (IOException cause) {
					throw new RuntimeException("Cannot create temp path for: " + pdfName, cause);
				}
				try {
					Files.write(path, pdf.bytes());
				} catch (IOException cause) {
					throw new RuntimeException("Cannot write temp file: " + path, cause);
				}
				try {
					Desktop.getDesktop().open(path.toFile());
				} catch (IOException cause) {
					throw new RuntimeException("Cannot open: " + path, cause);
				}
			});
			mailAttachments.add(button);
		});
	}

	interface Pdf {
		String name();

		byte[] bytes();
	}

	public static class BodyVisitor implements Body.Visitor {

		private Optional<String> plainText = Optional.empty();

		@Override
		public void visitPlainText(Body body) {
			if (this.plainText.isPresent()) {
				throw new IllegalStateException("Already a plain value: " + this.plainText.get());
			} else {
				this.plainText = Optional.of(((Body.Textual) body).text());
			}
		}

		private Optional<String> html = Optional.empty();

		@Override
		public void visitHtml(Body body) {
			if (this.html.isPresent()) {
				throw new IllegalStateException("Already an HTML value: " + this.html.get());
			} else {
				this.html = Optional.of(((Body.Textual) body).text());
			}
		}

		private final Map<String, Image> includedImages = new HashMap<>();

		@Override
		public void visitImage(Body body) {
			ImageBody imageBody = (ImageBody) body;
			byte[] bytes = imageBody.bytes();
			Image image;
			try {
				image = ImageIO.read(ImageIO.createImageInputStream(new ByteArrayInputStream(bytes)));
			} catch (IOException cause) {
				throw new RuntimeException("Cannot build " + body.mimeType() + " image from bytes", cause);
			}
			String id = imageBody.contentId();
			// FIXME Might be attachment (Multipart-Mixed), not included (Multipart-Related)
			Image existingImage = includedImages.putIfAbsent(id, image);
			if (existingImage != null) {
				throw new IllegalStateException("Image key already used: " + id);
			}
		}

		private final Map<String, Pdf> attachedPdfs = new HashMap<>();

		@Override
		public void visitPdf(Body body) {
			NamedBinaryBody pdfBody = (NamedBinaryBody) body;
			String id = pdfBody.contentId();
			String name = pdfBody.name();
			byte[] bytes = pdfBody.bytes();
			Pdf existingPdf = attachedPdfs.putIfAbsent(id, (Pdf) new Pdf() {

				@Override
				public String name() {
					return name;
				}

				@Override
				public byte[] bytes() {
					return bytes;
				}
			});
			if (existingPdf != null) {
				throw new IllegalStateException("PDF key already used: " + id);
			}
		}

		@Override
		public void visitMultipartAlternative(Body body) {
			// TODO All alternatives make a single resource
			// Example: plain + HTML versions
			for (Body subBody : ((Body.Composed) body).bodies()) {
				subBody.visit(this);
			}
		}

		@Override
		public void visitMultipartRelated(Body body) {
			// TODO All related make a composed resource
			// Example: mail + included image
			for (Body subBody : ((Body.Composed) body).bodies()) {
				subBody.visit(this);
			}
		}

		@Override
		public void visitMultipartMixed(Body body) {
			// TODO All resources are separate
			// Example: mail + attachments
			for (Body subBody : ((Body.Composed) body).bodies()) {
				subBody.visit(this);
			}
		}
	}

	public void clearMail() {
		mailDate.setText("-");
		mailSender.setText("-");
		mailSender.setToolTipText(null);
		mailReceivers.setText("-");
		mailReceivers.setToolTipText(null);
		mailSubject.setText("-");
		mailArea.setText("<no mail displayed>");
		mailAttachments.removeAll();
	}

	private String formatToolTip(Address address) {
		String email = address.email();
		return address.name()//
				.map(name -> name + " <" + email + ">")//
				.orElse(email);
	}

	private String formatName(Address address) {
		return address.name().orElseGet(address::email);
	}
}
