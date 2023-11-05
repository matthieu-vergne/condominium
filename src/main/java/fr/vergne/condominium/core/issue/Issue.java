package fr.vergne.condominium.core.issue;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedList;

import fr.vergne.condominium.core.mail.Mail;

public interface Issue {

	String title();

	History history();

	void notifyByMail(Mail mail, Status status);

	default Status currentStatus() {
		return history().lastItem().status();
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

		Item lastItem();

		void add(Item item);

		public static record Item(ZonedDateTime datetime, Status status, Object source) {
		}

		static History createEmpty() {
			Collection<Item> items = new LinkedList<>();
			return new History() {

				@Override
				public Item lastItem() {
					return items.stream().reduce((i1, i2) -> {
						return i1.datetime.compareTo(i2.datetime) < 0 ? i1 : i2;
					}).orElseThrow();
				}

				@Override
				public void add(Item item) {
					items.add(item);
				}
			};
		}
	}
}
