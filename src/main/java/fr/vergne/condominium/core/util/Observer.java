package fr.vergne.condominium.core.util;

public interface Observer<T> {
	void change(T oldValue, T newValue);
}
