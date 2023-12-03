package fr.vergne.condominium.core.issue;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedList;
import java.util.stream.Stream;

import fr.vergne.condominium.core.source.Source;

public interface Issue {

	String title();

	History history();

	ZonedDateTime dateTime();

	void notify(Status reported, Source<?> source, ZonedDateTime dateTime);

	default Status currentStatus() {
		return history().stream()//
				.map(History.Item::status)//
				// TODO No need to go further if we find the max
				.max(Status::compareTo)//
				.orElseThrow();
	}

	public static Issue createEmpty(String issueTitle, ZonedDateTime dateTime) {
		return create(issueTitle, dateTime, History.createEmpty());
	}

	public static Issue create(String issueTitle, ZonedDateTime dateTime, History history) {
		return new Issue() {

			@Override
			public String title() {
				return issueTitle;
			}

			@Override
			public History history() {
				return history;
			}

			@Override
			public ZonedDateTime dateTime() {
				return dateTime;
			}

			@Override
			public void notify(Status status, Source<?> source, ZonedDateTime dateTime) {
				history.add(new History.Item(dateTime, status, source));
			}
		};
	}

	public enum Status {
		REPORTED, CONFIRMED, RESOLVING, RESOLVED
	}

	public interface History {

		Stream<Item> stream();

		void add(Item item);

		public static record Item(ZonedDateTime dateTime, Status status, Source<?> source) {
		}

		static History createEmpty() {
			Collection<Item> items = new LinkedList<>();
			return new History() {

				@Override
				public Stream<Item> stream() {
					return items.stream();
				}

				@Override
				public void add(Item item) {
					items.add(item);
				}
			};
		}
	}
}
