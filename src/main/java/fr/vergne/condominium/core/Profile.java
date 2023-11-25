package fr.vergne.condominium.core;

import static java.util.function.Predicate.isEqual;

import java.util.Objects;
import java.util.Set;

import fr.vergne.condominium.core.mail.Mail.Address;

public interface Profile {

	Set<String> names();

	Set<String> emails();

	boolean contains(Address address);

	static class Base implements Profile {

		private final Set<String> emails;
		private final Set<String> names;

		public Base(Set<String> names, Set<String> emails) {
			this.names = names;
			this.emails = emails;
		}

		@Override
		public Set<String> names() {
			return names;
		}

		@Override
		public Set<String> emails() {
			return emails;
		}

		@Override
		public boolean contains(Address address) {
			return emails.stream()//
					.map(String::toLowerCase)//
					.anyMatch(isEqual(address.email().toLowerCase()));
		}

		@Override
		public String toString() {
			return names + " as " + emails;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof Profile that //
					&& Objects.equals(this.names(), that.names()) //
					&& Objects.equals(this.emails(), that.emails());
		}

		@Override
		public int hashCode() {
			return Objects.hash(names, emails);
		}
	}
}
