package fr.vergne.condominium.core.repository;

import java.util.Optional;
import java.util.stream.Stream;

public interface RepositoryDiff {

	static <R, K> Stream<Diff<R, K>> diff(Repository<R, K> repo1, Repository<R, K> repo2) {
		Stream<Diff<R, K>> replacementsAndRemovals = repo1.stream()//
				.map(entry1 -> {
					K key1 = entry1.getKey();
					R resource1 = entry1.getValue();

					boolean repo2HasK1 = repo2.has(key1);
					if (repo2HasK1) {
						R resource2 = repo2.mustGet(key1);
						if (!resource1.equals(resource2)) {
							return Diff.replaceResource(key1, resource1, resource2);
						}
					}

					Optional<K> repo2KeyForR1 = repo2.key(resource1);
					if (repo2KeyForR1.isPresent()) {
						K key2 = repo2KeyForR1.get();
						if (!key1.equals(key2)) {
							return Diff.replaceKey(key1, key2, resource1);
						}
					}

					if (!repo2HasK1 && repo2KeyForR1.isEmpty()) {
						return Diff.remove(key1, resource1);
					}

					return null;
				}).filter(x -> x != null);

		Stream<Diff<R, K>> additions = repo2.stream()//
				.map(entry2 -> {
					K key2 = entry2.getKey();
					R resource2 = entry2.getValue();

					if (!repo1.has(key2) && repo1.key(resource2).isEmpty()) {
						return Diff.add(key2, resource2);
					}

					return null;
				}).filter(x -> x != null);

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
