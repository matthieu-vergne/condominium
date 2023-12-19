package fr.vergne.condominium.core.source;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiFunction;

// TODO Test
public interface Source<T> {

	T resolve();

	interface Creator {
		<T> Source<T> create(T t);
	}

	public static <T> Source<T> create(T object) {
		return new Source<T>() {
			@Override
			public T resolve() {
				return object;
			}

			@Override
			public boolean equals(Object obj) {
				return obj instanceof Source<?> that //
						? Objects.equals(this.resolve(), that.resolve())//
						: false;
			}

			@Override
			public int hashCode() {
				return resolve().hashCode();
			}
		};
	}

	interface Dated<T> extends Source<T> {
		ZonedDateTime date();
	}

	default <U, V> Source<V> refine(Refiner<T, U, V> refiner, U id) {
		return refiner.resolve(this, id);
	}

	interface Root<T> extends Source<T> {
	}

	interface Refiner<T, U, V> {
		Source<V> resolve(Source<T> parentSource, U id);

		interface Creator {
			<T1, I, T2> Refiner<T1, I, T2> create(BiFunction<T1, I, T2> idResolver);
		}

		public static <T1, I, T2> Refiner<T1, I, T2> create(BiFunction<T1, I, T2> idResolver) {
			return new Refiner<T1, I, T2>() {

				@Override
				public Source<T2> resolve(Source<T1> parentSource, I id) {
					return new Source<T2>() {
						@Override
						public T2 resolve() {
							return idResolver.apply(parentSource.resolve(), id);
						}

						@Override
						public boolean equals(Object obj) {
							return obj instanceof Source<?> that //
									? Objects.equals(this.resolve(), that.resolve())//
									: false;
						}

						@Override
						public int hashCode() {
							return resolve().hashCode();
						}

						@Override
						public String toString() {
							return "Source[" + resolve().toString() + "]";
						}
					};
				}
			};
		}
	}

	public static interface Track {
		Root root();

		public static interface Refined<U> extends Track {
			U refinedId();
		}

		public interface Root extends Transitive {
			Source<?> source();
		}

		public interface Transition<U> extends Transitive {
			Refiner<?, U, ?> refiner();

			U id();
		}

		public interface Transitive {
			boolean hasTransition();

			Transition<?> transition();
		}

		static Track from(Source<?> source) {
			return new Track() {

				@Override
				public Root root() {
					return new Root() {

						@Override
						public Source<?> source() {
							return source;
						}

						@Override
						public boolean hasTransition() {
							return false;
						}

						@Override
						public Transition<?> transition() {
							throw new NoSuchElementException("No transition");
						}
					};
				}
			};
		}

		// TODO Test, surely won't work with 2 calls or more
		default <T, U, V> Track.Refined<U> then(Refiner<T, U, V> refiner, U id) {
			Track parent = this;
			return new Track.Refined<U>() {

				@Override
				public Root root() {
					return new Root() {

						@Override
						public Source<?> source() {
							return parent.root().source();
						}

						@Override
						public boolean hasTransition() {
							return true;
						}

						@Override
						public Transition<U> transition() {
							return new Transition<U>() {

								@Override
								public Refiner<?, U, ?> refiner() {
									return refiner;
								}

								@Override
								public U id() {
									return id;
								}

								@Override
								public boolean hasTransition() {
									return false;
								}

								@Override
								public Transition<?> transition() {
									throw new NoSuchElementException("No transition");
								}
							};
						}
					};
				}

				@Override
				public U refinedId() {
					return id;
				}
			};
		}
	}

	interface Tracker {
		<T> Source<T> createSource(T t);

		<T, U, V> Refiner<T, U, V> createRefiner(BiFunction<T, U, V> idResolver);

		Track trackOf(Source<?> source);

		public static Tracker create(Source.Creator sourceCreator, Refiner.Creator refinerCreator) {
			return new Source.Tracker() {
				Map<Source<?>, Source.Track> sourceTracks = new HashMap<>();

				@Override
				public <T> Source<T> createSource(T mailRepository) {
					Source<T> repoSource = sourceCreator.create(mailRepository);
					sourceTracks.put(repoSource, Source.Track.from(repoSource));
					return repoSource;
				}

				@Override
				public <T, U, V> Source.Refiner<T, U, V> createRefiner(BiFunction<T, U, V> idResolver) {
					Refiner<T, U, V> parentRefiner = refinerCreator.create(idResolver);
					Source.Refiner<T, U, V> mailRefiner = new Source.Refiner<T, U, V>() {

						@Override
						public Source<V> resolve(Source<T> parentSource, U id) {
							Source<V> resolved = parentRefiner.resolve(parentSource, id);
							sourceTracks.put(resolved, sourceTracks.get(parentSource).then(this, id));
							return resolved;
						}
					};
					return mailRefiner;
				}

				@Override
				public Source.Track trackOf(Source<?> source) {
					return sourceTracks.get(source);
				}
			};
		}
	}
}
