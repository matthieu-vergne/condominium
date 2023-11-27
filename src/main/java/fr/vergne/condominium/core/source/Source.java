package fr.vergne.condominium.core.source;

import java.time.ZonedDateTime;

import fr.vergne.condominium.core.mail.Mail;

public interface Source {

	ZonedDateTime date();

	interface Provider {

		Source sourceMail(Mail mail);

	}
}
