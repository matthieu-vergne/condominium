package fr.vergne.condominium.core.parser.mbox;

import static java.lang.System.lineSeparator;
import static java.util.stream.Collectors.joining;

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
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Spliterators;
import java.util.function.BiFunction;
import java.util.function.Consumer;
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
import fr.vergne.condominium.core.mail.Mail.Body;
import fr.vergne.condominium.core.mail.MimeType;
import fr.vergne.condominium.core.parser.mbox.MBoxParser.Encoding.Decoder.FromString;
import fr.vergne.condominium.core.util.StringEscaper;

public class MBoxParser {

	private final Pattern headerPattern = Pattern.compile("^([^:]+):(.*)$");
	private final DateTimeFormatter timestampFormatter = DateTimeFormatter.ofPattern("E MMM dd HH:mm:ss Z yyyy",
			Locale.ENGLISH);
	private final Function<String, Address> addressParser = MBoxParser.Address.parser();
	private final Consumer<Object> logger;

	public MBoxParser(Consumer<Object> logger) {
		this.logger = logger;
	}

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

	public Mail parseMail(List<String> lines) {
		// TODO Support RFCs
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

		Parsed parsed = parse(linesIterator);

		Supplier<Mail.Address> senderToSupplier = () -> {
			Address senderAddress = parsed.headers().tryGet("From")//
					.map(Header::body)//
					.map(addressParser)//
					.orElseThrow(() -> new IllegalStateException("No sender for " + id));
			return Mail.Address.createWithCanonEmail(senderAddress.name(), senderAddress.email());
		};

		Supplier<Stream<Mail.Address>> receiversSupplier = () -> {
			return Stream.of(//
					parsed.headers().tryGet("To"), //
					parsed.headers().tryGet("Cc"), //
					parsed.headers().tryGet("Delivered-To")//
			)//
					.filter(Optional::isPresent).map(Optional::get)//
					.map(Header::body)//
					.flatMap(addresses -> splitAddresses(addresses))//
					.map(addressParser)//
					.map(address -> Mail.Address.createWithCanonEmail(address.name(), address.email()));
		};

		return new Mail.Base(id, lines, parsed.headersSupplier(), parsed.bodySupplier(), receivedDateSupplier,
				senderToSupplier, receiversSupplier);
	}

	private Stream<String> splitAddresses(String addresses) {
		StringEscaper escaper = StringEscaper.Builder.create()//
				.escapeWith('$')//
				.whenEnclosedIn('"', sub -> sub.replace(',', 'µ'))//
				.build();

		return Stream.of(addresses)//
				.map(escaper::escape)//
				.flatMap(str -> Stream.of(str.split(",")))//
				.map(escaper::unescape);
	}

	record Parsed(Supplier<Headers> headersSupplier, Supplier<Body> bodySupplier) {
		Headers headers() {
			return headersSupplier.get();
		}

		Body body() {
			return bodySupplier.get();
		}
	}

	private Parsed parse(Iterator<String> linesIterator) {
		Supplier<Headers> headersSupplier = () -> parseHeaders(linesIterator);
		// The lines can be consumed only once, so cache the result
		Supplier<Headers> actualHeadersSupplier = cache(headersSupplier);

		Supplier<Body> bodySupplier = () -> parseBody(linesIterator, actualHeadersSupplier.get());
		// The lines can be consumed only once, so cache the result
		Supplier<Body> actualBodySupplier = cache(bodySupplier);

		return new Parsed(actualHeadersSupplier, actualBodySupplier);
	}

