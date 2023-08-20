package fr.vergne.condominium.core.mail;

import static java.util.stream.Collectors.toMap;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import fr.vergne.condominium.core.parser.mbox.MBoxParser.Symbol;

public interface Headers {
	// TODO Provide standard headers
	// https://datatracker.ietf.org/doc/html/rfc822#section-4.1

	Stream<Header> stream();

	Optional<Header> tryGet(String name);

	default Header get(String name) {
		return tryGet(name).orElseThrow(() -> new NoSuchElementException("No header " + name));
	}

	static Headers createFromMap(Map<String, List<String>> headers,
			BiFunction<String, String, Stream<Symbol>> symbolsParser) {
		Map<String, List<String>> formattedHeaders = headers.entrySet().stream().collect(toMap(//
				entry -> entry.getKey().toLowerCase(), //
				entry -> entry.getValue()//
		));
		return new Headers() {

			@Override
			public Optional<Header> tryGet(String name) {
				List<String> bodies = formattedHeaders.get(name.toLowerCase());
				if (bodies == null) {
					return Optional.empty();
				}
				return Optional.of(new Header.WithString() {

					@Override
					public String name() {
						return name;
					}

					@Override
					public List<String> bodies() {
						return bodies;
					}

					@Override
					public Stream<Symbol> structure() {
						return symbolsParser.apply(name, body());
					}
				});
			}

			@Override
			public Stream<Header> stream() {
				return formattedHeaders.entrySet().stream().map(entry -> {
					return new Header.WithString() {

						@Override
						public String name() {
							return entry.getKey();
						}

						@Override
						public List<String> bodies() {
							return entry.getValue();
						}

						@Override
						public Stream<Symbol> structure() {
							return symbolsParser.apply(name(), body());
						}
					};
				});
			}
		};
	}
}
