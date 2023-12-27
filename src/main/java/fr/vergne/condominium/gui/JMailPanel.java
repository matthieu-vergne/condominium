package fr.vergne.condominium.gui;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import fr.vergne.condominium.Main;
import fr.vergne.condominium.core.mail.Mail;
import fr.vergne.condominium.core.mail.Mail.Address;

@SuppressWarnings("serial")
public class JMailPanel extends JPanel {
	private final DateTimeFormatter dateTimeFormatter;

	private final JButton previousButton = new JButton("<");
	private final JButton nextButton = new JButton(">");

	private final JLabel mailDate = new JLabel("", JLabel.CENTER);
	private final JLabel mailSender = new JLabel("", JLabel.TRAILING);
	private final JLabel mailReceivers = new JLabel("", JLabel.LEADING);
	private final JLabel mailSubject = new JLabel("", JLabel.LEADING);

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
	}

	public void clearMail() {
		mailDate.setText("-");
		mailSender.setText("-");
		mailSender.setToolTipText(null);
		mailReceivers.setText("-");
		mailReceivers.setToolTipText(null);
		mailSubject.setText("-");
		mailArea.setText("<no mail displayed>");
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
