package fr.vergne.condominium.core.repository;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

public interface RepositoryDiff<K, R> {

	Stream<ResourceDiff<K, R>> stream();

	default RepositoryDiff<K, R> snapshot() {
		List<ResourceDiff<K, R>> diffs = stream().toList();
		return new RepositoryDiff<>() {

			@Override
			public Stream<ResourceDiff<K, R>> stream() {
				return diffs.stream();
			}

			@Override
			public RepositoryDiff<K, R> snapshot() {
				// Don't recreate a snapshot if we already have one
				return this;
			}
		};
	}

	default void apply(Repository<K, R> repository) {
		stream().forEach(diff -> diff.applyTo(repository));
	}

	static <K, R> RepositoryDiff<K, R> of(Repository<K, R> repo1, Repository<K, R> repo2) {
		return new RepositoryDiff<K, R>() {
			@Override
			public Stream<ResourceDiff<K, R>> stream() {
				Stream<ResourceDiff<K, R>> replacementsAndRemovals = repo1.stream()//
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

				Stream<ResourceDiff<K, R>> additions = repo2.stream()//
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

	public class ResourceDiff<K, R> {
		private final Action action;
		private final Values<K, R> values;

		private ResourceDiff(Action action, Values<K, R> values) {
			this.action = requireNonNull(action);
			this.values = requireNonNull(values);
		}

		public boolean is(Action action) {
			return this.action == action;
		}

		public Values<K, R> values() {
			return values;
		}

		public void applyTo(Repository<K, R> repository) {
			action.apply(values, repository);
		}

		@Override
		public String toString() {
			return action.format(values);
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof @SuppressWarnings("rawtypes") ResourceDiff that //
					&& Objects.equals(this.values(), that.values);
		}

		@Override
		public int hashCode() {
			return values().hashCode();
		}

		public record Values<K, R>(K oldKey, R oldResource, K newKey, R newResource) {
		}

		public enum Action {
			ADDITION(new Action.Applier() {

				@Override
				public <K, R> void apply(Values<K, R> values, Repository<K, R> repository) {
					K actualKey = repository.add(values.newResource);
					if (!actualKey.equals(values.newKey)) {
						throw new InvalidApplyException(repository, "adding " + values.newResource + " create "
								+ actualKey + " instead of " + values.newKey);
					}
				}
			}, values -> "Add[" + values.newKey + ", " + values.newResource + "]"), //
			REMOVAL(new Action.Applier() {

				@Override
				public <K, R> void apply(Values<K, R> values, Repository<K, R> repository) {
					R actualResource = repository.remove(values.oldKey).orElse(null);
					if (!actualResource.equals(values.oldResource)) {
						throw new InvalidApplyException(repository, "removing " + values.oldKey + " returns "
								+ actualResource + " instead of " + values.oldResource);
					}
				}
			}, values -> "Remove[" + values.oldKey + ", " + values.oldResource + "]"), //
			RESOURCE_REPLACEMENT(new Action.Applier() {

				@Override
				public <K, R> void apply(Values<K, R> values, Repository<K, R> repository) {
					R removedResource = repository.remove(values.oldKey).orElse(null);
					if (!removedResource.equals(values.oldResource)) {
						throw new InvalidApplyException(repository, "replacing " + values.oldKey + " returns "
								+ removedResource + " instead of " + values.oldResource);
					}

					K newKey = repository.add(values.newResource);
					if (!newKey.equals(values.oldKey)) {
						throw new InvalidApplyException(repository, "replacing " + values.oldKey + " with "
								+ values.newResource + " reidentify as " + newKey);
					}
				}
			}, values -> "Replace[" + values.oldKey + ", " + values.oldResource + "]With[" + values.newResource + "]"), //
			KEY_REPLACEMENT(new Action.Applier() {

				@Override
				public <K, R> void apply(Values<K, R> values, Repository<K, R> repository) {
					R removedResource = repository.remove(values.oldKey).orElse(null);
					if (!removedResource.equals(values.oldResource)) {
						throw new InvalidApplyException(repository, "reidentifying " + values.oldKey + " returns "
								+ removedResource + " instead of " + values.oldResource);
					}

					K createdKey = repository.add(values.oldResource);
					if (!createdKey.equals(values.newKey)) {
						throw new InvalidApplyException(repository, "reidentifying " + values.oldResource + " returns "
								+ createdKey + " instead of " + values.newKey);
					}
				}
			}, values -> "Reidentify[" + values.oldKey + ", " + values.oldResource + "]With[" + values.newKey + "]");

			public static interface Applier {
				<K, R> void apply(Values<K, R> values, Repository<K, R> repository);
			}

			private final Applier applier;
			private final Function<Values<?, ?>, String> stringFormatter;

			private Action(Applier applier, Function<Values<?, ?>, String> stringFormatter) {
				this.applier = applier;
				this.stringFormatter = stringFormatter;
			}

			public <K, R> void apply(Values<K, R> values, Repository<K, R> repository) {
				applier.apply(values, repository);
			}

			public String format(Values<?, ?> values) {
				return stringFormatter.apply(values);
			}
		}

		public static <K, R> ResourceDiff<K, R> add(K newKey, R newResource) {
			return new ResourceDiff<>(Action.ADDITION, new Values<>(null, null, newKey, newResource));
		}

		public static <K, R> ResourceDiff<K, R> remove(K oldKey, R oldResource) {
			return new ResourceDiff<>(Action.REMOVAL, new Values<>(oldKey, oldResource, null, null));
		}

		public static <K, R> ResourceDiff<K, R> replaceResource(K key, R oldResource, R newResource) {
			return new ResourceDiff<>(Action.RESOURCE_REPLACEMENT, new Values<>(key, oldResource, key, newResource));
		}

		public static <K, R> ResourceDiff<K, R> replaceKey(K oldKey, K newKey, R resource) {
			return new ResourceDiff<>(Action.KEY_REPLACEMENT, new Values<>(oldKey, resource, newKey, resource));
		}
	}

	@SuppressWarnings("serial")
	public static class InvalidApplyException extends IllegalArgumentException {
		public InvalidApplyException(Repository<?, ?> targetRepository, String reason) {
			super("Incompatible repository " + targetRepository + ": " + reason);
		}
	}
}
