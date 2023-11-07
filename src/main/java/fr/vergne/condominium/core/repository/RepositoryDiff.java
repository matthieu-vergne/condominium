package fr.vergne.condominium.core.repository;

import java.util.stream.Stream;

public interface RepositoryDiff {

	static <R, K> Stream<Diff<R, K>> diff(Repository<R, K> repo1, Repository<R, K> repo2) {
//		Iterator<Diff<R, K>> iterator = new Iterator<Diff<R, K>>() {
//
//			@Override
//			public boolean hasNext() {
//				// TODO Auto-generated method stub
//				return false;
//			}
//
//			@Override
//			public Diff<R, K> next() {
//				// TODO Auto-generated method stub
//				return null;
//			}
//		};
//		Spliterator<Diff<R, K>> spliterator = Spliterators.spliterator(iterator, 0,
//				Spliterator.DISTINCT & Spliterator.IMMUTABLE & Spliterator.NONNULL);
//		StreamSupport.stream(spliterator, false);
		Stream<Diff<R, K>> removals = repo1.stream()//
				.filter(resource -> !repo2.has(repo1.key(resource).get()) && repo2.key(resource).isEmpty())
				.map(resource -> Diff.remove(repo1.key(resource).get(), resource));
		Stream<Diff<R, K>> additions = repo2.stream()//
				.filter(resource -> !repo1.has(repo2.key(resource).get()) && repo1.key(resource).isEmpty())
				.map(resource -> Diff.add(repo2.key(resource).get(), resource));
		Stream<Diff<R, K>> keyReplacements = repo1.stream()//
				.filter(resource -> repo2.key(resource).isPresent()
						&& !repo2.key(resource).get().equals(repo1.key(resource).get()))
				.map(resource -> Diff.replaceKey(repo1.key(resource).get(), repo2.key(resource).get(), resource));
		Stream<Diff<R, K>> resourceReplacements = repo1.stream()//
				.filter(resource -> repo2.has(repo1.key(resource).get())
						&& !repo2.get(repo1.key(resource).get()).equals(resource))
				.map(resource -> Diff.replaceResource(repo1.key(resource).get(), resource,
						repo2.get(repo1.key(resource).get())));
		// TODO Refactor
		return Stream.concat(removals, Stream.concat(additions, Stream.concat(keyReplacements, resourceReplacements)));
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
