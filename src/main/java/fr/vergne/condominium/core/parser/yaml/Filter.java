package fr.vergne.condominium.core.parser.yaml;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
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

	public static interface PredicateFactory<T> {
		Predicate<T> createPredicate(Filter filter);

		public static abstract class Base<T> implements PredicateFactory<T> {
			@Override
			public Predicate<T> createPredicate(Filter filter) {
				if (filter instanceof Filter.OrFilter typedFilter) {
					return createPredicate(typedFilter);
				} else if (filter instanceof Filter.NameEqualsFilter typedFilter) {
					return createPredicate(typedFilter);
				} else if (filter instanceof Filter.EmailEqualsFilter typedFilter) {
					return createPredicate(typedFilter);
				} else if (filter instanceof Filter.EmailEndsWithFilter typedFilter) {
					return createPredicate(typedFilter);
				} else {
					throw new IllegalArgumentException("Unsupported filter: " + filter.getClass());
				}
			}

			protected abstract Predicate<T> createPredicate(OrFilter filter);

			protected abstract Predicate<T> createPredicate(NameEqualsFilter filter);

			protected abstract Predicate<T> createPredicate(EmailEqualsFilter filter);

			protected abstract Predicate<T> createPredicate(EmailEndsWithFilter filter);

		}

		public static <T> PredicateFactory<T> createFromOptionalAndString(Function<T, Optional<String>> nameExtractor,
				Function<T, String> emailExtractor) {
			return new PredicateFactory.Base<T>() {

				@Override
				protected Predicate<T> createPredicate(Filter.OrFilter filter) {
					return filter.getSubfilters().stream()//
							.map(subfilter -> createPredicate(subfilter))//
							.reduce(Predicate::or)//
							.orElse(address -> false);
				}

				@Override
				protected Predicate<T> createPredicate(Filter.NameEqualsFilter filter) {
					Predicate<String> equalName = Predicate.isEqual(filter.getName());
					return address -> nameExtractor.apply(address).filter(equalName).isPresent();
				}

				@Override
				protected Predicate<T> createPredicate(Filter.EmailEqualsFilter filter) {
					String email = filter.getEmail();
					return address -> emailExtractor.apply(address).equalsIgnoreCase(email);
				}

				@Override
				protected Predicate<T> createPredicate(Filter.EmailEndsWithFilter filter) {
					String emailEnd = filter.getEmailEnd().toLowerCase();
					return address -> emailExtractor.apply(address).toLowerCase().endsWith(emailEnd);
				}
			};
		}

		public static <T> PredicateFactory<T> createFromCollections(Function<T, Collection<String>> namesExtractor,
				Function<T, Collection<String>> emailsExtractor) {
			return new PredicateFactory.Base<T>() {

				@Override
				protected Predicate<T> createPredicate(Filter.OrFilter filter) {
					return filter.getSubfilters().stream()//
							.map(subfilter -> createPredicate(subfilter))//
							.reduce(Predicate::or)//
							.orElse(source -> false);
				}

				@Override
				protected Predicate<T> createPredicate(Filter.NameEqualsFilter filter) {
					String name = filter.getName();
					return source -> namesExtractor.apply(source).contains(name);
				}

				@Override
				protected Predicate<T> createPredicate(Filter.EmailEqualsFilter filter) {
					Predicate<String> equalEmail = Predicate.isEqual(filter.getEmail().toLowerCase());
					return source -> emailsExtractor.apply(source).stream()//
							.map(String::toLowerCase)//
							.anyMatch(equalEmail);
				}

				@Override
				protected Predicate<T> createPredicate(Filter.EmailEndsWithFilter filter) {
					String emailEnd = filter.getEmailEnd().toLowerCase();
					return source -> emailsExtractor.apply(source).stream()//
							.map(String::toLowerCase)//
							.anyMatch(email -> email.endsWith(emailEnd));
				}
			};
		}

	}
}
