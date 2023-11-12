package fr.vergne.condominium.core.repository;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

public interface RepositoryDiff<R, K> {

	Stream<ResourceDiff<R, K>> stream();

	default void collect(//
			Consumer<ResourceDiff<R, K>> additionListener, //
			Consumer<ResourceDiff<R, K>> removalListener, //
			Consumer<ResourceDiff<R, K>> replacementListener, //
			Consumer<ResourceDiff<R, K>> reidentifyListener//
	) {
		this.stream().forEach(resDiff -> {
			if (resDiff.isAddition()) {
				additionListener.accept(resDiff);
			} else if (resDiff.isRemoval()) {
				removalListener.accept(resDiff);
			} else if (resDiff.isResourceReplacement()) {
				replacementListener.accept(resDiff);
			} else {
				reidentifyListener.accept(resDiff);
			}
		});
	}

	default void apply(Repository<R, K> targetRepository) {
		// TODO Apply diff to repo
	}

	static <R, K> RepositoryDiff<R, K> diff(Repository<R, K> repo1, Repository<R, K> repo2) {
		return new RepositoryDiff<R, K>() {
			@Override
			public Stream<ResourceDiff<R, K>> stream() {
				Stream<ResourceDiff<R, K>> replacementsAndRemovals = repo1.stream()//
						.map(entry1 -> {
							K key1 = entry1.getKey();
							R resource1 = entry1.getValue();

							boolean repo2HasK1 = repo2.has(key1);
							if (repo2HasK1) {
								R resource2 = repo2.mustGet(key1);
								if (!resource1.equals(resource2)) {
									return ResourceDiff.replaceResource(key1, resource1, resource2);
								}
							}

							Optional<K> repo2KeyForR1 = repo2.key(resource1);
							if (repo2KeyForR1.isPresent()) {
								K key2 = repo2KeyForR1.get();
								if (!key1.equals(key2)) {
									return ResourceDiff.replaceKey(key1, key2, resource1);
								}
							}

							if (!repo2HasK1 && repo2KeyForR1.isEmpty()) {
								return ResourceDiff.remove(key1, resource1);
							}

							return null;
						}).filter(x -> x != null);

				Stream<ResourceDiff<R, K>> additions = repo2.stream()//
						.map(entry2 -> {
							K key2 = entry2.getKey();
							R resource2 = entry2.getValue();

							if (!repo1.has(key2) && repo1.key(resource2).isEmpty()) {
								return ResourceDiff.add(key2, resource2);
							}

							return null;
						}).filter(x -> x != null);

				return Stream.concat(replacementsAndRemovals, additions);
			}
		};
	}

	public record ResourceDiff<R, K>(K oldKey, R oldResource, K newKey, R newResource) {
		public static <R, K> ResourceDiff<R, K> add(K key, R resource) {
			return new ResourceDiff<>(null, null, key, resource);
		}

		public static <R, K> ResourceDiff<R, K> remove(K key, R resource) {
			return new ResourceDiff<>(key, resource, null, null);
		}

		public static <R, K> ResourceDiff<R, K> replaceResource(K key, R oldResource, R newResource) {
			return new ResourceDiff<>(key, oldResource, key, newResource);
		}

		public static <R, K> ResourceDiff<R, K> replaceKey(K oldKey, K newKey, R resource) {
			return new ResourceDiff<>(oldKey, resource, newKey, resource);
		}

		public boolean isAddition() {
			return oldKey == null;
		}

		public boolean isRemoval() {
			return newKey == null;
		}

		public boolean isResourceReplacement() {
			return oldKey == newKey;
		}

		public boolean isKeyReplacement() {
			return oldResource == newResource;
		}
	}
}
