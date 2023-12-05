package fr.vergne.condominium.core.repository;

import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import fr.vergne.condominium.core.repository.RepositoryDiff.ResourceDiff;
import fr.vergne.condominium.core.repository.RepositoryDiff.ResourceDiff.Values;
import fr.vergne.condominium.core.repository.RepositoryDiff.ResourceDiff.Action;

class RepositoryDiffTest {

	static Stream<Arguments> testDiffValue() {
		return Stream.of(//
				arguments(ResourceDiff.add(1, "a"), new Values<>(null, null, 1, "a")), //
				arguments(ResourceDiff.remove(1, "a"), new Values<>(1, "a", null, null)), //
				arguments(ResourceDiff.replaceKey(1, 2, "a"), new Values<>(1, "a", 2, "a")), //
				arguments(ResourceDiff.replaceResource(1, "a", "b"), new Values<>(1, "a", 1, "b"))//
		);
	}

	@ParameterizedTest
	@MethodSource
	void testDiffValue(ResourceDiff<String, Integer> diff, Values<String, Integer> values) {
		assertEquals(values, diff.values());
	}

	static Stream<Arguments> testDiffAction() {
		return Stream.of(//
				arguments(ResourceDiff.add(1, "a"), Action.ADDITION), //
				arguments(ResourceDiff.remove(1, "a"), Action.REMOVAL), //
				arguments(ResourceDiff.replaceKey(1, 2, "a"), Action.KEY_REPLACEMENT), //
				arguments(ResourceDiff.replaceResource(1, "a", "b"), Action.RESOURCE_REPLACEMENT)//
		);
	}

	@ParameterizedTest
	@MethodSource
	void testDiffAction(ResourceDiff<String, Integer> diff, Action action) {
		assertTrue(diff.is(action));
	}

	@Test
	void testDiffOnSameRepositoryIsEmpty() {
		// GIVEN
		Repository<Integer, String> repo = createRepository(Map.of(1, "a", 2, "b"));

		// WHEN
		RepositoryDiff<Integer, String> diff = RepositoryDiff.of(repo, repo);

		// THEN
		assertEquals(emptySet(), diff.stream().collect(toSet()));
	}

	@Test
	void testDiffOnEqualRepositoriesIsEmpty() {
		// GIVEN
		Repository<Integer, String> repo1 = createRepository(Map.of(1, "a", 2, "b"));
		Repository<Integer, String> repo2 = createRepository(Map.of(1, "a", 2, "b"));

		// WHEN
		RepositoryDiff<Integer, String> diff = RepositoryDiff.of(repo1, repo2);

		// THEN
		assertEquals(emptySet(), diff.stream().collect(toSet()));
	}

	@Test
	void testDiffOnEmptyRepositoriesIsEmpty() {
		// GIVEN
		Repository<Integer, String> repo1 = createRepository(Map.of());
		Repository<Integer, String> repo2 = createRepository(Map.of());

		// WHEN
		RepositoryDiff<Integer, String> diff = RepositoryDiff.of(repo1, repo2);

		// THEN
		assertEquals(emptySet(), diff.stream().collect(toSet()));
	}

	@Test
	void testDiffOnAdditionReturnsAddition() {
		// GIVEN
		Repository<Integer, String> repo1 = createRepository(Map.of());
		Repository<Integer, String> repo2 = createRepository(Map.of(1, "a"));

		// WHEN
		RepositoryDiff<Integer, String> diff = RepositoryDiff.of(repo1, repo2);

		// THEN
		assertEquals(Set.of(ResourceDiff.add(1, "a")), diff.stream().collect(toSet()));
	}

	@Test
	void testDiffOnRemovalReturnsRemoval() {
		// GIVEN
		Repository<Integer, String> repo1 = createRepository(Map.of(1, "a"));
		Repository<Integer, String> repo2 = createRepository(Map.of());

		// WHEN
		RepositoryDiff<Integer, String> diff = RepositoryDiff.of(repo1, repo2);

		// THEN
		assertEquals(Set.of(ResourceDiff.remove(1, "a")), diff.stream().collect(toSet()));
	}

	@Test
	void testDiffOnKeyReplacementReturnsKeyReplacement() {
		// GIVEN
		Repository<Integer, String> repo1 = createRepository(Map.of(1, "a"));
		Repository<Integer, String> repo2 = createRepository(Map.of(2, "a"));

		// WHEN
		RepositoryDiff<Integer, String> diff = RepositoryDiff.of(repo1, repo2);

		// THEN
		assertEquals(Set.of(ResourceDiff.replaceKey(1, 2, "a")), diff.stream().collect(toSet()));
	}

	@Test
	void testDiffOnResourceReplacementReturnsResourceReplacement() {
		// GIVEN
		Repository<Integer, String> repo1 = createRepository(Map.of(1, "a"));
		Repository<Integer, String> repo2 = createRepository(Map.of(1, "b"));

		// WHEN
		RepositoryDiff<Integer, String> diff = RepositoryDiff.of(repo1, repo2);

		// THEN
		assertEquals(Set.of(ResourceDiff.replaceResource(1, "a", "b")), diff.stream().collect(toSet()));
	}

