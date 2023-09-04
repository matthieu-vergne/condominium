package fr.vergne.condominium.core.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StringEscaperBuilderTest {

	static Stream<Arguments> originalAndEscaped() {
		StringEscaper escaper = StringEscaper.Builder.create()//
				.escapeWith('$')//
				.whenEnclosedIn('|', sub -> sub.replace('x', 'y'))//
				.whenEnclosedIn('#', sub -> sub.replace('v', 'w'))//
				.build();
		return Stream.of(//
				arguments(escaper, "foo", "foo"), //

				arguments(escaper, "$foo", "$$foo"), //
				arguments(escaper, "foo$", "foo$$"), //
				arguments(escaper, "foo$bar", "foo$$bar"), //

				arguments(escaper, "foo x bar", "foo x bar"), //
				arguments(escaper, "|foo x bar|", "|foo $y bar|"), //
				arguments(escaper, "foo|x|bar", "foo|$y|bar"), //
				arguments(escaper, "foo|x|foobar|x|bar", "foo|$y|foobar|$y|bar"), //

				arguments(escaper, "foo|x|foobar|v|bar", "foo|$y|foobar|v|bar"), //
				arguments(escaper, "foo#x#foobar#v#bar", "foo#x#foobar#$w#bar"), //

				arguments(escaper, "$|$|#$#", "$$|$$|#$$#")//
		);
	}

	@ParameterizedTest(name = "\"{1}\" to \"{2}\"")
	@MethodSource("originalAndEscaped")
	void testEscapeReturnsEscaped(StringEscaper escaper, String original, String escaped) {
		assertThat(escaper.escape(original), is(equalTo(escaped)));
	}

	@ParameterizedTest(name = "\"{1}\"")
	@MethodSource("originalAndEscaped")
	void testEscapeThenUnescapeReturnsOriginal(StringEscaper escaper, String original) {
		assertThat(escaper.unescape(escaper.escape(original)), is(equalTo(original)));
	}

	static Stream<Arguments> testUnescapeInvalidEscapedContentFails() {
		return Stream.of(//
				arguments('/', "/", "Missing escaped character after: /"), //
				arguments('$', "$", "Missing escaped character after: $"), //
				arguments('$', "$foo", "Unsupported escaped character: f"), //
				arguments('$', "foo$bar", "Unsupported escaped character: b"), //
				arguments('$', "foo$", "Missing escaped character after: $")//
		);
	}

	@ParameterizedTest(name = "Cannot unescape when escaped with ''{0}'' in \"{1}\" because \"{2}\"")
	@MethodSource
	void testUnescapeInvalidEscapedContentFails(char escapeChar, String escapedContent, String exceptionMessage) {
		StringEscaper escaper = StringEscaper.Builder.create().escapeWith(escapeChar).build();
		var exception = assertThrows(IllegalArgumentException.class, () -> escaper.unescape(escapedContent));
		assertThat(exception.getMessage(), is(equalTo(exceptionMessage)));
	}

	static Stream<Arguments> testUnescapeEscapedContentOutOfItsEnclosingFails() {
		return Stream.of(//
				// TODO Prefer to say that character is not enclosed in |...| ?
				arguments('$', '|', 'x', 'y', "$y", "Unsupported escaped character: y"), //
				arguments('$', '|', 'x', 'y', "$y|foo|bar", "Unsupported escaped character: y"), //
				arguments('$', '|', 'x', 'y', "foo|bar|$y", "Unsupported escaped character: y")//
		);
	}

	@ParameterizedTest(name = "Cannot unescape ''{3}'' in \"{4}\" because \"{5}\" out of \"{1}...{1}\"")
	@MethodSource
	void testUnescapeEscapedContentOutOfItsEnclosingFails(char escapeChar, char delimiterChar, char replacedChar,
			char replacementChar, String escapedContent, String exceptionMessage) {
		StringEscaper escaper = StringEscaper.Builder.create()//
				.escapeWith(escapeChar)//
				.whenEnclosedIn(delimiterChar, sub -> sub.replace(replacedChar, replacementChar))//
				.build();
		var exception = assertThrows(IllegalArgumentException.class, () -> escaper.unescape(escapedContent));
		assertThat(exception.getMessage(), is(equalTo(exceptionMessage)));
	}

	@Test
	void testEnclosingWithoutDoingAnythingFails() {
		StringEscaper.Builder.Loop builder = StringEscaper.Builder.create().escapeWith('$');
		var exception = assertThrows(IllegalArgumentException.class, () -> builder.whenEnclosedIn('|', sub -> sub));
		assertThat(exception.getMessage(), is(equalTo("Nothing to do when enclosed in '|'")));
	}

	@Test
	void testEnclosingReplaceSameCharacterTwiceWithDifferentCharactersFails() {
		StringEscaper.Builder.Loop builder = StringEscaper.Builder.create().escapeWith('$');
		var exception = assertThrows(IllegalArgumentException.class,
				() -> builder.whenEnclosedIn('"', sub -> sub.replace('a', 'b').replace('a', 'c')));
		assertThat(exception.getMessage(), is(equalTo("Character 'a' already replaced by 'b', cannot replace by 'c'")));
	}

	@Test
	void testEnclosingReplaceSameCharacterTwiceWithSameCharacterSucceeds() {
		StringEscaper escaper = StringEscaper.Builder.create()//
				.escapeWith('$')//
				.whenEnclosedIn('|', sub -> sub//
						.replace('a', 'b')//
						.replace('a', 'b'))//
				.build();
		assertThat(escaper.escape("a|a|a"), is(equalTo("a|$b|a")));
	}
}
