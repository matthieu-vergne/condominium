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
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import fr.vergne.condominium.core.mail.Header;
import fr.vergne.condominium.core.mail.Headers;
import fr.vergne.condominium.core.mail.Mail;
import fr.vergne.condominium.core.mail.Mail.Body;
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

	private Mail parseMail(List<String> lines) {
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

		Body body = headers.tryGet("Content-Type").map(ContentType::parse).map(contentType -> {
			Supplier<String> stringSupplier = () -> stringDecoder
					.toString(contentType.charset().orElse(Charset.defaultCharset()));
			Supplier<byte[]> bytesSupplier = () -> bytesDecoder.apply(bodyContent);
			if (contentType.mainType().equals("text/plain")) {
				return new PlainBody(stringSupplier.get());
			} else if (contentType.mainType().equals("text/html")) {
				return new HtmlBody(stringSupplier.get());
			} else if (contentType.mainType().equals("text/x-amp-html")) {
				return new AmpHtmlBody(stringSupplier.get());
			} else if (contentType.mainType().equals("text/rfc822-headers")) {
				// https://datatracker.ietf.org/doc/html/rfc1892#section-2
				return new MessageHeadersBody(stringSupplier.get());
			} else if (contentType.mainType().equals("message/rfc822")) {
				// https://datatracker.ietf.org/doc/html/rfc2046#section-5.2
				return new MessageBody(stringSupplier.get());
			} else if (contentType.mainType().equals("message/delivery-status")) {
				// https://datatracker.ietf.org/doc/html/rfc3464#section-2.1
				return new MessageStatusBody(stringSupplier.get());
			} else if (contentType.mainType().equals("text/calendar")) {
				return new CalendarBody(stringSupplier.get(), contentType.method().get());
			} else if (contentType.mainType().equals("application/ics")) {
				return new IcsBody(stringSupplier.get(), contentType.name().get());
			} else if (contentType.mainType().equals("application/pdf")) {
				return new PdfBody(bytesSupplier.get(), contentType.name().get());
			} else if (contentType.mainType()
					.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
				return new DocxBody(bytesSupplier.get(), contentType.name().get());
			} else if (contentType.mainType()
					.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) {
				return new XlsxBody(bytesSupplier.get(), contentType.name().get());
			} else if (contentType.mainType().equals("image/png")) {
				return new ImageBody(bytesSupplier.get(), ImageBody.Format.PNG);
			} else if (contentType.mainType().equals("image/jpeg")) {
				return new ImageBody(bytesSupplier.get(), ImageBody.Format.JPEG);
			} else if (contentType.mainType().equals("image/gif")) {
				return new ImageBody(bytesSupplier.get(), ImageBody.Format.GIF);
			} else if (contentType.mainType().equals("image/heic")) {
				return new ImageBody(bytesSupplier.get(), ImageBody.Format.HEIC);
			} else if (contentType.mainType().equals("video/mp4")) {
				return new VideoBody(bytesSupplier.get(), VideoBody.Format.MP4);
			} else if (contentType.mainType().equals("multipart/mixed")) {
				// References:
				// https://datatracker.ietf.org/doc/html/rfc2046#section-5.1.3
				// TODO Check reference to properly parse it
				// TODO Default multipart, or if not recognized, should be considered as mixed
				String boundary = contentType.boundary().get();
				String boundaryRegex = "(^|\\r?\\n)--" + boundary + "(--)?(\\r?\\n|$)";
				return new MultiMixBody(Stream.of(bodyContent.split(boundaryRegex))//
						.skip(1)// Ignore first part
						.map(part -> parse(part.lines().iterator()).body())//
						.toList()//
				);
			} else if (contentType.mainType().equals("multipart/alternative")) {
				// References:
				// https://datatracker.ietf.org/doc/html/rfc2046#section-5.1.4
				// TODO Check reference to properly parse it
				String boundary = contentType.boundary().get();
				String boundaryRegex = "(^|\\r?\\n)--" + boundary + "(--)?(\\r?\\n|$)";
				return new MultiAltBody(Stream.of(bodyContent.split(boundaryRegex))//
						.skip(1)// Ignore first part
						.map(part -> parse(part.lines().iterator()).body())//
						.toList()//
				);
			} else if (contentType.mainType().equals("multipart/related")) {
				// References:
				// https://datatracker.ietf.org/doc/html/rfc2387
				// TODO Check reference to properly parse it
				String boundary = contentType.boundary().get();
				String boundaryRegex = "(^|\\r?\\n)--" + boundary + "(--)?(\\r?\\n|$)";
				return new MultiRelBody(Stream.of(bodyContent.split(boundaryRegex))//
						.skip(1)// Ignore first part
						.map(part -> parse(part.lines().iterator()).body())//
						.toList());
			} else if (contentType.mainType().equals("multipart/report")) {
				// References:
				// https://datatracker.ietf.org/doc/html/rfc6522
				// TODO Check reference to properly parse it
				String boundary = contentType.boundary().get();
				String boundaryRegex = "(^|\\r?\\n)--" + boundary + "(--)?(\\r?\\n|$)";
				return new MultiRepBody(Stream.of(bodyContent.split(boundaryRegex))//
						.skip(1)// Ignore first part
						.map(part -> parse(part.lines().iterator()).body())//
						.toList());
			} else if (contentType.mainType().equals("application/octet-stream")) {
				// TODO Should we be careful of application/octet-stream for security?
				// TODO Retrieve type from content if possible, otherwise from name
				return contentType.name().map(name -> {
					if (name.endsWith(".pdf")) {
						return new PdfBody(bytesSupplier.get(), contentType.name().get());
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

		String mainType();

		Optional<Charset> charset();

		Optional<String> boundary();

		Optional<String> type();

		Optional<String> method();

		Optional<String> name();

		public static ContentType parse(Header header) {
			// TODO Parse reliably (which RFC?)
			String contentType = header.body();
			String[] split = contentType.split(";");
			String mainType = split[0].trim();
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
				} else {
					throw new RuntimeException("Not supported: " + name);
				}
			});
			return new ContentType() {
				@Override
				public String mainType() {
					return mainType;
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
		public String toString() {
			return "[RAW]\n" + content;
		}
	}

	public static class PlainBody implements Mail.Body {
		private final String text;

		public PlainBody(String text) {
			this.text = text;
		}

		public String text() {
			return text;
		}

		@Override
		public String toString() {
			return "[PLAIN]\n" + text;
		}
	}

	public static class HtmlBody implements Mail.Body {
		private final String html;

		public HtmlBody(String html) {
			this.html = html;
		}

		public String html() {
			return html;
		}

		@Override
		public String toString() {
			return "[HTML]\n" + html;
		}
	}

	public static class AmpHtmlBody implements Mail.Body {
		private final String html;

		public AmpHtmlBody(String html) {
			this.html = html;
		}

		public String html() {
			return html;
		}

		@Override
		public String toString() {
			return "[HTML]\n" + html;
		}
	}

	public static class MessageBody implements Mail.Body {
		private final String message;

		public MessageBody(String message) {
			this.message = message;
		}

		public String message() {
			return message;
		}

		@Override
		public String toString() {
			return "[MSG]\n" + message;
		}
	}

	public static class MessageHeadersBody implements Mail.Body {
		private final String headers;

		public MessageHeadersBody(String headers) {
			this.headers = headers;
		}

		public String headers() {
			return headers;
		}

		@Override
		public String toString() {
			return "[MSG-HEAD]\n" + headers;
		}
	}

	public static class MessageStatusBody implements Mail.Body {
		private final String status;

		public MessageStatusBody(String status) {
			this.status = status;
		}

		public String status() {
			return status;
		}

		@Override
		public String toString() {
			return "[MSG-STATUS]\n" + status;
		}
	}

	public static class CalendarBody implements Mail.Body {
		private final String script;
		private final String method;

		public CalendarBody(String script, String method) {
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
			return "[CALENDAR]\n" + script;
		}
	}

	public static class IcsBody implements Mail.Body {
		private final String script;
		private final String name;

		public IcsBody(String script, String name) {
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
			return "[ICS]\n" + script;
		}
	}

	public static class PdfBody implements Mail.Body {
		private final byte[] bytes;
		private final String name;

		public PdfBody(byte[] bytes, String name) {
			this.bytes = bytes;
			this.name = name;
		}

		public byte[] bytes() {
			return bytes;
		}

		public String name() {
			return name;
		}

		@Override
		public String toString() {
			return "[PDF]" + bytes.length;
		}
	}

	public static class DocxBody implements Mail.Body {
		private final byte[] bytes;
		private final String name;

		public DocxBody(byte[] bytes, String name) {
			this.bytes = bytes;
			this.name = name;
		}

		public byte[] bytes() {
			return bytes;
		}

		public String name() {
			return name;
		}

		@Override
		public String toString() {
			return "[DOCX]" + bytes.length;
		}
	}

	public static class XlsxBody implements Mail.Body {
		private final byte[] bytes;
		private final String name;

		public XlsxBody(byte[] bytes, String name) {
			this.bytes = bytes;
			this.name = name;
		}

		public byte[] bytes() {
			return bytes;
		}

		public String name() {
			return name;
		}

		@Override
		public String toString() {
			return "[XLSX]" + bytes.length;
		}
	}

	public static class ImageBody implements Mail.Body {
		private final byte[] bytes;
		private final Format format;

		enum Format {
			PNG, JPEG, GIF, HEIC
		}

		public ImageBody(byte[] bytes, Format format) {
			this.bytes = bytes;
			this.format = format;
		}

		public byte[] bytes() {
			return bytes;
		}

		public Format format() {
			return format;
		}

		@Override
		public String toString() {
			return "[IMAGE-" + format + "]" + bytes.length;
		}
	}

	public static class VideoBody implements Mail.Body {
		private final byte[] bytes;
		private final Format format;

		enum Format {
			MP4
		}

		public VideoBody(byte[] bytes, Format format) {
			this.bytes = bytes;
			this.format = format;
		}

		public byte[] bytes() {
			return bytes;
		}

		public Format format() {
			return format;
		}

		@Override
		public String toString() {
			return "[VIDEO-" + format + "]" + bytes.length;
		}
	}

	public static class MultiMixBody implements Mail.Body {

		private final Collection<? extends Mail.Body> bodies;

		public MultiMixBody(Collection<? extends Mail.Body> bodies) {
			this.bodies = bodies;
		}

		public Collection<? extends Mail.Body> bodies() {
			return bodies;
		}

		@Override
		public String toString() {
			return "[MULTI-MIX]" + bodies.size() + "\n"
					+ bodies.stream().map(Object::toString).collect(Collectors.joining("\n-----\n"));
		}
	}

	public static class MultiAltBody implements Mail.Body {

		private final Collection<? extends Mail.Body> bodies;

		public MultiAltBody(Collection<? extends Mail.Body> bodies) {
			this.bodies = bodies;
		}

		public Collection<? extends Mail.Body> bodies() {
			return bodies;
		}

		@Override
		public String toString() {
			return "[MULTI-ALT]" + bodies.size() + "\n"
					+ bodies.stream().map(Object::toString).collect(Collectors.joining("\n-----\n"));
		}
	}

	public static class MultiRelBody implements Mail.Body {

		private final Collection<? extends Mail.Body> bodies;

		public MultiRelBody(Collection<? extends Mail.Body> bodies) {
			this.bodies = bodies;
		}

		public Collection<? extends Mail.Body> bodies() {
			return bodies;
		}

		@Override
		public String toString() {
			return "[MULTI-MIX]" + bodies.size() + "\n"
					+ bodies.stream().map(Object::toString).collect(Collectors.joining("\n-----\n"));
		}
	}

	public static class MultiRepBody implements Mail.Body {

		private final Collection<? extends Mail.Body> bodies;

		public MultiRepBody(Collection<? extends Mail.Body> bodies) {
			this.bodies = bodies;
		}

		public Collection<? extends Mail.Body> bodies() {
			return bodies;
		}

		@Override
		public String toString() {
			return "[MULTI-MIX]" + bodies.size() + "\n"
					+ bodies.stream().map(Object::toString).collect(Collectors.joining("\n-----\n"));
		}
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
