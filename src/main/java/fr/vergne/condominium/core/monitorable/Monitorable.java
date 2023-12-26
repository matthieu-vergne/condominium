package fr.vergne.condominium.core.monitorable;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedList;
import java.util.stream.Stream;

import fr.vergne.condominium.core.source.Source;
import fr.vergne.condominium.core.util.Observer;

public interface Monitorable<S> {

	String title();

	void setTitle(String newTitle);

	void observeTitle(Observer<String> titleObserver);

	History<S> history();

	ZonedDateTime dateTime();

	default void notify(S state, ZonedDateTime dateTime, Source<?> source) {
		history().add(new History.Item<S>(dateTime, state, source));
	}

	default void denotify(S state, ZonedDateTime dateTime) {
		History<S> history = history();
		history.stream()//
				.filter(item -> item.state().equals(state) && item.dateTime().equals(dateTime))//
				.toList()// Store all in temporary list to not mess search with removal
				.forEach(history::remove);
	}

	interface Factory<M, S> {
		M createMonitorable(String title, ZonedDateTime dateTime, History<S> history);
	}

	public interface History<S> {

		Stream<Item<S>> stream();

		void add(Item<S> item);

		boolean remove(Item<S> item);

		public static record Item<S>(ZonedDateTime dateTime, S state, Source<?> source) {
		}

		static <S> History<S> createEmpty() {
			Collection<Item<S>> items = new LinkedList<>();
			return new History<S>() {

				@Override
				public Stream<Item<S>> stream() {
					return items.stream();
				}

				@Override
				public void add(Item<S> item) {
					items.add(item);
				}

				@Override
				public boolean remove(Item<S> item) {
					return items.remove(item);
				}
			};
		}
	}
}
