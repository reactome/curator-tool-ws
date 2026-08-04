package org.reactome.curation.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.reactome.curation.model.ListOperand;

/**
 * Tests for the search key handling shared by listInstances and searchInstances:
 * literal versus regex pattern building, and the comma-escaped key list format.
 */
class SearchKeyUtilsTest {

    @Test
    void literalSearchMatchesMetacharactersAsText() {
        // The parentheses used to be treated as a capturing group, so an instance named
        // "Cyclin A (human)" could not be found by typing its own display name.
        String pattern = CuratorToolWSUtils.buildContainsPattern("Cyclin A (human)");
        assertTrue(Pattern.matches(pattern, "Cyclin A (human) [1234]"));
        assertFalse(Pattern.matches(pattern, "Cyclin A human"));
    }

    @Test
    void literalSearchIsCaseInsensitiveAndUnanchored() {
        String pattern = CuratorToolWSUtils.buildContainsPattern("tp53");
        assertTrue(Pattern.matches(pattern, "phosphorylated TP53 dimer"));
    }

    @Test
    void literalSearchAcceptsAnInvalidRegexAsText() {
        // A lone bracket is a syntax error as a pattern but a legitimate thing to type.
        String pattern = CuratorToolWSUtils.buildContainsPattern("TP53 [");
        assertTrue(Pattern.matches(pattern, "TP53 [1234]"));
    }

    @Test
    void regexSearchAnchorsAndStaysCaseInsensitive() {
        String pattern = CuratorToolWSUtils.buildRegexPattern("^TP5[34]$");
        assertTrue(Pattern.matches(pattern, "TP53"));
        assertTrue(Pattern.matches(pattern, "tp54"));
        assertFalse(Pattern.matches(pattern, "TP53 mutant"));
    }

    @Test
    void regexSearchCanOptBackIntoCaseSensitivity() {
        String pattern = CuratorToolWSUtils.buildRegexPattern("(?-i)^TP53$");
        assertTrue(Pattern.matches(pattern, "TP53"));
        assertFalse(Pattern.matches(pattern, "tp53"));
    }

    @Test
    void invalidRegexIsRejectedWithAnExplanation() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CuratorToolWSUtils.buildRegexPattern("TP53["));
        assertTrue(e.getMessage().contains("Invalid regular expression"));
    }

    @Test
    void searchKeysSplitOnUnescapedCommasOnly() {
        // A regex quantifier contains a comma, which used to split the key in two and
        // desynchronize it from the attribute and operand lists.
        assertEquals(Arrays.asList("a{2,3}", "^Cyclin"),
                CuratorToolWSUtils.splitSearchKeys("a{2\\,3},^Cyclin"));
    }

    @Test
    void searchKeysWithoutEscapesSplitAsBefore() {
        List<String> keys = CuratorToolWSUtils.splitSearchKeys("TP53,MDM2");
        assertEquals(Arrays.asList("TP53", "MDM2"), keys);
    }

    @Test
    void searchKeysKeepRegexBackslashes() {
        // Only ',' and '\' are escaped by the client: the \d of a regex must survive.
        assertEquals(Arrays.asList("\\d+", "x"), CuratorToolWSUtils.splitSearchKeys("\\d+,x"));
        // An escaped backslash unescapes to a single one.
        assertEquals(Arrays.asList("a\\,b"), CuratorToolWSUtils.splitSearchKeys("a\\\\\\,b"));
    }

    @Test
    void operandLabelsFromTheFrontendMapToOperands() {
        // The frontend sends its display labels verbatim, so "Not Equal" has to resolve.
        assertEquals(ListOperand.NOT_EQUAL, ListOperand.map("Not Equal"));
        assertEquals(ListOperand.MATCHES_REGEX, ListOperand.map("Matches Regex"));
        assertEquals(ListOperand.IS_NOT_NULL, ListOperand.map("IS NOT NULL"));
        assertEquals(ListOperand.CONTAINS, ListOperand.map("contains"));
        assertEquals(ListOperand.EQUAL, ListOperand.map("EQUAL"));
    }

    @Test
    void unknownOperandMapsToNull() {
        assertEquals(null, ListOperand.map("Sounds Like"));
        assertEquals(null, ListOperand.map(null));
    }
}
