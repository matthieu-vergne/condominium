package fr.vergne.condominium.core.repository;

import java.util.Optional;
import java.util.stream.Stream;

public interface RepositoryDiff {

	static <R, K> Stream<Diff<R, K>> diff(Repository<R, K> repo1, Repository<R, K> repo2) {
		Stream<Diff<R, K>> replacementsAndRemovals = repo1.streamResources()//
				.map(resource -> {
					K key1 = repo1.key(resource).get();
					if (repo2.has(key1) && !repo2.mustGet(key1).equals(resource)) {
						return Diff.replaceResource(key1, resource, repo2.mustGet(key1));
					} else {
						Optional<K> key2 = repo2.key(resource);
						if (!repo2.has(key1) && key2.isEmpty()) {
							return Diff.remove(key1, resource);
						} else if (key2.isPresent() && !key2.get().equals(key1)) {
							return Diff.replaceKey(key1, key2.get(), resource);
						} else {
							return null;
						}
					}
				}).filter(x -> x != null);

		Stream<Diff<R, K>> additions = repo2.streamResources()//
				.filter(resource -> !repo1.has(repo2.key(resource).get()) && repo1.key(resource).isEmpty())
				.map(resource -> Diff.add(repo2.key(resource).get(), resource));

		return Stream.concat(replacementsAndRemovals, additions);
	}

	public record Diff<R, K>(K oldKey, R oldResource, K newKey, R newResource) {
		public static <R, K> Diff<R, K> add(K key, R resource) {
			return new Diff<>(null, null, key, resource);
		}

		public static <R, K> Diff<R, K> remove(K key, R resource) {
			return new Diff<>(key, resource, null, null);
		}

		public static <R, K> Diff<R, K> replaceResource(K key, R oldResource, R newResource) {
			return new Diff<>(key, oldResource, key, newResource);
		}

		public static <R, K> Diff<R, K> replaceKey(K oldKey, K newKey, R resource) {
			return new Diff<>(oldKey, resource, newKey, resource);
		}
	}

}
