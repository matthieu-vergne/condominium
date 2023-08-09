package fr.vergne.condominium;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import jakarta.mail.Address;
import jakarta.mail.Authenticator;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import jakarta.mail.Store;

@SuppressWarnings("unused")
public class ImapTrial {

	// Documentation:
	// https://jakarta.ee/specifications/mail/2.0/jakarta-mail-spec-2.0.html
	// Cannot connect, require specific account configuration:
	// https://stackoverflow.com/questions/56524735/how-to-fix-javax-mail-authenticationfailedexception-invalid-credentials
	// MBox case does not work either with provider at:
	// https://mvnrepository.com/artifact/com.sun.mail/mbox/1.6.7.payara-p1
	public static void main(String[] args) {
		// SMTP = RFC 821
		// POP3 = RFC 1939
		// IMAP = RFC 2060
		final var imap = new Object() {
			String address = "imap.gmail.com";
			boolean requireSsl = true;
			int port = 993;
		};
		final var smtp = new Object() {
			String address = "smtp.gmail.com";
			boolean requireSsl = true;
			boolean requireTls = true;
			boolean requireAuthent = true;
			int sslPort = 465;
			int tlsPort = 587;
		};
		final String gmailName = "TODO";
		final String gmailLogin = "TODO";
		final String gmailPassword = "TODO";// TODO Hide

		/*
		 * Pour éviter de perdre temporairement l'accès à votre compte, assurez-vous de
		 * ne pas dépasser 2 500 Mo par jour pour les téléchargements, et 500 Mo pour
		 * les importations avec IMAP. Si vous configurez le même compte IMAP sur
		 * plusieurs ordinateurs, laissez passer un peu de temps entre deux
		 * configurations.
		 */

		{
			final Properties properties = System.getProperties();
			final Authenticator authenticator = null;// TODO
			final Session session = Session.getInstance(properties, authenticator);
			session.setDebug(true);// TODO remove
//			System.out.println("Providers:");
//			Stream.of(session.getProviders()).forEach(provider -> {
//				System.out.println("- " + provider);
//			});

//			String transportProtocol = "smtps";
//			Transport transport;
//			try {
//				transport = session.getTransport(transportProtocol);
//			} catch (NoSuchProviderException cause) {
//				throw new RuntimeException("Cannot get store for " + transportProtocol, cause);
//			}
//			System.out.println(transportProtocol + " transport: " + transport);

			final String storeProtocol = "imaps";
			Store store;
			try {
				store = session.getStore(storeProtocol);
			} catch (final NoSuchProviderException cause) {
				throw new RuntimeException("Cannot get store for " + storeProtocol, cause);
			}
			System.out.println(storeProtocol + " store: " + store);
			try {
				store.connect(imap.address, imap.port, gmailLogin, gmailPassword);
			} catch (final MessagingException cause) {
				throw new RuntimeException("Cannot connect store " + store, cause);
			}
			Folder folder;
			try {
				folder = store.getDefaultFolder();
			} catch (final MessagingException cause) {
				throw new RuntimeException("Cannot get default folder from store " + store, cause);
			}
			final int msgIndex = 1;
			Message message;
			try {
				message = folder.getMessage(msgIndex);
			} catch (final MessagingException cause) {
				throw new RuntimeException("Cannot get message from folder " + folder, cause);
			}
			System.out.println("IMAP message " + msgIndex + ":");
			String subject;
			try {
				subject = message.getSubject();
			} catch (final MessagingException cause) {
				throw new RuntimeException("Cannot get subject from message " + message, cause);
			}
			System.out.println("- SUBJECT: " + subject);
			List<Address> recipients;
			try {
				recipients = Arrays.asList(message.getAllRecipients());
			} catch (final MessagingException cause) {
				throw new RuntimeException("Cannot get recipients from message: " + subject, cause);
			}
			System.out.println("- RECIPTS: " + recipients);
			List<jakarta.mail.Header> headers;
			try {
				headers = Collections.list(message.getAllHeaders());
			} catch (final MessagingException cause) {
				throw new RuntimeException("Cannot get headers from message: " + subject, cause);
			}
			System.out.println("- HEADERS: " + headers);
			String contentType;
			try {
				contentType = message.getContentType();
			} catch (final MessagingException cause) {
				throw new RuntimeException("Cannot get content-type from message: " + subject, cause);
			}
			System.out.println("- CT-TYPE: " + contentType);
			Object content;
			try {
				content = message.getContent();
			} catch (IOException | MessagingException cause) {
				throw new RuntimeException("Cannot get content from message: " + subject, cause);
			}
			System.out.println("- CONTENT: " + content);

			try {
				folder.close();
			} catch (final MessagingException cause) {
				throw new RuntimeException("Cannot close folder " + folder, cause);
			}
			try {
				store.close();
			} catch (final MessagingException cause) {
				throw new RuntimeException("Cannot close store " + store, cause);
			}
		}
	}
}
