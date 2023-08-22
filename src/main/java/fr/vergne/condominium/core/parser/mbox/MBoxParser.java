package fr.vergne.condominium.core.parser.mbox;

import static java.lang.System.lineSeparator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import fr.vergne.condominium.core.mail.Header;
import fr.vergne.condominium.core.mail.Headers;
import fr.vergne.condominium.core.mail.Mail;

public class MBoxParser {

	private final Pattern headerPattern = Pattern.compile("^([^:]+):(.*)$");
	private final DateTimeFormatter timestampFormatter = DateTimeFormatter.ofPattern("E MMM dd HH:mm:ss Z yyyy",
			Locale.ENGLISH);
	private final Function<String, Address> addressParser = MBoxParser.Address.parser();

	public Stream<Mail> parseMBox(Path mboxPath) {
		Stream<String> lines;
		try {
			lines = Files.lines(mboxPath, Charset.defaultCharset());
		} catch (IOException cause) {
			throw new RuntimeException("Cannot open " + mboxPath, cause);
		}
		return groupPerMail(lines).map(this::parseMail);
	}

	private Stream<List<String>> groupPerMail(Stream<String> lines) {
		Iterator<List<String>> mailsIterator = new Iterator<List<String>>() {
			Iterator<String> linesIterator = lines.iterator();
			List<String> nextEmail = null;
			List<String> emailLines = null;
			boolean isPrecededByBlankLine = true;
			String previousLine;

			@Override
			public boolean hasNext() {
				if (nextEmail != null) {
					return true;
				} else if (!linesIterator.hasNext()) {
					lines.close();
					return false;
				} else {
					while (linesIterator.hasNext()) {
						String line = linesIterator.next();
						if (isPrecededByBlankLine && line.startsWith("From ")) {
							if (emailLines != null) {
								nextEmail = emailLines;
							}
							emailLines = new LinkedList<>();
							previousLine = line;
							isPrecededByBlankLine = line.isEmpty();
							return true;
						} else {
							emailLines.add(previousLine);
							previousLine = line;
							isPrecededByBlankLine = line.isEmpty();
						}
					}
					nextEmail = emailLines;
					return true;
				}
			}

			@Override
			public List<String> next() {
				if (!hasNext()) {
					throw new NoSuchElementException();
				}
				List<String> email = nextEmail;
				nextEmail = null;
				return email;
			}
		};

		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(mailsIterator, 0), false);
	}

	private Mail parseMail(List<String> lines) {
		// TODO Support RFCs
		// https://datatracker.ietf.org/doc/html/rfcXXX
		// rfc822/rfc2822/rfc3522 Message format
		// rfc934 Message Forwarding
		// rfc2046 MIME
		// rfc4155 MIME application/mbox
		// rfc6854 Group syntax in From/Sender headers

		Iterator<String> linesIterator = lines.iterator();

		String fromLine = linesIterator.next();
		Pattern fromPattern = Pattern.compile("^From ([^ ]+) (.*)$");
		Matcher fromMatcher = fromPattern.matcher(fromLine);
		if (!fromMatcher.find()) {
			throw new RuntimeException("Invalid FROM line: " + fromLine);
		}
		String id = fromMatcher.group(1);
		ZonedDateTime receivedDate = parseTimestamp(fromMatcher.group(2));
		Supplier<ZonedDateTime> receivedDateSupplier = () -> receivedDate;

		Headers headers = parseHeaders(linesIterator);

		String body = readBody(linesIterator);

		Supplier<Mail.Address> senderToSupplier = () -> {
			Address senderAddress = headers.tryGet("From")//
					.map(Header::body)//
					.map(addressParser)//
					.orElseThrow(() -> new IllegalStateException("No sender for " + id));
			return Mail.Address.createWithCanonEmail(senderAddress.name(), senderAddress.email());
		};

		Supplier<Stream<Mail.Address>> receiversSupplier = () -> {
			return Stream.of(//
					headers.tryGet("To"), //
					headers.tryGet("Cc"), //
					headers.tryGet("Delivered-To")//
			)//
					.filter(Optional::isPresent).map(Optional::get)//
					.map(Header::body)//
					.flatMap(addresses -> Stream.of(addresses.split(",")))//
					.map(addressParser)//
					.map(address -> Mail.Address.createWithCanonEmail(address.name(), address.email()));
		};

		return new Mail.Base(id, lines, headers, body, receivedDateSupplier, senderToSupplier, receiversSupplier);
	}

	

	private Headers parseHeaders(Iterator<String> linesIterator) {
		List<String> headerLines = new LinkedList<>();
		while (linesIterator.hasNext()) {
			String line = linesIterator.next();
			if (line.isEmpty()) {
				break;
			} else {
				headerLines.add(line);
			}
		}

		headerLines = unfoldHeaders(headerLines);

		// TODO Parse headers following rfc822
		// - field-names
		// - unstructured field bodies
		// - structured field bodies
		// https://datatracker.ietf.org/doc/html/rfc822#section-3.1.2
		Map<String, List<String>> headers = new LinkedHashMap<>();
		for (String line : headerLines) {
			Matcher headerMatcher = headerPattern.matcher(line);
			if (!headerMatcher.find()) {
				throw new RuntimeException("Invalid header line: " + line);
			}
			String key = headerMatcher.group(1);
			String value = headerMatcher.group(2).trim();

			String decodedValue = Encoding.decodeAll(value);

			// Force case so common keys are kept together
			headers.compute(key.toLowerCase(), (k, v) -> {
				if (v == null) {
					v = new LinkedList<>();
				}
				v.add(decodedValue);
				return v;
			});
		}

		return Headers.createFromMap(headers, this::parseHeaderSymbols);
	}

	private Stream<Symbol> parseHeaderSymbols(String name, String body) {
		List<Symbol> symbols = new LinkedList<>();
		try (StringReader reader = new StringReader(body)) {
			int codePoint;
			StringBuilder atomBuilder = new StringBuilder();
			Runnable atomFlusher = () -> {
				if (!atomBuilder.isEmpty()) {
					symbols.add(new Symbol(Symbol.Type.ATOM, atomBuilder.toString()));
					atomBuilder.delete(0, atomBuilder.length());
				}
			};
			while ((codePoint = reader.read()) != -1) {
				if (isSpecialChar(codePoint)) {
					atomFlusher.run();
					symbols.add(new Symbol(Symbol.Type.INDIVIDUAL_SPECIAL_CHARACTER, Character.toString(codePoint)));
				} else if (isQuotedStart(codePoint)) {
					atomFlusher.run();
					String quotedString = consume(reader, MBoxParser::isQuotedStop);
					symbols.add(new Symbol(Symbol.Type.QUOTED_STRING, quotedString));
				} else if (isDomainStart(codePoint)) {
					atomFlusher.run();
					String domainLiteral = consume(reader, MBoxParser::isDomainStop);
					symbols.add(new Symbol(Symbol.Type.DOMAIN_LITERAL, domainLiteral));
				} else if (isCommentStart(codePoint)) {
					atomFlusher.run();
					String comment = consume(reader, MBoxParser::isCommentStop);
					symbols.add(new Symbol(Symbol.Type.COMMENT, comment));
				} else if (isSpace(codePoint)) {
					atomFlusher.run();
				} else {
					atomBuilder.appendCodePoint(codePoint);
				}
			}
			atomFlusher.run();
		} catch (IOException cause) {
			throw new RuntimeException("Cannot read body of header " + name, cause);
		}
		// TODO Parse based on stream consumption
		return symbols.stream();
	}

	private static String consume(StringReader reader, IntPredicate stopper) throws IOException {
		// TODO Support quoted chars
		// TODO Support recursiveness
		int codePoint;
		StringBuilder builder = new StringBuilder();
		for (codePoint = reader.read(); !stopper.test(codePoint); codePoint = reader.read()) {
			builder.appendCodePoint(codePoint);
		}
		return builder.toString();
	}

	private ZonedDateTime parseTimestamp(String timestamp) {
		ZonedDateTime dateTime;
		try {
			dateTime = timestampFormatter.parse(timestamp, Instant::from).atZone(ZoneId.systemDefault());
		} catch (DateTimeParseException cause) {
			throw new RuntimeException("Cannot parse '" + timestamp + "' as '" + timestampFormatter + "'", cause);
		}
		return dateTime;
	}

	private String readBody(Iterator<String> linesIterator) {
		StringBuilder bodyBuilder = new StringBuilder();
		String separator = lineSeparator();
		while (linesIterator.hasNext()) {
			String line = linesIterator.next();
			bodyBuilder.append(line).append(separator);
		}
		return bodyBuilder.toString();
	}

	private List<String> unfoldHeaders(List<String> lines) {
		LinkedList<String> unfoldedLines = new LinkedList<>();
		for (String line : lines) {
			if (line.startsWith(" ") || line.startsWith("\t")) {
				unfoldedLines.add(unfoldedLines.removeLast() + line);
			} else {
				unfoldedLines.add(line);
			}
		}
		return unfoldedLines;
	}

	public enum Encoding {
		B(() -> {
			Decoder decoder = Base64.getMimeDecoder();
			return charset -> encoded -> new String(decoder.decode(encoded), charset);
		}), //
		Q(() -> {
			// References:
			// https://datatracker.ietf.org/doc/html/rfc2045
			// https://datatracker.ietf.org/doc/html/rfc2047#section-4.2
			return charset -> {
				return encoded -> {
					StringReader reader = new StringReader(encoded);
					ByteArrayOutputStream out = new ByteArrayOutputStream();
					try {
						int character;
						List<Integer> whiteSpaces = new LinkedList<>();
						Runnable whiteSpacesWriter = () -> {
							whiteSpaces.forEach(out::write);
							whiteSpaces.clear();
						};
						while ((character = reader.read()) != -1) {
							// TODO Finish
							if (character == codePointOf('_')) {
								whiteSpacesWriter.run();
								out.write(' ');
							} else if (character == 9 || character == 32) {
								whiteSpaces.add(character);
							} else if (character == codePointOf('\n')) {
								whiteSpaces.clear();
								out.write(character);
							} else if (character == codePointOf('=')) {
								whiteSpacesWriter.run();
								char[] buffer = new char[2];
								reader.read(buffer);
								if (buffer[0] == codePointOf('\r') && buffer[1] == codePointOf('\n')) {
									// Simple line break to fit 76 characters
								} else {
									int decodedCharacter = Integer.parseInt(new String(buffer), 16);
									out.write(decodedCharacter);
								}
							} else if (33 <= character && character <= 60 || 62 <= character && character <= 126) {
								whiteSpacesWriter.run();
								Character.toString(character);
								out.write(character);
							} else {
								throw new RuntimeException("Not supported character: " + character);
							}
						}
					} catch (IOException cause) {
						throw new IllegalArgumentException("Cannot read: " + encoded);
					}
					return out.toString(charset);
				};
			};
		});//

		private final Function<Charset, UnaryOperator<String>> decoderFactory;

		private Encoding(Supplier<Function<Charset, UnaryOperator<String>>> decoderFactory) {
			this.decoderFactory = decoderFactory.get();
		}

		UnaryOperator<String> decoder(Charset charset) {
			return decoderFactory.apply(charset);
		}

		private static final Pattern ENCODED_PATTERN = Pattern.compile("=\\?(.*?)\\?(.*?)\\?(.*?)\\?=");

		public static String decodeAll(String value) {
			return ENCODED_PATTERN.matcher(value).replaceAll(match -> {
				Charset charset = Charset.forName(match.group(1));
				Encoding encoding = Encoding.valueOf(match.group(2));
				UnaryOperator<String> decoder = encoding.decoder(charset);
				String encoded = match.group(3);
				return decoder.apply(encoded);
			});
		}
	}

	public static record Symbol(Symbol.Type type, String value) {
		public static enum Type {
			INDIVIDUAL_SPECIAL_CHARACTER, QUOTED_STRING, DOMAIN_LITERAL, COMMENT, ATOM
		}
	}

	private static int codePointOf(char c) {
		return String.valueOf(c).codePointAt(0);
	}

	private static boolean isSpace(int codePoint) {
		return codePoint == codePointOf(' ');
	}

	private static boolean isCommentStart(int codePoint) {
		return codePoint == codePointOf('(');
	}

	private static boolean isCommentStop(int codePoint) {
		return codePoint == codePointOf(')');
	}

	private static boolean isDomainStart(int codePoint) {
		return codePoint == codePointOf('[');
	}

	private static boolean isDomainStop(int codePoint) {
		return codePoint == codePointOf(']');
	}

	private static boolean isQuotedStart(int codePoint) {
		return codePoint == codePointOf('"');
	}

	private static boolean isQuotedStop(int codePoint) {
		return codePoint == codePointOf('"');
	}

	private static boolean isSpecialChar(int codePoint) {
		return "()<>@,;:\\\".[]".contains(Character.toString(codePoint));
	}

	// TODO Finish
	// https://datatracker.ietf.org/doc/html/rfc2822#autoid-25
	public record Address(String email, Optional<String> name) {

		public Address(String email) {
			this(email, Optional.empty());
		}

		static Function<String, Optional<Address>> nameEmailParser(Pattern pattern,
				Function<Matcher, String> nameExtractor, Function<Matcher, String> emailExtractor) {
			return addressStr -> {
				Matcher matcher = pattern.matcher(addressStr);
				if (matcher.find()) {
					String name = nameExtractor.apply(matcher);
					String email = emailExtractor.apply(matcher);
					return Optional.of(new Address(email, Optional.of(name)));
				}
				return Optional.empty();
			};
		}

		static Function<String, Optional<Address>> emailParser(Pattern pattern,
				Function<Matcher, String> emailExtractor) {
			return addressStr -> {
				Matcher matcher = pattern.matcher(addressStr);
				if (matcher.find()) {
					String email = emailExtractor.apply(matcher);
					return Optional.of(new Address(email));
				}
				return Optional.empty();
			};
		}

		public static Function<String, Address> parser() {
			UnaryOperator<Optional<Address>> cleanNameIfEmpty = addressOpt -> addressOpt.map(address -> {
				return new Address(address.email(), address.name().filter(name -> !name.isBlank()));
			});
			List<Function<String, Optional<Address>>> optionalParsers = List.of(//
					nameEmailParser(//
							Pattern.compile("^\\s*\"?(.*?)\"?\\s*<([^>]+)>\\s*$"), //
							matcher -> matcher.group(1), matcher -> matcher.group(2)//
					).andThen(cleanNameIfEmpty), //
					nameEmailParser(//
							Pattern.compile("^\\s*\"?(.*?)\"?\\s*\\(([^)]+)\\)\\s*$"), //
							matcher -> matcher.group(2), matcher -> matcher.group(1)//
					).andThen(cleanNameIfEmpty), //
					emailParser(//
							Pattern.compile("^\\s*<([^>]+)>\\s*$"), //
							matcher -> matcher.group(1)//
					)//
			);

			Function<String, Address> defaultParser = addressStr -> new Address(addressStr.trim());

			return addressStr -> optionalParsers.stream()//
					.map(optionalParser -> optionalParser.apply(addressStr))//
					.dropWhile(Optional<Address>::isEmpty)//
					.map(Optional<Address>::get)//
					.findFirst().orElseGet(() -> defaultParser.apply(addressStr));
		}
	}
}
