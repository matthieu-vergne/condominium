package fr.vergne.condominium.core.monitorable;

import java.time.ZonedDateTime;

public interface Issue extends Monitorable<Issue.State> {

	public enum State {
		INFO, RENEW, REPORTED, REJECTED, CONFIRMED, RESOLVING, RESOLVED
	}

	public static Issue create(String title, ZonedDateTime dateTime, History<State> history) {
		return new Issue() {

			String currentTitle = title;

			@Override
			public String title() {
				return currentTitle;
			}

			@Override
			public void setTitle(String newTitle) {
				this.currentTitle = newTitle;
			}

			@Override
			public History<State> history() {
				return history;
			}

			@Override
			public ZonedDateTime dateTime() {
				return dateTime;
			}
		};
	}
}