	private <T> Supplier<T> cache(Supplier<T> supplier) {
		var cache = new Object() {
			T value = null;
		};
		return () -> {
			if (cache.value == null) {
				cache.value = supplier.get();
			}
			return cache.value;
		};
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

	private Body parseBody(Iterator<String> linesIterator, Headers headers) {
		StringBuilder bodyContentBuilder = new StringBuilder();
		String separator = lineSeparator();
		while (linesIterator.hasNext()) {
			String line = linesIterator.next();
			bodyContentBuilder.append(line).append(separator);
		}
		String bodyContent = bodyContentBuilder.toString();
		RawBody rawBody = new RawBody(bodyContent);

		// References:
		// https://datatracker.ietf.org/doc/html/rfc2045#section-6.1
		// TODO Support ietf-token
		// TODO Support x-token
		Encoding encoding = headers.tryGet("Content-Transfer-Encoding").map(Header::body)//
				.map(String::toLowerCase)//
				.map(contentTransferEncoding -> {
					switch (contentTransferEncoding) {
					case "base64":
						return Encoding.BASE64;
					case "quoted-printable":
						return Encoding.QUOTED_PRINTABLE;
					case "8bit":
						return Encoding._8BIT;
					case "7bit":
						return Encoding._7BIT;
					case "binary":
						return Encoding.BINARY;
					default:
						throw new RuntimeException("Not supported: " + contentTransferEncoding);
					}
				})//
				.orElse(Encoding._7BIT);

		Encoding.Decoder decoder = encoding.decoder();
		FromString stringDecoder = decoder.fromString(bodyContent);
		Function<String, byte[]> bytesDecoder = decoder.forBytes();

		Body body = headers.tryGet("Content-Type").map(ContentType::parse).<Body>map(contentType -> {
			Supplier<String> stringSupplier = () -> stringDecoder
					.toString(contentType.charset().orElse(Charset.defaultCharset()));
			Supplier<byte[]> bytesSupplier = () -> bytesDecoder.apply(bodyContent);
			if (contentType.mimeType().equals(MimeType.Text.PLAIN)) {
				// TODO Deduplicate class + mime type
				return new TextualBody(MimeType.Text.PLAIN, stringSupplier.get());
			} else if (contentType.mimeType().equals(MimeType.Text.HTML)) {
				return new TextualBody(MimeType.Text.HTML, stringSupplier.get());
			} else if (contentType.mimeType().equals(MimeType.Text.AMP)) {
				return new TextualBody(MimeType.Text.AMP, stringSupplier.get());
			} else if (contentType.mimeType().equals(MimeType.Text.RFC822_HEADERS)) {
				// https://datatracker.ietf.org/doc/html/rfc1892#section-2
				return new TextualBody(MimeType.Text.RFC822_HEADERS, stringSupplier.get());
			} else if (contentType.mimeType().equals(MimeType.Message.RFC822)) {
				// https://datatracker.ietf.org/doc/html/rfc2046#section-5.2
				return new TextualBody(MimeType.Message.RFC822, stringSupplier.get());
			} else if (contentType.mimeType().equals(MimeType.Message.DELIVERY_STATUS)) {
				// https://datatracker.ietf.org/doc/html/rfc3464#section-2.1
				return new TextualBody(MimeType.Message.DELIVERY_STATUS, stringSupplier.get());
			} else if (contentType.mimeType().equals(MimeType.Text.CALENDAR)) {
				return new CalendarBody(MimeType.Text.CALENDAR, stringSupplier.get(), contentType.method().get());
			} else if (contentType.mimeType().equals(MimeType.Application.ICS)) {
				return new IcsBody(MimeType.Application.ICS, stringSupplier.get(), contentType.name().get());
			} else if (contentType.mimeType().equals(MimeType.Application.PDF)) {
				// https://opensource.adobe.com/dc-acrobat-sdk-docs/pdflsdk/#pdf-reference
				return new NamedBinaryBody(MimeType.Application.PDF, bytesSupplier.get(), contentType.name().get());
			} else if (contentType.mimeType().equals(MimeType.Application.WORD)) {
				return new NamedBinaryBody(MimeType.Application.WORD, bytesSupplier.get(), contentType.name().get());
			} else if (contentType.mimeType().equals(MimeType.Application.SPREADSHEET)) {
				return new NamedBinaryBody(MimeType.Application.SPREADSHEET, bytesSupplier.get(),
						contentType.name().get());
			} else if (contentType.mimeType().equals(MimeType.Image.PNG)) {
				return new ImageBody(MimeType.Image.PNG, bytesSupplier.get());
			} else if (contentType.mimeType().equals(MimeType.Image.JPEG)) {
				return new ImageBody(MimeType.Image.JPEG, bytesSupplier.get());
			} else if (contentType.mimeType().equals(MimeType.Image.GIF)) {
				return new ImageBody(MimeType.Image.GIF, bytesSupplier.get());
			} else if (contentType.mimeType().equals(MimeType.Image.HEIC)) {
				return new ImageBody(MimeType.Image.HEIC, bytesSupplier.get());
			} else if (contentType.mimeType().equals(MimeType.Video.MP4)) {
				return new VideoBody(MimeType.Video.MP4, bytesSupplier.get());
			} else if (contentType.mimeType().equals(MimeType.Multipart.MIXED)) {
				// References:
				// https://datatracker.ietf.org/doc/html/rfc2046#section-5.1.3
				// TODO Check reference to properly parse it
				// TODO Default multipart, or if not recognized, should be considered as mixed
				String boundary = contentType.boundary().get();
				String boundaryRegex = "(^|\\r?\\n)--" + boundary + "(--)?(\\r?\\n|$)";
				return new ComposedBody(MimeType.Multipart.MIXED, Stream.of(bodyContent.split(boundaryRegex))//
						.skip(1)// Ignore first part
						.map(part -> parse(part.lines().iterator()).body())//
						.toList()//
				);
			} else if (contentType.mimeType().equals(MimeType.Multipart.ALTERNATIVE)) {
				// References:
				// https://datatracker.ietf.org/doc/html/rfc2046#section-5.1.4
				// TODO Check reference to properly parse it
				String boundary = contentType.boundary().get();
				String boundaryRegex = "(^|\\r?\\n)--" + boundary + "(--)?(\\r?\\n|$)";
				return new ComposedBody(MimeType.Multipart.ALTERNATIVE, Stream.of(bodyContent.split(boundaryRegex))//
						.skip(1)// Ignore first part
						.map(part -> parse(part.lines().iterator()).body())//
						.toList()//
				);
			} else if (contentType.mimeType().equals(MimeType.Multipart.RELATED)) {
				// References:
				// https://datatracker.ietf.org/doc/html/rfc2387
				// TODO Check reference to properly parse it
				String boundary = contentType.boundary().get();
				String boundaryRegex = "(^|\\r?\\n)--" + boundary + "(--)?(\\r?\\n|$)";
				return new ComposedBody(MimeType.Multipart.RELATED, Stream.of(bodyContent.split(boundaryRegex))//
						.skip(1)// Ignore first part
						.map(part -> parse(part.lines().iterator()).body())//
						.toList());
			} else if (contentType.mimeType().equals(MimeType.Multipart.REPORT)) {
				// References:
				// https://datatracker.ietf.org/doc/html/rfc6522
				// TODO Check reference to properly parse it
				String boundary = contentType.boundary().get();
				String boundaryRegex = "(^|\\r?\\n)--" + boundary + "(--)?(\\r?\\n|$)";
				return new ComposedBody(MimeType.Multipart.REPORT, Stream.of(bodyContent.split(boundaryRegex))//
						.skip(1)// Ignore first part
						.map(part -> parse(part.lines().iterator()).body())//
						.toList());
			} else if (contentType.mimeType().equals(MimeType.Application.OCTET_STREAM)) {
				// TODO Should we be careful of application/octet-stream for security?
				// TODO Retrieve type from content if possible, otherwise from name
				return contentType.name().map(name -> {
					if (name.endsWith(".pdf")) {
						return new NamedBinaryBody(MimeType.Application.OCTET_STREAM, bytesSupplier.get(),
								contentType.name().get());
					} else {
						throw new RuntimeException("Not supported: " + name);
					}
				}).orElseThrow(() -> new RuntimeException("No name to retrieve type"));
			} else {
				throw new RuntimeException("Not supported: " + contentType);
			}
		}).orElse(rawBody);

		return body;
	}

	interface ContentType {

		MimeType mimeType();

		Optional<Charset> charset();

		Optional<String> boundary();

		Optional<String> type();

		Optional<String> method();

		Optional<String> name();

		public static ContentType parse(Header header) {
			// TODO Parse reliably (which RFC?)
			String contentType = header.body();
			String[] split = contentType.split(";");
			MimeType mimeType = MimeType.parse(split[0].trim());
			var wrapper = new Object() {
				// TODO Check the purpose of each item
				Optional<String> charset = Optional.empty();
				Optional<String> boundary = Optional.empty();
				Optional<String> type = Optional.empty();
				Optional<String> format = Optional.empty();
				Optional<String> delsp = Optional.empty();
				Optional<String> method = Optional.empty();
				Optional<String> name = Optional.empty();
				Optional<String> reportType = Optional.empty();
				Optional<String> differences = Optional.empty();
			};
			Stream.of(split).skip(1).map(String::trim).forEach(part -> {
				String[] split2 = part.split("=", 2);
				String name = split2[0];
				String value = split2[1];
				if (name.equals("charset")) {
					wrapper.charset = Optional.of(unquote(value));
				} else if (name.equals("boundary")) {
					wrapper.boundary = Optional.of(unquote(value));
				} else if (name.equals("type")) {
					wrapper.type = Optional.of(value);
				} else if (name.equals("format")) {
					wrapper.format = Optional.of(value);
				} else if (name.equals("delsp")) {
					wrapper.delsp = Optional.of(value);
				} else if (name.equals("method")) {
					wrapper.method = Optional.of(value);
				} else if (name.equals("name")) {
					wrapper.name = Optional.of(unquote(value));
				} else if (name.equals("report-type")) {
					wrapper.reportType = Optional.of(unquote(value));
				} else if (name.equals("differences")) {
					wrapper.differences = Optional.of(value);
				} else {
					throw new RuntimeException("Not supported: " + name);
				}
			});
			return new ContentType() {
				@Override
				public MimeType mimeType() {
					return mimeType;
				}

				@Override
				public Optional<Charset> charset() {
					return wrapper.charset.map(Charset::forName);
				}

				@Override
				public Optional<String> boundary() {
					return wrapper.boundary;
				}

				@Override
				public Optional<String> type() {
					return wrapper.type;
				}

				@Override
				public Optional<String> method() {
					return wrapper.method;
				}

				@Override
				public Optional<String> name() {
					return wrapper.name;
				}

				@Override
				public String toString() {
					return contentType;
				}
			};
		}

		private static String unquote(String value) {
			return value.startsWith("\"") && value.endsWith("\"") //
					? value.substring(1, value.length() - 1) //
					: value;
		}
	}

	public static class RawBody implements Mail.Body {
		private final String content;

		public RawBody(String content) {
			this.content = content;
		}

		@Override
		public MimeType mimeType() {
			throw new NoSuchElementException("No mime type provided");
		}

		@Override
		public String toString() {
			return "[RAW]\n" + content;
		}
	}

	public static class TypedBody implements Mail.Body {
		private final MimeType mimeType;

		public TypedBody(MimeType mimeType) {
			this.mimeType = mimeType;
		}

		@Override
		public MimeType mimeType() {
			return mimeType;
		}
	}

	public static class ComposedBody extends TypedBody implements Mail.Body.Composed {
		private final Collection<? extends Mail.Body> bodies;

		public ComposedBody(MimeType mimeType, Collection<? extends Mail.Body> bodies) {
			super(mimeType);
			this.bodies = bodies;
		}

		@Override
		public Collection<? extends Mail.Body> bodies() {
			return bodies;
		}

		@Override
		public String toString() {
			return "[" + mimeType() + "]" + bodies().size() + "\n"
					+ bodies().stream().map(Object::toString).collect(joining("\n-----\n"));
		}
	}

	public static class TextualBody extends TypedBody implements Mail.Body.Textual {
		private final String text;

		public TextualBody(MimeType mimeType, String text) {
			super(mimeType);
			this.text = text;
		}

		@Override
		public String text() {
			return text;
		}

		@Override
		public String toString() {
			return "[" + mimeType() + "]\n" + text;
		}
	}

	public static class BinaryBody extends TypedBody implements Mail.Body.Binary {
		private final byte[] bytes;

		public BinaryBody(MimeType mimeType, byte[] bytes) {
			super(mimeType);
			this.bytes = bytes;
		}

		@Override
		public byte[] bytes() {
			return bytes;
		}

		@Override
		public String toString() {
			return "[" + mimeType() + "] " + bytes.length + " bytes";
		}
	}

	public static class NamedBinaryBody extends BinaryBody implements Mail.Body.Named {
		private final String name;

		public NamedBinaryBody(MimeType mimeType, byte[] bytes, String name) {
			super(mimeType, bytes);
			this.name = name;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public String toString() {
			return super.toString() + " as " + name;
		}
	}

	public static class ImageBody extends TypedBody {
		enum Format {
			PNG(MimeType.Image.PNG), //
			JPEG(MimeType.Image.JPEG), //
			GIF(MimeType.Image.GIF), //
			HEIC(MimeType.Image.HEIC),//
			;

			private final MimeType mimeType;

			Format(MimeType mimeType) {
				this.mimeType = mimeType;
			}

			static Format fromMimeType(MimeType mimeType) {
				return Stream.of(values())//
						.filter(format -> format.mimeType.equals(mimeType))//
						.findFirst().orElseThrow(() -> {
							return new IllegalArgumentException("No format for mime type: " + mimeType);
						});
			}
		}

		private final byte[] bytes;

		public ImageBody(MimeType mimeType, byte[] bytes) {
			super(mimeType);
			this.bytes = bytes;
		}

		public byte[] bytes() {
			return bytes;
		}

		public Format format() {
			return Format.fromMimeType(mimeType());
		}

		@Override
		public String toString() {
			return "[" + mimeType() + "]" + bytes.length;
		}
	}

	public static class VideoBody extends TypedBody {
		enum Format {
			MP4(MimeType.Video.MP4), //
			;

			private final MimeType mimeType;

			Format(MimeType mimeType) {
				this.mimeType = mimeType;
			}

			static Format fromMimeType(MimeType mimeType) {
				return Stream.of(values())//
						.filter(format -> format.mimeType.equals(mimeType))//
						.findFirst().orElseThrow(() -> {
							return new IllegalArgumentException("No format for mime type: " + mimeType);
						});
			}
		}

		private final byte[] bytes;

		public VideoBody(MimeType mimeType, byte[] bytes) {
			super(mimeType);
			this.bytes = bytes;
		}

		public byte[] bytes() {
			return bytes;
		}

		public Format format() {
			return Format.fromMimeType(mimeType());
		}

		@Override
		public String toString() {
			return "[" + mimeType() + "]" + bytes.length;
		}
	}

	public static class CalendarBody extends TypedBody {
		private final String script;
		private final String method;

		public CalendarBody(MimeType mimeType, String script, String method) {
			super(mimeType);
			this.script = script;
			this.method = method;
		}

		public String script() {
			return script;
		}

		public String method() {
			return method;
		}

		@Override
		public String toString() {
			return "[" + mimeType() + "]\n" + script;
		}
	}

	public static class IcsBody extends TypedBody {
		private final String script;
		private final String name;

		public IcsBody(MimeType mimeType, String script, String name) {
			super(mimeType);
			this.script = script;
			this.name = name;
		}

		public String script() {
			return script;
		}

		public String name() {
			return name;
		}

		@Override
		public String toString() {
			return "[" + mimeType() + "]\n" + script;
		}
	}

	private List<String> unfoldHeaders(List<String> lines) {
		LinkedList<String> unfoldedLines = new LinkedList<>();
		for (String line : lines) {
			if (line.startsWith(" ") || line.startsWith("\t")) {
				unfoldedLines.add(unfoldedLines.removeLast() + line.substring(1));
			} else {
				unfoldedLines.add(line);
			}
		}
		return unfoldedLines;
	}

	public enum Encoding {
		BINARY(//
				(encoded) -> {
					// TODO Is String input relevant in this case?
					throw new RuntimeException("Not implemented yet");
				}, //
				(encoded, charset) -> {
					// TODO Is String input relevant in this case?
					throw new RuntimeException("Not implemented yet");
				} //
		), //
		_7BIT(//
				// References:
				// https://datatracker.ietf.org/doc/html/rfc2045#autoid-9
				// https://datatracker.ietf.org/doc/html/rfc2045#section-6.2
				(encoded) -> {
					throw new RuntimeException("Not implemented yet");
				}, //
				(encoded, charset) -> {
					return encoded;
				} //
		), //
		_8BIT(//
				(encoded) -> {
					throw new RuntimeException("Not implemented yet");
				}, //
				(encoded, charset) -> {
					return encoded;
				} //
		), //
		BASE64(//
				(encoded) -> {
					return Base64.getMimeDecoder().decode(encoded);
				}, //
				(encoded, charset) -> {
					return new String(Base64.getMimeDecoder().decode(encoded), charset);
				} //
		), //
		QUOTED_PRINTABLE(//
				(encoded) -> {
					throw new RuntimeException("Not implemented yet");
				}, //
				(encoded, charset) -> {
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
							if (character == 9 || character == 32) {
								whiteSpaces.add(character);
							} else if (character == codePointOf('\r')) {
								whiteSpaces.clear();
								out.write(character);
								out.write(reader.read());// Assume \n
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
				} //
		), //
		B(//
				(encoded) -> {
					throw new RuntimeException("Not implemented yet");
				}, //
				(encoded, charset) -> {
					return new String(Base64.getMimeDecoder().decode(encoded), charset);
				} //
		), //
		Q(//
				(encoded) -> {
					throw new RuntimeException("Not implemented yet");
				}, //
				(encoded, charset) -> {
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
							} else if (character == codePointOf('\r')) {
								whiteSpaces.clear();
								out.write(character);
								out.write(reader.read());// Assume \n
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
				} //
		);//

		private final Function<String, byte[]> byteDecoder;
		private final BiFunction<String, Charset, String> stringDecoder;

		private Encoding(Function<String, byte[]> byteDecoder, BiFunction<String, Charset, String> stringDecoder) {
			this.byteDecoder = byteDecoder;
			this.stringDecoder = stringDecoder;
		}

		Decoder decoder() {
			return new Decoder() {

				@Override
				public Function<String, byte[]> forBytes() {
					return byteDecoder;
				}

				@Override
				public Function<String, String> forString(Charset charset) {
					return content -> stringDecoder.apply(content, charset);
				}

				@Override
				public FromString fromString(String content) {
					return new Decoder.FromString() {

						@Override
						public byte[] toBytes() {
							return byteDecoder.apply(content);
						}

						@Override
						public String toString(Charset charset) {
							return stringDecoder.apply(content, charset);
						}
					};
				}
			};
		}

		interface Decoder {
			Function<String, byte[]> forBytes();

			Function<String, String> forString(Charset charset);

			Decoder.FromString fromString(String content);

			interface FromString {
				byte[] toBytes();

				String toString(Charset charset);
			}
		}

		private static final Pattern ENCODED_PATTERN = Pattern.compile("=\\?(.*?)\\?(.*?)\\?(.*?)\\?=");

		public static String decodeAll(String value) {
			return ENCODED_PATTERN.matcher(value).replaceAll(match -> {
				Charset charset = Charset.forName(match.group(1));
				Encoding encoding = Encoding.valueOf(match.group(2).toUpperCase());
				UnaryOperator<String> decoder = encoding.decoder().forString(charset)::apply;
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
