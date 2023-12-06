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

	/**
	 * A {@link Session} provides a scope to control the update of a
	 * {@link Repository} through {@link #commit()} and {@link #rollback()}.
	 * 
	 * <h1>What is a {@link Repository} update?</h1>
	 * 
	 * A {@link Repository} provides several methods to change its state, like
	 * {@link Repository#add(Object)} and {@link Repository#remove(Object)}. Calling
	 * these methods impacts immediately the state of the {@link Repository}.
	 * However, when the resource is mutable, it might see its own state change.
	 * This kind of change is done out of the control of the {@link Repository},
	 * since it applies by interacting with the resource itself.
	 * 
	 * <h1>What is the problem?</h1>
	 * 
	 * When we interact with the {@link Repository}, the updates are immediate, so
	 * controlling these updates can be done by controlling the relevant calls to
	 * the methods of the {@link Repository}. However, changes done to the resource
	 * itself might reflect on the repository independently of the control of the
	 * user of the resource.
	 * <p>
	 * For example, an in-memory {@link Repository} might reflect the update
	 * immediately because the instance of the resource itself is the one stored. At
	 * the opposite, a file-based {@link Repository} might not reflect it because
	 * changing the instance of the resource does not automatically rewrite the
	 * corresponding file. These are two examples where the implementation of the
	 * {@link Repository} is in control of whether a resource update reflects
	 * immediately or not, thus out of the user's control.
	 * 
	 * <h1>Controlling updates through a {@link Session}</h1>
	 * 
	 * When the {@link Repository} is used through a {@link Session}, specific
	 * methods allow to tell whether the changes done should be applied
	 * ({@link #commit()}) or not ({@link #rollback()}). More precisely, the
	 * {@link Session} itself comes with its own representation of the
	 * {@link Repository} ({@link Session#repository()}). This representation
	 * includes all the changes done so far. These changes should however only be
	 * permanent after calling {@link #commit()}, and be discarded upon calling
	 * {@link #rollback()}. This applies for both {@link Repository} methods and
	 * resource state.
	 * <p>
	 * More formally, any non-comitted change should be discarded upon calling
	 * {@link #rollback()}, and the {@link Session} {@link Repository} is considered
	 * comitted upon creating the {@link Session}.
	 * <p>
	 * An important aspect is that {@link #commit()} and {@link #rollback()} only
	 * offer a control over the {@link Repository} representation of the
	 * {@link Session}. If a resource is obtained from a {@link Repository} not
	 * provided by {@link Session#repository()} (or from a different
	 * {@link Session}), the {@link Session} offers no guaranteed control over the
	 * resource updates. The resource must be obtained from the {@link Repository}
	 * of the {@link Session} to obtain full control over its updates.
	 * <p>
	 * Moreover, upon {@link #commit()}, the changes should reflect permanently,
	 * thus any other {@link Repository} representation (from a {@link Session} or
	 * not) should be impacted by these changes. In particular, if we create two
	 * {@link Session}s, S1 and S2, and we create the resources X in S1 and Y in S2,
	 * if we {@link #commit()} S1 and {@link #rollback()} S2 (in any order), then X
	 * should also exist in S2. In practice, it is however recommended to use a
	 * single session to avoid conflicts.
	 * 
	 * @param <K> the type of the resource key
	 * @param <R> the type of the resource
	 */
	public interface Session<K, R> {
		Repository<K, R> repository();

		void commit();

		void rollback();
	}

	public interface Sessionable<K, R> extends Repository<K, R> {
		Session<K, R> createSession();
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
