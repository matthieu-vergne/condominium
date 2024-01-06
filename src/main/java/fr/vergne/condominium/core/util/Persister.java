package fr.vergne.condominium.core.util;

public interface Persister {
	boolean hasSave();

	void save();

	void load();
}
