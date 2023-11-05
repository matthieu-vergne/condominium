package fr.vergne.condominium.core.issue;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedList;
import java.util.stream.Stream;

import fr.vergne.condominium.core.mail.Mail;

public interface Issue {

	String title();

	History history();

	void notifyByMail(Mail mail, Status status);

	default Status currentStatus() {
		return history().stream()//
				.map(History.Item::status)//
				// TODO No need to go further if we find the max
				.max(Status::compareTo)//
				.orElseThrow();
	}

	public static Issue create(String issueTitle) {
		History history = History.createEmpty();
		Issue issue = new Issue() {

			@Override
			public String title() {
				return issueTitle;
			}

			@Override
			public History history() {
				return history;
			}

			@Override
			public void notifyByMail(Mail mail, Status status) {
				history.add(new History.Item(mail.receivedDate(), status, mail));
			}
		};
		return issue;
	}

	public enum Status {
		REPORTED, CONFIRMED, RESOLVING, RESOLVED
	}

	public interface History {

		Stream<Item> stream();

		void add(Item item);

		public static record Item(ZonedDateTime datetime, Status status, Object source) {
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
