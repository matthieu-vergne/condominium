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

	public interface Session<K, R> {
		Repository<K, R> repository();

		void commit();

		void rollback();
	}

	/**
	 * An {@link Updatable} {@link Repository} provides a way to control the updates
	 * of its resources, instead of letting that to the {@link Repository}
	 * implementation.
	 * <p>
	 * For example, an in-memory {@link Repository} might reflect the update
	 * immediately because the instance of the resource itself is the one stored. At
	 * the opposite, a file-based {@link Repository} might not reflect it because
	 * changing the instance of the resource does not automatically rewrite the
	 * corresponding file. These are two examples where the implementation of the
	 * {@link Repository} is in control of whether a resource update reflects
	 * immediately or not, thus out of the user's control.
	 * <p>
	 * When the {@link Repository} is {@link Updatable}, it explicitly states that
	 * updating the state of the resource <b>does not update the state of the
	 * {@link Repository}</b>, and in order to do so the user must call
	 * {@link #update(Object, Object)} with the resource to update.
	 * <p>
	 * To help the user keep control of its resources, it is thus recommended to
	 * implement a simple {@link Repository} to store immutable resources, and an
	 * {@link Updatable} {@link Repository} to store mutable resources. It is only
	 * stated as a recommendation since it remains perfectly fine to use other
	 * strategies, like implementing a {@link Repository} with the intent of
	 * automatically store the state updates of the resources. It is however
	 * strongly recommended to say it in the documentation.
	 * 
	 * @param <K> the type of the resource key
	 * @param <R> the type of the resource
	 */
	public interface Updatable<K, R> extends Repository<K, R> {
		void update(K key, R resource);
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
