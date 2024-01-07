package fr.vergne.condominium.core.util;

public interface Persister<T> {
	boolean hasSave();

	void save(T t);

	T load();
}
