package fr.vergne.condominium.core.util;

import java.util.function.Function;

/**
 * Defines a pair of objects of the same type.
 * 
 * @param <T> the type of items paired
 */
public record Pair<T>(T item1, T item2) {

	/**
	 * Transform the items homogeneously to create a transformed {@link Pair}.
	 * 
	 * @param <U>            the new type of items paired
	 * @param transformation the transformation to apply to each item
	 * @return a newly typed {@link Pair}
	 */
	public <U> Pair<U> map(Function<T, U> transformation) {
		return new Pair<>(transformation.apply(this.item1()), transformation.apply(this.item2()));
	}
}
