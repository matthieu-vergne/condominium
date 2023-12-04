package fr.vergne.condominium.core.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.function.Function;

public interface Serializer<T, S> {
	S serialize(T t);

	T deserialize(S serial);

	public static <T, S> Serializer<T, S> createFromMap(Map<T, S> pairs) {
		Map<T, S> serializerMap = new HashMap<>();
		Map<S, T> deserializerMap = new HashMap<>();
		pairs.entrySet().stream().collect(HashSet<S>::new, (set, entry) -> {
			T object = entry.getKey();
			S serial = entry.getValue();
			if (!set.add(serial)) {
				throw new IllegalArgumentException(
						"Duplicate serial: " + serial + " on both " + object + " and " + deserializerMap.get(serial));
			}
			serializerMap.put(object, serial);
			deserializerMap.put(serial, object);
		}, HashSet<S>::addAll);

		return new Serializer<>() {
			@Override
			public S serialize(T key) {
				return serializerMap.computeIfAbsent(key, rejectMissingKey());
			}

			@Override
			public T deserialize(S serial) {
				return deserializerMap.computeIfAbsent(serial, rejectMissingKey());
			}

			public static <K, V> Function<K, V> rejectMissingKey() {
				return key -> {
					throw new IllegalArgumentException("Not supported: " + key);
				};
			}
		};
	}
}
