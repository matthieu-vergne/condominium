package fr.vergne.condominium.core.monitorable;

import java.time.ZonedDateTime;

public interface Question extends Monitorable<Question.State> {

	public enum State {
		INFO, RENEW, REQUEST, ANSWER
	}

	public static Question create(String title, ZonedDateTime dateTime, History<State> history) {
		return new Question() {

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
