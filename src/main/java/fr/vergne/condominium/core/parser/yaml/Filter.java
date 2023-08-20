package fr.vergne.condominium.core.parser.yaml;

import static java.util.function.Predicate.isEqual;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import fr.vergne.condominium.core.mail.Mail;

public interface Filter {

	// FIXME Remove
	@Deprecated
	public Predicate<fr.vergne.condominium.core.Profile> toProfilePredicate();

	public Predicate<Mail.Address> toAddressPredicate();

	public static class OrFilter implements Filter {
		private List<Filter> filters;

		public OrFilter(List<Filter> filters) {
			this.filters = filters;
		}

		@Override
		public Predicate<fr.vergne.condominium.core.Profile> toProfilePredicate() {
			return filters.stream()//
					.map(Filter::toProfilePredicate)//
					.reduce(Predicate::or)//
					.orElse(p -> false);
		}

		@Override
		public Predicate<Mail.Address> toAddressPredicate() {
			return filters.stream()//
					.map(Filter::toAddressPredicate)//
					.reduce(Predicate::or)//
					.orElse(p -> false);
		}

		@Override
		public String toString() {
			return filters.stream().map(Filter::toString).collect(Collectors.joining(" OR "));
		}
	}

	public static class NameEqualsFilter implements Filter {
		private String name;

		public NameEqualsFilter(String name) {
			this.name = name;
		}

		public String getValue() {
			return name;
		}

		@Override
		public Predicate<fr.vergne.condominium.core.Profile> toProfilePredicate() {
			return profile -> profile.names().contains(name);
		}

		@Override
		public Predicate<Mail.Address> toAddressPredicate() {
			return address -> address.name().filter(isEqual(name)).isPresent();
		}

		@Override
		public String toString() {
			return "name=" + name;
		}
	}

	public static class EmailEqualsFilter implements Filter {
		private String email;

		public EmailEqualsFilter(String email) {
			this.email = email.toLowerCase();
		}

		public String getValue() {
			return email;
		}

		@Override
		public Predicate<fr.vergne.condominium.core.Profile> toProfilePredicate() {
			return profile -> profile.emails().stream().map(String::toLowerCase).anyMatch(Predicate.isEqual(email));
		}

		@Override
		public Predicate<Mail.Address> toAddressPredicate() {
			return address -> address.email().equalsIgnoreCase(email);
		}

		@Override
		public String toString() {
			return "email=" + email;
		}
	}

	public static class EmailEndsWithFilter implements Filter {
		private String end;

		public EmailEndsWithFilter(String end) {
			this.end = end.toLowerCase();
		}

		public String getValue() {
			return end;
		}

		@Override
		public Predicate<fr.vergne.condominium.core.Profile> toProfilePredicate() {
			return (profile) -> profile.emails().stream().map(String::toLowerCase).anyMatch(email -> email.endsWith(end));
		}

		@Override
		public Predicate<Mail.Address> toAddressPredicate() {
			return address -> address.email().toLowerCase().endsWith(end);
		}

		@Override
		public String toString() {
			return "email ends with: " + end;
		}
	}
}
