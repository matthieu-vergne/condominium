package fr.vergne.condominium.core.repository;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

public interface Repository<R, K> {
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

	interface Decorator<R, K> {
		Decorator<R, K> preAdd(Consumer<R> decoration);

		Decorator<R, K> postAdd(BiConsumer<K, R> decoration);

		Decorator<R, K> preDel(Consumer<K> decoration);

		Decorator<R, K> postDel(BiConsumer<K, R> decoration);

		Repository<R, K> build();
	}

	default Decorator<R, K> decorate() {
		Repository<R, K> delegate = this;
		var decorations = new Object() {
			Optional<Consumer<R>> preAdd = Optional.empty();
			Optional<BiConsumer<K, R>> postAdd = Optional.empty();
			Optional<Consumer<K>> preDel = Optional.empty();
			Optional<BiConsumer<K, R>> postDel = Optional.empty();
		};
		return new Decorator<R, K>() {

			@Override
			public Decorator<R, K> preAdd(Consumer<R> decoration) {
				if (decorations.preAdd.isPresent()) {
					throw new IllegalStateException("Pre-add decoration already associated");
				}
				decorations.preAdd = Optional.of(decoration);
				return this;
			}

			@Override
			public Decorator<R, K> postAdd(BiConsumer<K, R> decoration) {
				if (decorations.postAdd.isPresent()) {
					throw new IllegalStateException("Post-add decoration already associated");
				}
				decorations.postAdd = Optional.of(decoration);
				return this;
			}

			@Override
			public Decorator<R, K> preDel(Consumer<K> decoration) {
				if (decorations.preDel.isPresent()) {
					throw new IllegalStateException("Pre-del decoration already associated");
				}
				decorations.preDel = Optional.of(decoration);
				return this;
			}

			@Override
			public Decorator<R, K> postDel(BiConsumer<K, R> decoration) {
				if (decorations.postDel.isPresent()) {
					throw new IllegalStateException("Post-del decoration already associated");
				}
				decorations.postDel = Optional.of(decoration);
				return this;
			}

			@Override
			public Repository<R, K> build() {
				return new Repository<R, K>() {

					@Override
					public K add(R resource) throws AlredyExistingResourceKeyException {
						decorations.preAdd.ifPresent(consumer -> consumer.accept(resource));
						K key = delegate.add(resource);
						decorations.postAdd.ifPresent(consumer -> consumer.accept(key, resource));
						return key;
					}

					@Override
					public Optional<K> key(R resource) {
						return delegate.key(resource);
					}

					@Override
					public boolean has(K key) {
						return delegate.has(key);
					}

					@Override
					public Optional<R> get(K key) {
						return delegate.get(key);
					}

					@Override
					public Optional<R> remove(K key) {
						decorations.preDel.ifPresent(consumer -> consumer.accept(key));
						Optional<R> resource = delegate.remove(key);
						resource.ifPresent(res -> decorations.postDel.ifPresent(consumer -> consumer.accept(key, res)));
						return resource;
					}

					@Override
					public Stream<Entry<K, R>> stream() {
						return delegate.stream();
					}
				};
			}
		};
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
