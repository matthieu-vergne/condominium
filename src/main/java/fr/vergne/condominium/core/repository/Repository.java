package fr.vergne.condominium.core.repository;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public interface Repository<K, R> {
	K add(R resource) throws AlredyExistingResourceKeyException;

	Optional<K> key(R resource);

	boolean has(K key);

	Optional<R> get(K key);

	default R mustGet(K key) throws UnknownResourceKeyException {
		return get(key).orElseThrow(() -> new UnknownResourceKeyException(key));
	}

	Optional<R> remove(K key);

	default R mustRemove(K key) throws UnknownResourceKeyException {
		return remove(key).orElseThrow(() -> new UnknownResourceKeyException(key));
	}

	Stream<Map.Entry<K, R>> stream();

	default Stream<K> streamKeys() {
		return stream().map(Map.Entry::getKey);
	}

	default Stream<R> streamResources() {
		return stream().map(Map.Entry::getValue);
	}

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
