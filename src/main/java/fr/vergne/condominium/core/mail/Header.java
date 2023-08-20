package fr.vergne.condominium.core.mail;

import static java.util.stream.Collectors.joining;

import java.util.List;
import java.util.stream.Stream;

import fr.vergne.condominium.core.parser.mbox.MBoxParser.Symbol;

public interface Header {
	String name();

	default String body() {
		List<String> bodies = bodies();
		int size = bodies.size();
		if (size == 1) {
			return bodies.get(0);
		} else {
			throw new IllegalStateException("Not exactly one body: " + size);
		}
	}

	List<String> bodies();

	static abstract class WithString implements Header {
		@Override
		public String toString() {
			return name() + ": " + body();
		}
	}
	
	Stream<Symbol> structure();

	default String canon() {
		return structure()//
				.filter(symbol -> symbol.type() != Symbol.Type.COMMENT)//
				.map(symbol -> symbol.value())//
				.collect(joining());
	}

}
