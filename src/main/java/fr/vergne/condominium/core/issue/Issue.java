package fr.vergne.condominium.core.issue;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedList;
import java.util.stream.Stream;

import fr.vergne.condominium.core.source.Source;

public interface Issue {

	String title();

	void setTitle(String newTitle);

	History history();

	ZonedDateTime dateTime();

	void notify(Status status, ZonedDateTime dateTime, Source<?> source);

	void denotify(Status status, ZonedDateTime dateTime);

	default Status currentStatus() {
		// Status should not be CONFIRMED if finally REJECTED
		// TODO Do not rely on order anymore, maybe support finite state machine?
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

			String title = issueTitle;

			@Override
			public String title() {
				return title;
			}

			@Override
			public void setTitle(String newTitle) {
				this.title = newTitle;
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
			public void notify(Status status, ZonedDateTime dateTime, Source<?> source) {
				history.add(new History.Item(dateTime, status, source));
			}

			@Override
			public void denotify(Status status, ZonedDateTime dateTime) {
				history.stream()//
						.filter(item -> item.status().equals(status) && item.dateTime().equals(dateTime))//
						.toList()// Store all in temporary list to not mess search with removal
						.forEach(history::remove);
			}
		};
	}

	public enum Status {
		INFO, RENEW, REPORTED, REJECTED, CONFIRMED, RESOLVING, RESOLVED
	}

	public interface History {

		Stream<Item> stream();

		void add(Item item);

		boolean remove(Item item);

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

				@Override
				public boolean remove(Item item) {
					return items.remove(item);
				}
			};
		}
	}
}
