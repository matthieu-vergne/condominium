package fr.vergne.condominium.core.repository;

import static java.nio.file.Files.delete;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.readAllBytes;
import static java.nio.file.Files.write;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class FileRepository<R, K> implements Repository<R, K> {

	private Function<R, K> identifier;
	private Function<R, byte[]> resourceSerializer;
	private Function<Supplier<byte[]>, R> resourceDeserializer;
	private Function<K, Path> pathResolver;
	private Supplier<Stream<Path>> pathFinder;

	public FileRepository(Function<R, K> identifier, Function<R, byte[]> resourceSerializer,
			Function<Supplier<byte[]>, R> resourceDeserializer, Function<K, Path> pathResolver,
			Supplier<Stream<Path>> pathFinder) {
		this.identifier = identifier;
		this.pathFinder = pathFinder;
		this.pathResolver = pathResolver;
		this.resourceSerializer = resourceSerializer;
		this.resourceDeserializer = resourceDeserializer;
	}

	@Override
	public K add(R resource) throws AlredyExistingResourceKeyException {
		K key = identifier.apply(resource);
		Path path = pathResolver.apply(key);
		if (exists(path)) {
			throw new AlredyExistingResourceKeyException(key);
		}
		try {
			write(path, resourceSerializer.apply(resource));
		} catch (IOException cause) {
			throw new CannotWriteFileException(key, path, cause);
		}
		return key;
	}

	@Override
	public Optional<K> key(R resource) {
		K key = identifier.apply(resource);
		Path path = pathResolver.apply(key);
		if (exists(path)) {
			return Optional.of(key);
		} else {
			return Optional.empty();
		}
	}

	@Override
	public boolean has(K key) {
		Path path = pathResolver.apply(key);
		return exists(path);
	}

	@Override
	public Optional<R> get(K key) {
		Path path = pathResolver.apply(key);
		if (!exists(path)) {
			return Optional.empty();
		}
		R resource = resourceDeserializer.apply(() -> {
			try {
				return readAllBytes(path);
			} catch (IOException cause) {
				throw new CannotReadFileException(key, path, cause);
			}
		});
		return Optional.of(resource);
	}

	@Override
	public Optional<R> remove(K key) {
		Path path = pathResolver.apply(key);
		if (!exists(path)) {
			return Optional.empty();
		}
		byte[] bytes;
		try {
			bytes = readAllBytes(path);
		} catch (IOException cause) {
			throw new CannotReadFileException(key, path, cause);
		}
		R resource = resourceDeserializer.apply(() -> bytes);
		try {
			delete(path);
		} catch (IOException cause) {
			throw new CannotDeleteFileException(key, path, cause);
		}
		return Optional.of(resource);
	}

	@Override
	public Stream<R> streamResources() {
		return pathFinder.get().map(path -> {
			return resourceDeserializer.apply(() -> {
				try {
					return readAllBytes(path);
				} catch (IOException cause) {
					throw new CannotReadFileException(path, cause);
				}
			});
		});
	}

	@Override
	public Stream<K> streamKeys() {
		return streamResources().map(identifier);
	}

	@Override
	public Stream<Entry<K, R>> stream() {
		return streamResources().map(resource -> {
			K key = identifier.apply(resource);
			return Map.entry(key, resource);
		});
	}

	@SuppressWarnings("serial")
	public static class CannotWriteFileException extends RuntimeException {

		public CannotWriteFileException(Object key, Path path, IOException cause) {
			super("Cannot write " + key + " at " + path, cause);
		}
	}

	@SuppressWarnings("serial")
	public static class CannotReadFileException extends RuntimeException {

		public CannotReadFileException(Object key, Path path, IOException cause) {
			super("Cannot read " + key + " at " + path, cause);
		}

		public CannotReadFileException(Path path, IOException cause) {
			super("Cannot read file at " + path, cause);
		}
	}

	@SuppressWarnings("serial")
	public static class CannotDeleteFileException extends RuntimeException {

		public CannotDeleteFileException(Object key, Path path, IOException cause) {
			super("Cannot delete " + key + " at " + path, cause);
		}
	}
}
