package fr.vergne.condominium.core.mail;

import java.lang.ref.SoftReference;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class SoftReferencedMail implements Mail {
	private final Supplier<Mail> mailSupplier;

	public SoftReferencedMail(Supplier<Mail> mailSupplier) {
		this.mailSupplier = mailSupplier;
	}

	// Use soft reference to release bytes from memory when needed
	private SoftReference<Mail> reference = new SoftReference<>(null);

	private Mail retrieveMail() {
		Mail mail = reference.get();
		if (mail == null) {
			mail = mailSupplier.get();
			reference = new SoftReference<>(mail);
		}
		return mail;
	}

	@Override
	public String subject() {
		return retrieveMail().subject();
	}

	@Override
	public Address sender() {
		return retrieveMail().sender();
	}

	@Override
	public Stream<Address> receivers() {
		return retrieveMail().receivers();
	}

	@Override
	public ZonedDateTime receivedDate() {
		return retrieveMail().receivedDate();
	}

	@Override
	public List<String> lines() {
		return retrieveMail().lines();
	}

	@Override
	public Headers headers() {
		return retrieveMail().headers();
	}

	@Override
	public Body body() {
		return retrieveMail().body();
	}

	@Override
	public boolean equals(Object obj) {
		return retrieveMail().equals(obj);
	}

	@Override
	public int hashCode() {
		return retrieveMail().hashCode();
	}

	@Override
	public String toString() {
		return retrieveMail().toString();
	}
}