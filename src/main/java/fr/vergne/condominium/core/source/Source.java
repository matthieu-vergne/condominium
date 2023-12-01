package fr.vergne.condominium.core.source;

import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.function.BiFunction;

import fr.vergne.condominium.core.mail.Mail;

public interface Source<T> {

	T resolve();

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
					};
				}
			};
		}
	}

	interface Provider {
		Source.Dated<Mail> sourceMail(Mail mail);
	}
}
