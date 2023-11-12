package fr.vergne.condominium.core.repository;

import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import fr.vergne.condominium.core.repository.RepositoryDiff.ResourceDiff;

class RepositoryDiffTest {

	@Test
	void testDiffActions() {
		assertEquals(new ResourceDiff<>(null, null, 1, "a"), ResourceDiff.add(1, "a"));
		assertEquals(new ResourceDiff<>(1, "a", null, null), ResourceDiff.remove(1, "a"));
		assertEquals(new ResourceDiff<>(1, "a", 2, "a"), ResourceDiff.replaceKey(1, 2, "a"));
		assertEquals(new ResourceDiff<>(1, "a", 1, "b"), ResourceDiff.replaceResource(1, "a", "b"));
	}

	@Test
	void testDiffOnSameRepositoryIsEmpty() {
		// GIVEN
		Repository<String, Integer> repo = createRepository(Map.of(1, "a", 2, "b"));

		// WHEN
		RepositoryDiff<String, Integer> diff = RepositoryDiff.diff(repo, repo);

		// THEN
		assertEquals(emptySet(), diff.stream().collect(toSet()));
	}

	@Test
	void testDiffOnEqualRepositoriesIsEmpty() {
		// GIVEN
		Repository<String, Integer> repo1 = createRepository(Map.of(1, "a", 2, "b"));
		Repository<String, Integer> repo2 = createRepository(Map.of(1, "a", 2, "b"));

		// WHEN
		RepositoryDiff<String, Integer> diff = RepositoryDiff.diff(repo1, repo2);

		// THEN
		assertEquals(emptySet(), diff.stream().collect(toSet()));
	}

	@Test
	void testDiffOnEmptyRepositoriesIsEmpty() {
		// GIVEN
		Repository<String, Integer> repo1 = createRepository(Map.of());
		Repository<String, Integer> repo2 = createRepository(Map.of());

		// WHEN
		RepositoryDiff<String, Integer> diff = RepositoryDiff.diff(repo1, repo2);

		// THEN
		assertEquals(emptySet(), diff.stream().collect(toSet()));
	}

	@Test
	void testDiffOnAdditionReturnsAddition() {
		// GIVEN
		Repository<String, Integer> repo1 = createRepository(Map.of());
		Repository<String, Integer> repo2 = createRepository(Map.of(1, "a"));

		// WHEN
		RepositoryDiff<String, Integer> diff = RepositoryDiff.diff(repo1, repo2);

		// THEN
		assertEquals(Set.of(ResourceDiff.add(1, "a")), diff.stream().collect(toSet()));
	}

	@Test
	void testDiffOnRemovalReturnsRemoval() {
		// GIVEN
		Repository<String, Integer> repo1 = createRepository(Map.of(1, "a"));
		Repository<String, Integer> repo2 = createRepository(Map.of());

		// WHEN
		RepositoryDiff<String, Integer> diff = RepositoryDiff.diff(repo1, repo2);

		// THEN
		assertEquals(Set.of(ResourceDiff.remove(1, "a")), diff.stream().collect(toSet()));
	}

	@Test
	void testDiffOnKeyReplacementReturnsKeyReplacement() {
		// GIVEN
		Repository<String, Integer> repo1 = createRepository(Map.of(1, "a"));
		Repository<String, Integer> repo2 = createRepository(Map.of(2, "a"));

		// WHEN
		RepositoryDiff<String, Integer> diff = RepositoryDiff.diff(repo1, repo2);

		// THEN
		assertEquals(Set.of(ResourceDiff.replaceKey(1, 2, "a")), diff.stream().collect(toSet()));
	}

	@Test
	void testDiffOnResourceReplacementReturnsResourceReplacement() {
		// GIVEN
		Repository<String, Integer> repo1 = createRepository(Map.of(1, "a"));
		Repository<String, Integer> repo2 = createRepository(Map.of(1, "b"));

		// WHEN
		RepositoryDiff<String, Integer> diff = RepositoryDiff.diff(repo1, repo2);

		// THEN
		assertEquals(Set.of(ResourceDiff.replaceResource(1, "a", "b")), diff.stream().collect(toSet()));
	}

	@Test
	void testCombinedCase() {
		// GIVEN
		Repository<String, Integer> repo1 = createRepository(Map.of(1, "removed", 3, "old", 4, "moved"));
		Repository<String, Integer> repo2 = createRepository(Map.of(2, "added", 3, "new", 5, "moved"));

		// WHEN
		RepositoryDiff<String, Integer> diff = RepositoryDiff.diff(repo1, repo2);

		// THEN
		assertEquals(Set.of(//
				ResourceDiff.remove(1, "removed"), //
				ResourceDiff.add(2, "added"), //
				ResourceDiff.replaceResource(3, "old", "new"), //
				ResourceDiff.replaceKey(4, 5, "moved")//
		), diff.stream().collect(toSet()));
	}

	private Repository<String, Integer> createRepository(Map<Integer, String> map1) {
		Map<String, Integer> map2 = map1.entrySet().stream()
				.collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
		Repository<String, Integer> repo1 = new Repository<String, Integer>() {

			@Override
			public Stream<Entry<Integer, String>> stream() {
				return map1.entrySet().stream();
			}

			@Override
			public Optional<String> remove(Integer key) {
				throw new RuntimeException("Not supported");
			}

			@Override
			public Optional<Integer> key(String resource) {
				return Optional.ofNullable(map2.get(resource));
			}

			@Override
			public Optional<String> get(Integer key) {
				return Optional.ofNullable(map1.get(key));
			}

			@Override
			public boolean has(Integer key) {
				return map1.containsKey(key);
			}

			@Override
			public Integer add(String resource) throws AlredyExistingResourceKeyException {
				throw new RuntimeException("Not supported");
			}
		};
		return repo1;
	}

}
