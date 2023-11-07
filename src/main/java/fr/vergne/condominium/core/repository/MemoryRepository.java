package fr.vergne.condominium.core.repository;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

public class MemoryRepository<R, K> implements Repository<R, K> {

	private final Function<R, K> identifier;
	private final Map<K, R> resources;

	public MemoryRepository(Function<R, K> identifier, Map<K, R> resources) {
		this.identifier = identifier;
		this.resources = resources;
	}

	@Override
	public K add(R resource) throws AlredyExistingResourceKeyException {
		K key = identifier.apply(resource);
		R previousResources = resources.putIfAbsent(key, resource);
		if (previousResources != null) {
			throw new AlredyExistingResourceKeyException(key);
		} else {
			return key;
		}
	}

	@Override
	public Optional<K> key(R resource) {
		return resources.entrySet().stream()//
				.filter(entry -> Objects.equals(entry.getValue(), resource))//
				.map(Map.Entry::getKey)//
				.findFirst();
	}

	@Override
	public boolean has(K key) {
		return resources.containsKey(key);
	}

	@Override
	public R get(K key) throws UnknownResourceKeyException {
		R resource = resources.get(key);
		if (resources == null) {
			throw new UnknownResourceKeyException(key);
		} else {
			return resource;
		}
	}

	@Override
	public R remove(K key) throws UnknownResourceKeyException {
		R resource = resources.remove(key);
		if (resources == null) {
			throw new UnknownResourceKeyException(key);
		} else {
			return resource;
		}
	}

	@Override
	public Stream<R> stream() {
		return resources.values().stream();
	}

}
