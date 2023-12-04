package fr.vergne.condominium.core.util;

import fr.vergne.condominium.core.source.Source.Refiner;

public interface RefinerIdSerializer {
	<I> Object serialize(Refiner<?, I, ?> refiner, I id);

	<I> I deserialize(Refiner<?, I, ?> refiner, Object serial);
}
