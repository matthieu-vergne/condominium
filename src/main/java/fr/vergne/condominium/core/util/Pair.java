package fr.vergne.condominium.core.util;

import java.util.function.Function;

public record Pair<T>(T a, T b) {

	public <U> Pair<U> map(Function<T, U> f) {
		return new Pair<>(f.apply(this.a()), f.apply(this.b()));
	}
}
