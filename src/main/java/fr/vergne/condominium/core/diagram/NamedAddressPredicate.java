package fr.vergne.condominium.core.diagram;

import java.util.function.Predicate;

import fr.vergne.condominium.core.mail.Mail;

public interface NamedAddressPredicate extends Predicate<Mail.Address> {
	String name();

	public static NamedAddressPredicate create(String name, Predicate<Mail.Address> addressPredicate) {
		return new NamedAddressPredicate() {

			@Override
			public String name() {
				return name;
			}

			@Override
			public boolean test(Mail.Address address) {
				return addressPredicate.test(address);
			}
		};
	}

	public static NamedAddressPredicate anyAs(String name) {
		return new NamedAddressPredicate() {

			@Override
			public String name() {
				return name;
			}

			@Override
			public boolean test(Mail.Address address) {
				return true;
			}
		};
	}
}
