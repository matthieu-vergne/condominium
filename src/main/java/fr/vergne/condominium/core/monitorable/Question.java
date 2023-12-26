package fr.vergne.condominium.core.monitorable;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedList;

import fr.vergne.condominium.core.util.Observer;

public interface Question extends Monitorable<Question.State> {

	public enum State {
		INFO, RENEW, REQUEST, ANSWER
	}

	public static Question create(String title, ZonedDateTime dateTime, History<State> history) {
		return new Question() {

			private String currentTitle = title;
			private final Collection<Observer<String>> titleObservers = new LinkedList<>();

			@Override
			public String title() {
				return currentTitle;
			}

			@Override
			public void setTitle(String newTitle) {
				String oldTitle = this.currentTitle;
				this.currentTitle = newTitle;
				titleObservers.forEach(observer -> observer.change(oldTitle, newTitle));
			}

			@Override
			public void observeTitle(Observer<String> titleObserver) {
				titleObservers.add(titleObserver);
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
