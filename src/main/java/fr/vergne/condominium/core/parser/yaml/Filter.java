package fr.vergne.condominium.core.parser.yaml;

import java.util.List;
import java.util.stream.Collectors;

public interface Filter {

	public static class OrFilter implements Filter {
		private List<Filter> subfilters;

		public OrFilter(List<Filter> subfilters) {
			this.subfilters = subfilters;
		}

		public List<Filter> getSubfilters() {
			return subfilters;
		}

		@Override
		public String toString() {
			return subfilters.stream().map(Filter::toString).collect(Collectors.joining(" OR "));
		}
	}

	public static class NameEqualsFilter implements Filter {
		private String name;

		public NameEqualsFilter(String name) {
			this.name = name;
		}

		public String getName() {
			return name;
		}

		@Override
		public String toString() {
			return "name=" + name;
		}
	}

	public static class EmailEqualsFilter implements Filter {
		private String email;

		public EmailEqualsFilter(String email) {
			this.email = email;
		}

		public String getEmail() {
			return email;
		}

		@Override
		public String toString() {
			return "email=" + email;
		}
	}

	public static class EmailEndsWithFilter implements Filter {
		private String end;

		public EmailEndsWithFilter(String end) {
			this.end = end;
		}

		public String getEmailEnd() {
			return end;
		}

		@Override
		public String toString() {
			return "email ends with: " + end;
		}
	}
}
