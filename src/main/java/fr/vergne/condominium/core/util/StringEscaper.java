package fr.vergne.condominium.core.util;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * A {@link StringEscaper} supports the (un)escaping of characters within a
 * {@link String}. To create a {@link StringEscaper} easily, call
 * {@link StringEscaper.Builder#create()}.
 */
public interface StringEscaper {
	/**
	 * Escape the given content.
	 * 
	 * @param content the content to escape
	 * @return the escaped content
	 */
	String escape(String content);

	/**
	 * Remove the escaping of the given content.
	 * 
	 * @param escapedContent the escaped content
	 * @return the non-escaped content
	 */
	String unescape(String escapedContent);

	/**
	 * A builder root interface for {@link StringEscaper}. To remain with a
	 * consistent building process, prefer to call {@link Builder#create()} rather
	 * than implementing your own.
	 */
	interface Builder {

		/**
		 * Returns a new {@link StringEscaper} builder instance.
		 * 
		 * @return a new {@link StringEscaper} builder instance
		 */
		public static Builder.Escaper create() {
			List<Parser.Factory> parserFactories = new LinkedList<>();
			return new Builder.Escaper() {

				@Override
				public Builder.Loop escapeWith(char escapeChar) {
					Parser.Factory baseParserFactory = Parser.Factory.forBase(escapeChar);
					return new Builder.Loop() {

						@Override
						public Builder.Loop whenEnclosedIn(char delimiterChar,
								UnaryOperator<Builder.ForEnclosing> subBuilding) {
							Map<Character, Character> replacements = new HashMap<>();
							subBuilding.apply(new Builder.ForEnclosing() {

								@Override
								public Builder.ForEnclosing replace(char replacedChar, char replacementChar) {
									// TODO Support replacements out of enclosing too
									Character oldReplacementChar = replacements.put(replacedChar, replacementChar);
									if (oldReplacementChar != null && oldReplacementChar != replacementChar) {
										throw new IllegalArgumentException("Character '" + replacedChar
												+ "' already replaced by '" + oldReplacementChar
												+ "', cannot replace by '" + replacementChar + "'");
									}
									return this;
								}
							});
							parserFactories.add(Parser.Factory.forEnclosing(escapeChar, delimiterChar, replacements));
							return this;
						}

						@Override
						public StringEscaper build() {
							return new StringEscaper() {

								@Override
								public String escape(String content) {
									StringReader reader = new StringReader(content);
									StringWriter writer = new StringWriter();

									List<Parser.ForEscape> parsers = Stream
											.concat(parserFactories.stream(), Stream.of(baseParserFactory))//
											.map(factory -> factory.createEscapeParser(writer))//
											.toList();

									int read;
									try {
										while ((read = reader.read()) != -1) {
											if (read == codePointOf(escapeChar)) {
												writer.write(escapeChar);
												writer.write(escapeChar);
											} else {
												for (Parser.ForEscape parser : parsers) {
													if (parser.parses(read)) {
														break;
													}
												}
											}
										}
									} catch (IOException cause) {
										throw new RuntimeException("Cannot read: " + content, cause);
									}
									return writer.toString();
								}

								@SuppressWarnings("resource") // Closing resources on String has no effect
								@Override
								public String unescape(String escapedContent) {
									StringReader reader = new StringReader(escapedContent);
									StringWriter writer = new StringWriter();

									List<Parser.ForUnescape> parsers = Stream
											.concat(parserFactories.stream(), Stream.of(baseParserFactory))//
											.map(factory -> factory.createUnescapeParser(writer))//
											.toList();

									int read;
									try {
										while ((read = reader.read()) != -1) {
											if (read == codePointOf(escapeChar)) {
												try {
													read = reader.read();
												} catch (IOException cause) {
													throw new RuntimeException("Cannot read: " + escapedContent, cause);
												}
												if (read == codePointOf(escapeChar)) {
													writer.write(escapeChar);
												} else {
													for (Parser.ForUnescape parser : parsers) {
														if (parser.parsesEscaped(read)) {
															break;
														}
													}
												}
											} else {
												for (Parser.ForUnescape parser : parsers) {
													if (parser.parses(read)) {
														break;
													}
												}
											}
										}
									} catch (IOException cause) {
										throw new RuntimeException("Cannot read: " + escapedContent, cause);
									}
									return writer.toString();
								}
							};
						}
					};
				}
			};
		}

		/**
		 * First mandatory step of the {@link Builder} to escape all the required
		 * characters.
		 */
		interface Escaper {
			/**
			 * Define the character used for escaping. Any character defined to be escaped
			 * will be escaped with this character. If this escaping character occurs within
			 * the content to escape, it will also be escaped with itself to avoid confusion
			 * with a non escaped one (especially for {@link StringEscaper#unescape(String)
			 * unescaping}).
			 * 
			 * @param escapeChar the character used for escaping others
			 * @return the builder for the next operations
			 */
			Loop escapeWith(char escapeChar);
		}

		/**
		 * Repetitive step of the {@link Builder}.
		 */
		interface Loop {
			/**
			 * Defines the behavior to have within an enclosing delimited by the same
			 * character.
			 * 
			 * @param delimiterChar the delimiting character of the enclosing
			 * @param subBuilding   the definition of the operations to do within such
			 *                      enclosing
			 * @return this instance for chained calls
			 */
			Loop whenEnclosedIn(char delimiterChar, UnaryOperator<Builder.ForEnclosing> subBuilding);

			/**
			 * Build the {@link StringEscaper} that fulfills the definition stored in this
			 * builder.
			 * 
			 * @return a new {@link StringEscaper}
			 */
			StringEscaper build();
		}

		/**
		 * {@link Builder} specialization for
		 * {@link Builder.Loop#whenEnclosedIn(char, UnaryOperator) enclosings}.
		 */
		interface ForEnclosing {
			/**
			 * Escape a character while replacing it in the escaped content. The replacement
			 * helps to deal with the occurrences of this character out of the enclosing
			 * without confusing them.
			 * 
			 * @param replacedChar    the character to escape
			 * @param replacementChar the character to use once escaped
			 * @return this instance for chained calls
			 */
			ForEnclosing replace(char replacedChar, char replacementChar);
		}
	}

	/**
	 * Utility parsing definitions to help implement a {@link StringEscaper}.
	 */
	interface Parser {
		/**
		 * Parser used during {@link StringEscaper#escape(String)}.
		 */
		interface ForEscape {
			/**
			 * Try to parse the given code point.
			 * 
			 * @param read the read code point
			 * @return <code>true</code> if this parser can parse this code point,
			 *         <code>false</code> otherwise
			 */
			boolean parses(int read);
		}

		/**
		 * Parser used during {@link StringEscaper#unescape(String)}.
		 */
		interface ForUnescape {
			/**
			 * Try to parse the given code point, considering that it is a <b>non-escaped
			 * character</b>.
			 * 
			 * @param read the read code point
			 * @return <code>true</code> if this parser can parse this code point,
			 *         <code>false</code> otherwise
			 */
			boolean parses(int read);

			/**
			 * Try to parse the given code point, considering that it is an <b>escaped
			 * character</b>.
			 * 
			 * @param read the read code point
			 * @return <code>true</code> if this parser can parse this code point,
			 *         <code>false</code> otherwise
			 */
			boolean parsesEscaped(int read);
		}

		/**
		 * Factory interface for various {@link StringEscaper} parsers.
		 */
		interface Factory {
			/**
			 * Creates the required {@link Parser.ForEscape}.
			 * 
			 * @param writer the {@link StringWriter} to write into
			 * @return a new {@link Parser.ForEscape}
			 */
			Parser.ForEscape createEscapeParser(StringWriter writer);

			/**
			 * Creates the required {@link Parser.ForUnescape}.
			 * 
			 * @param writer the {@link StringWriter} to write into
			 * @return a new {@link Parser.ForUnescape}
			 */
			Parser.ForUnescape createUnescapeParser(StringWriter writer);

			/**
			 * Creates a factory which provides parsers offering the minimal required
			 * capability.
			 * 
			 * @param escapeChar the escaping character
			 * @return a minimal {@link Parser.Factory}
			 */
			private static Parser.Factory forBase(char escapeChar) {
				return new Parser.Factory() {
					@Override
					public Parser.ForEscape createEscapeParser(StringWriter writer) {
						return new Parser.ForEscape() {
							@Override
							public boolean parses(int read) {
								writer.write(read);
								return true;
							}
						};
					}

					@Override
					public Parser.ForUnescape createUnescapeParser(StringWriter writer) {
						return new Parser.ForUnescape() {
							@Override
							public boolean parses(int read) {
								writer.write(read);
								return true;
							}

							@Override
							public boolean parsesEscaped(int read) {
								if (read == -1) {
									throw new IllegalArgumentException(
											"Missing escaped character after: " + escapeChar);
								} else {
									throw new IllegalArgumentException(
											"Unsupported escaped character: " + Character.toString(read));
								}
							}
						};
					}
				};
			}

			/**
			 * Creates a factory which provides parsers to deal with contents enclosed
			 * between specific characters.
			 * 
			 * @param escapeChar    the escaping character
			 * @param delimiterChar the delimiting character of the enclosing
			 * @param replacements  the characters to escape and replace in such an
			 *                      enclosing
			 * @return a {@link Parser.Factory} dealing with such an enclosing
			 */
			private static Parser.Factory forEnclosing(char escapeChar, char delimiterChar,
					Map<Character, Character> replacements) {
				if (replacements.isEmpty()) {
					throw new IllegalArgumentException("Nothing to do when enclosed in '" + delimiterChar + "'");
				}
				return new Parser.Factory() {
					@Override
					public Parser.ForEscape createEscapeParser(StringWriter writer) {
						return new Parser.ForEscape() {
							boolean isEnclosed = false;

							@Override
							public boolean parses(int read) {
								if (read == codePointOf(delimiterChar)) {
									isEnclosed = !isEnclosed;
									writer.write(read);
									return true;
								} else if (isEnclosed) {
									return replacements.entrySet().stream()//
											.filter(entry -> read == codePointOf(entry.getKey()))//
											.findFirst()//
											.map(entry -> {
												writer.write(escapeChar);
												writer.write(entry.getValue());
												return true;
											})//
											.orElse(false);
								}
								return false;
							}
						};
					}

					@Override
					public Parser.ForUnescape createUnescapeParser(StringWriter writer) {
						return new Parser.ForUnescape() {
							private boolean isEnclosed = false;

							@Override
							public boolean parses(int read) {
								if (read == codePointOf(delimiterChar)) {
									isEnclosed = !isEnclosed;
									writer.write(read);
									return true;
								} else {
									return false;
								}
							}

							@Override
							public boolean parsesEscaped(int read) {
								if (isEnclosed) {
									return replacements.entrySet().stream()//
											.filter(entry -> read == codePointOf(entry.getValue()))//
											.findFirst()//
											.map(entry -> {
												writer.write(entry.getKey());
												return true;
											})//
											.orElse(false);
								} else {
									return false;
								}
							}
						};
					}
				};
			}
		}
	}

	private static int codePointOf(char c) {
		return String.valueOf(c).codePointAt(0);
	}
}
