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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

		X x = new X();
		try {
			process(x, mail.body());
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

		String bodyText = Main.getPlainOrHtmlBody(mail).text();
		mailArea.setText(bodyText);
		mailArea.setCaretPosition(0);

		mailAttachments.removeAll();
		x.attachedPdfs.forEach((key, pdf) -> {
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

	private X processMixed(Body body) {
		X x = new X();
		for (Body subBody : ((Body.Composed) body).bodies()) {
			process(x, subBody);
		}
		// TODO All resources are separate
		// Example: mail + attachments
		return x;
	}

	private X processOctetStream(Body body) {
		NamedBinaryBody binaryBody = (NamedBinaryBody) body;
		if (binaryBody.name().endsWith(".pdf")) {
			return processPdf(binaryBody);
		} else {
			throw new RuntimeException("Not implemented yet: " + body);
		}
	}

	private X processPdf(Body body) {
		return processPdf((NamedBinaryBody) body);
	}

	private X processPdf(NamedBinaryBody pdfBody) {
		X x = new X();
		String name = pdfBody.name();
		byte[] bytes = pdfBody.bytes();
		String id = pdfBody.contentId();
		x.attachedPdfs.put(id, new Pdf() {

			@Override
			public String name() {
				return name;
			}

			@Override
			public byte[] bytes() {
				return bytes;
			}
		});
		return x;
	}

	private X processRelated(Body body) {
		X x = new X();
		for (Body subBody : ((Body.Composed) body).bodies()) {
			process(x, subBody);
		}
		// TODO All related make a composed resource
		// Example: mail + included image
		return x;
	}

	private X processImage(Body body) {
		ImageBody imageBody = (ImageBody) body;
		byte[] bytes = imageBody.bytes();
		BufferedImage image;
		try {
			image = ImageIO.read(ImageIO.createImageInputStream(new ByteArrayInputStream(bytes)));
		} catch (IOException cause) {
			throw new RuntimeException("Cannot build " + body.mimeType() + " image from bytes", cause);
		}
		String id = imageBody.contentId();
		X x = new X();
		x.includedImages.put(id, image);
		return x;
	}

	private X processAlternatives(Body body) {
		X x = new X();
		for (Body subBody : ((Body.Composed) body).bodies()) {
			process(x, subBody);
		}
		// TODO All alternatives make a single resource
		// Example: plain + HTML versions
		return x;
	}

	private void process(X x, Body body) {
		if (body.mimeType().equals(MimeType.Text.PLAIN)) {
			compose(x, processPlain(body));
		} else if (body.mimeType().equals(MimeType.Text.HTML)) {
			compose(x, processHtml(body));
		} else if (body.mimeType().equals(MimeType.Image.JPEG)) {
			compose(x, processImage(body));
		} else if (body.mimeType().equals(MimeType.Image.PNG)) {
			compose(x, processImage(body));
		} else if (body.mimeType().equals(MimeType.Image.GIF)) {
			compose(x, processImage(body));
		} else if (body.mimeType().equals(MimeType.Image.HEIC)) {
			compose(x, processImage(body));
		} else if (body.mimeType().equals(MimeType.Application.PDF)) {
			compose(x, processPdf(body));
		} else if (body.mimeType().equals(MimeType.Application.OCTET_STREAM)) {
			compose(x, processOctetStream(body));
		} else if (body.mimeType().equals(MimeType.Multipart.ALTERNATIVE)) {
			compose(x, processAlternatives(body));
		} else if (body.mimeType().equals(MimeType.Multipart.RELATED)) {
			compose(x, processRelated(body));
		} else if (body.mimeType().equals(MimeType.Multipart.MIXED)) {
			compose(x, processMixed(body));
		} else {
			throw new RuntimeException("Not implemented yet: " + body);
		}
	}

	private X processPlain(Body body) {
		Body.Textual plainBody = (Body.Textual) body;
		X x = new X();
		x.plainText = Optional.of(plainBody.text());
		return x;
	}

	private void compose(X x, X y) {
		if (y.plainText.isPresent()) {
			if (x.plainText.isPresent()) {
				throw new IllegalStateException("Already a plain value: " + x.plainText.get());
			} else {
				x.plainText = y.plainText;
			}
		}
		if (y.htmlText.isPresent()) {
			if (x.htmlText.isPresent()) {
				throw new IllegalStateException("Already an HTML value: " + x.htmlText.get());
			} else {
				x.htmlText = y.htmlText;
			}
		}
		if (!y.includedImages.isEmpty()) {
			Set<String> oldKeys = x.includedImages.keySet();
			Set<String> newKeys = y.includedImages.keySet();
			List<String> sharedKeys = oldKeys.stream().filter(newKeys::contains).toList();
			if (!sharedKeys.isEmpty()) {
				throw new IllegalStateException("Shared included images: " + sharedKeys);
			} else {
				x.includedImages.putAll(y.includedImages);
			}
		}
		if (!y.attachedPdfs.isEmpty()) {
			Set<String> oldKeys = x.attachedPdfs.keySet();
			Set<String> newKeys = y.attachedPdfs.keySet();
			List<String> sharedKeys = oldKeys.stream().filter(newKeys::contains).toList();
			if (!sharedKeys.isEmpty()) {
				throw new IllegalStateException("Shared attached PDFs: " + sharedKeys);
			} else {
				x.attachedPdfs.putAll(y.attachedPdfs);
			}
		}
	}

	private X processHtml(Body body) {
		Body.Textual htmlBody = (Body.Textual) body;
		X x = new X();
		x.htmlText = Optional.of(htmlBody.text());
		return x;
	}

	interface Pdf {
		String name();

		byte[] bytes();
	}

	public static class X {
		Optional<String> plainText = Optional.empty();
		Optional<String> htmlText = Optional.empty();
		Map<String, Image> includedImages = new HashMap<>();
		Map<String, Pdf> attachedPdfs = new HashMap<>();

		// FIXME Compose Xs iteratively
		// TODO Rename X
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
