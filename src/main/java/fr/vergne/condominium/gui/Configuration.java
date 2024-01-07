package fr.vergne.condominium.gui;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import fr.vergne.condominium.core.util.Persister;

public interface Configuration {
	Path mailsRepositoryPath();

	Path questionsRepositoryPath();

	Path issuesRepositoryPath();

	// TODO Make private
	public static record Impl(Path mailsRepositoryPath, Path issuesRepositoryPath, Path questionsRepositoryPath)
			implements Configuration {

		public Impl {
			requireNonNull(mailsRepositoryPath, "No path defined for mails repository");
			requireNonNull(issuesRepositoryPath, "No path defined for issues repository");
			requireNonNull(questionsRepositoryPath, "No path defined for questions repository");
		}
	}

	public static class Builder implements Configuration {
		private Path mailsRepositoryPath;
		private Path issuesRepositoryPath;
		private Path questionsRepositoryPath;

		public static Builder fromConfiguration(Configuration conf) {
			Builder builder = new Builder();
			builder.setMailsRepositoryPath(conf.mailsRepositoryPath());
			builder.setIssuesRepositoryPath(conf.issuesRepositoryPath());
			builder.setQuestionsRepositoryPath(conf.questionsRepositoryPath());
			return builder;
		}

		public void setMailsRepositoryPath(Path path) {
			this.mailsRepositoryPath = path;
		}

		@Override
		public Path mailsRepositoryPath() {
			return mailsRepositoryPath;
		}

		public void setIssuesRepositoryPath(Path path) {
			this.issuesRepositoryPath = path;
		}

		@Override
		public Path issuesRepositoryPath() {
			return issuesRepositoryPath;
		}

		public void setQuestionsRepositoryPath(Path path) {
			this.questionsRepositoryPath = path;
		}

		@Override
		public Path questionsRepositoryPath() {
			return questionsRepositoryPath;
		}

		public Configuration build() {
			return new Impl(mailsRepositoryPath, issuesRepositoryPath, questionsRepositoryPath);
		}
	}

	// TODO Make private
	public static enum Key {
		REPOSITORY_PATH_MAILS, //
		REPOSITORY_PATH_ISSUES, //
		REPOSITORY_PATH_QUESTIONS, //
	}

	@SuppressWarnings("serial")
	public static class MissingKeyException extends RuntimeException {
		public MissingKeyException(Key key) {
			super("Missing " + key.name());
		}
	}

	// TODO Make private
	public static interface KeySaver {
		<T> void save(Properties properties, Key key, Function<? super T, ? extends String> transformer,
				Supplier<? extends T> store);
	}

	// TODO Make private
	public static interface KeyLoader {
		<T> void load(Properties properties, Key key, Function<? super String, ? extends T> transformer,
				Consumer<? super T> store);
	}

	public static Persister<Configuration> fromPropertiesFile(Path confPath) {
		KeySaver keySaver = Configuration::saveOrFail;
		KeyLoader keyLoader = Configuration::loadOrFail;// Fail if a field is missing
		return createPersister(confPath, keySaver, keyLoader, Builder::build);
	}

	public static Persister<Builder> fromIncompletePropertiesFile(Path confPath) {
		KeySaver keySaver = Configuration::saveOrFail;
		KeyLoader keyLoader = Configuration::loadIncomplete;// Keep missing field null
		// TODO Avoid null values
		return createPersister(confPath, keySaver, keyLoader, builder -> builder);
	}

	static <C extends Configuration> Persister<C> createPersister(Path confPath, KeySaver keySaver, KeyLoader keyLoader,
			Function<Builder, C> finalizer) {
		return new Persister<C>() {

			@Override
			public boolean hasSave() {
				return Files.exists(confPath);
			}

			@Override
			public void save(Configuration configuration) {
				Properties properties = new Properties();
				keySaver.save(properties, Key.REPOSITORY_PATH_MAILS, Path::toString,
						configuration::mailsRepositoryPath);
				keySaver.save(properties, Key.REPOSITORY_PATH_ISSUES, Path::toString,
						configuration::issuesRepositoryPath);
				keySaver.save(properties, Key.REPOSITORY_PATH_QUESTIONS, Path::toString,
						configuration::questionsRepositoryPath);
				try {
					properties.store(Files.newBufferedWriter(confPath), null);
				} catch (IOException cause) {
					throw new RuntimeException("Cannot write: " + confPath, cause);
				}
			}

			@Override
			public C load() {
				Properties properties = new Properties();
				try {
					properties.load(Files.newBufferedReader(confPath));
				} catch (IOException cause) {
					throw new RuntimeException("Cannot read: " + confPath, cause);
				}
				Builder builder = new Builder();
				keyLoader.load(properties, Key.REPOSITORY_PATH_MAILS, Paths::get, builder::setMailsRepositoryPath);
				keyLoader.load(properties, Key.REPOSITORY_PATH_ISSUES, Paths::get, builder::setIssuesRepositoryPath);
				keyLoader.load(properties, Key.REPOSITORY_PATH_QUESTIONS, Paths::get,
						builder::setQuestionsRepositoryPath);
				return finalizer.apply(builder);
			}
		};
	}

	private static <T> void saveOrFail(Properties properties, Key key,
			Function<? super T, ? extends String> transformer, Supplier<? extends T> store) {
		Optional.ofNullable(store.get()).ifPresentOrElse(value -> {
			properties.setProperty(key.name(), transformer.apply(value));
		}, () -> {
			throw new MissingKeyException(key);
		});
	}

	private static <T> void loadOrFail(Properties properties, Key key,
			Function<? super String, ? extends T> transformer, Consumer<? super T> store) {
		Optional.ofNullable(properties.getProperty(key.name())).map(transformer).ifPresentOrElse(store, () -> {
			throw new MissingKeyException(key);
		});
	}

	private static <T> void loadIncomplete(Properties properties, Key key,
			Function<? super String, ? extends T> transformer, Consumer<? super T> store) {
		Optional.ofNullable(properties.getProperty(key.name())).map(transformer).ifPresent(store);
	}
}
