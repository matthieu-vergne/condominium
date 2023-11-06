package fr.vergne.condominium.core.repository;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public interface Repository<R, K> {
	K add(R resource) throws AlredyExistingResourceKeyException;

	Optional<K> key(R resource);

	R get(K key) throws UnknownResourceKeyException;

	R remove(K key) throws UnknownResourceKeyException;

	Stream<R> stream();

	@SuppressWarnings("serial")
	public static class UnknownResourceKeyException extends RuntimeException {

		public UnknownResourceKeyException(Object key) {
			super(Objects.toString(key));
		}
	}

	@SuppressWarnings("serial")
	public static class AlredyExistingResourceKeyException extends RuntimeException {

		public AlredyExistingResourceKeyException(Object key) {
			super(Objects.toString(key));
		}
	}
}