	@Test
	void testDiffCombinedCase() {
		// GIVEN
		Repository<Integer, String> repo1 = createRepository(Map.of(1, "removed", 3, "old", 4, "moved"));
		Repository<Integer, String> repo2 = createRepository(Map.of(2, "added", 3, "new", 5, "moved"));

		// WHEN
		RepositoryDiff<Integer, String> diff = RepositoryDiff.of(repo1, repo2);

		// THEN
		assertEquals(Set.of(//
				ResourceDiff.remove(1, "removed"), //
				ResourceDiff.add(2, "added"), //
				ResourceDiff.replaceResource(3, "old", "new"), //
				ResourceDiff.replaceKey(4, 5, "moved")//
		), diff.stream().collect(toSet()));
	}

	@Test
	void testApplyOnAddition() {
		// GIVEN
		Repository<Integer, String> repo1 = createRepository(new HashMap<>(), str -> 1);
		Repository<Integer, String> repo2 = createRepository(Map.of(1, "a"));
		RepositoryDiff<Integer, String> diff = RepositoryDiff.of(repo1, repo2).snapshot();

		// WHEN
		diff.apply(repo1);

		// THEN
		assertEquals(repo2.stream().collect(toSet()), repo1.stream().collect(toSet()));
	}

	@Test
	void testApplyOnRemoval() {
		// GIVEN
		Repository<Integer, String> repo1 = createRepository(new HashMap<>(Map.of(1, "a")));
		Repository<Integer, String> repo2 = createRepository(Map.of());
		RepositoryDiff<Integer, String> diff = RepositoryDiff.of(repo1, repo2).snapshot();

		// WHEN
		diff.apply(repo1);

		// THEN
		assertEquals(repo2.stream().collect(toSet()), repo1.stream().collect(toSet()));
	}

	@Test
	void testApplyOnKeyReplacement() {
		// GIVEN
		Repository<Integer, String> repo1 = createRepository(new HashMap<>(Map.of(1, "a")), str -> 2);
		Repository<Integer, String> repo2 = createRepository(Map.of(2, "a"));
		RepositoryDiff<Integer, String> diff = RepositoryDiff.of(repo1, repo2).snapshot();

		// WHEN
		diff.apply(repo1);

		// THEN
		assertEquals(repo2.stream().collect(toSet()), repo1.stream().collect(toSet()));
	}

	@Test
	void testApplyOnResourceReplacement() {
		// GIVEN
		Repository<Integer, String> repo1 = createRepository(new HashMap<>(Map.of(1, "a")), str -> 1);
		Repository<Integer, String> repo2 = createRepository(Map.of(1, "b"));
		RepositoryDiff<Integer, String> diff = RepositoryDiff.of(repo1, repo2).snapshot();

		// WHEN
		diff.apply(repo1);

		// THEN
		assertEquals(repo2.stream().collect(toSet()), repo1.stream().collect(toSet()));
	}

	@Test
	void testApplyCombinedCase() {
		// GIVEN
		Repository<Integer, String> repo1 = createRepository(//
				new HashMap<>(Map.of(1, "removed", 3, "old", 4, "moved")), //
				str -> {
					return switch (str) {
					case "added" -> 2;
					case "new" -> 3;
					case "moved" -> 5;
					default -> 0;
					};
				}//
		);
		Repository<Integer, String> repo2 = createRepository(Map.of(2, "added", 3, "new", 5, "moved"));
		RepositoryDiff<Integer, String> diff = RepositoryDiff.of(repo1, repo2).snapshot();

		// WHEN
		diff.apply(repo1);

		// THEN
		assertEquals(repo2.stream().collect(toSet()), repo1.stream().collect(toSet()));
	}

	private Repository<Integer, String> createRepository(Map<Integer, String> map) {
		Function<String, Integer> identifier = str -> {
			throw new RuntimeException("Not supported");
		};
		return createRepository(map, identifier);
	}

	private Repository<Integer, String> createRepository(Map<Integer, String> map,
			Function<String, Integer> identifier) {
		return new Repository<Integer, String>() {

			@Override
			public Stream<Entry<Integer, String>> stream() {
				return map.entrySet().stream();
			}

			@Override
			public Optional<String> remove(Integer key) {
				return Optional.ofNullable(map.remove(key));
			}

			@Override
			public Optional<Integer> key(String resource) {
				return map.entrySet().stream()//
						.filter(entry -> entry.getValue().equals(resource))//
						.map(Map.Entry::getKey)//
						.findFirst();
			}

			@Override
			public Optional<String> get(Integer key) {
				return Optional.ofNullable(map.get(key));
			}

			@Override
			public boolean has(Integer key) {
				return map.containsKey(key);
			}

			@Override
			public Integer add(String resource) throws AlredyExistingResourceKeyException {
				Integer key = identifier.apply(resource);
				map.put(key, resource);
				return key;
			}
		};
	}

}
