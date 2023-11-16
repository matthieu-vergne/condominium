package fr.vergne.condominium.core.repository;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

public interface RepositoryDiff<R, K> {

	Stream<ResourceDiff<R, K>> stream();

	default RepositoryDiff<R, K> snapshot() {
		List<ResourceDiff<R, K>> diffs = stream().toList();
		return new RepositoryDiff<R, K>() {

			@Override
			public Stream<ResourceDiff<R, K>> stream() {
				return diffs.stream();
			}

			@Override
			public RepositoryDiff<R, K> snapshot() {
				// Don't recreate a snapshot if we already have one
				return this;
			}
		};
	}

	default void apply(Repository<R, K> repository) {
		stream().forEach(diff -> diff.applyTo(repository));
	}

	static <R, K> RepositoryDiff<R, K> of(Repository<R, K> repo1, Repository<R, K> repo2) {
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

	public class ResourceDiff<R, K> {
		private final Action action;
		private final Values<R, K> values;

		private ResourceDiff(Action action, Values<R, K> values) {
			this.action = requireNonNull(action);
			this.values = requireNonNull(values);
		}

		public boolean is(Action action) {
			return this.action == action;
		}

		public Values<R, K> values() {
			return values;
		}

		public void applyTo(Repository<R, K> repository) {
			action.apply(values, repository);
		}

		@Override
		public String toString() {
			return action.format(values);
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this) {
				return true;
			} else if (obj instanceof @SuppressWarnings("rawtypes") ResourceDiff that) {
				return this.values().equals(that.values);
			} else {
				return false;
			}
		}

		@Override
		public int hashCode() {
			return values().hashCode();
		}

		public record Values<R, K>(K oldKey, R oldResource, K newKey, R newResource) {
		}

		public enum Action {
			ADDITION(new Action.Applier() {

				@Override
				public <R, K> void apply(Values<R, K> values, Repository<R, K> repository) {
					K actualKey = repository.add(values.newResource);
					if (!actualKey.equals(values.newKey)) {
						throw new InvalidApplyException(repository, "adding " + values.newResource + " create "
								+ actualKey + " instead of " + values.newKey);
					}
				}
			}, values -> "Add[" + values.newKey + ", " + values.newResource + "]"), //
			REMOVAL(new Action.Applier() {

				@Override
				public <R, K> void apply(Values<R, K> values, Repository<R, K> repository) {
					R actualResource = repository.remove(values.oldKey).orElse(null);
					if (!actualResource.equals(values.oldResource)) {
						throw new InvalidApplyException(repository, "removing " + values.oldKey + " returns "
								+ actualResource + " instead of " + values.oldResource);
					}
				}
			}, values -> "Remove[" + values.oldKey + ", " + values.oldResource + "]"), //
			RESOURCE_REPLACEMENT(new Action.Applier() {

				@Override
				public <R, K> void apply(Values<R, K> values, Repository<R, K> repository) {
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
				public <R, K> void apply(Values<R, K> values, Repository<R, K> repository) {
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
				<R, K> void apply(Values<R, K> values, Repository<R, K> repository);
			}

			private final Applier applier;
			private final Function<Values<?, ?>, String> stringFormatter;

			private Action(Applier applier, Function<Values<?, ?>, String> stringFormatter) {
				this.applier = applier;
				this.stringFormatter = stringFormatter;
			}

			public <R, K> void apply(Values<R, K> values, Repository<R, K> repository) {
				applier.apply(values, repository);
			}

			public String format(Values<?, ?> values) {
				return stringFormatter.apply(values);
			}
		}

		public static <R, K> ResourceDiff<R, K> add(K newKey, R newResource) {
			return new ResourceDiff<>(Action.ADDITION, new Values<>(null, null, newKey, newResource));
		}

		public static <R, K> ResourceDiff<R, K> remove(K oldKey, R oldResource) {
			return new ResourceDiff<>(Action.REMOVAL, new Values<>(oldKey, oldResource, null, null));
		}

		public static <R, K> ResourceDiff<R, K> replaceResource(K key, R oldResource, R newResource) {
			return new ResourceDiff<>(Action.RESOURCE_REPLACEMENT, new Values<>(key, oldResource, key, newResource));
		}

		public static <R, K> ResourceDiff<R, K> replaceKey(K oldKey, K newKey, R resource) {
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
