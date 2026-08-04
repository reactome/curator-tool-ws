package org.reactome.curation.model;

/**
 * A list of operands that are used to query instances.
 */
public enum ListOperand {

    EQUAL,
    NOT_EQUAL,
    CONTAINS,
    MATCHES_REGEX,
    IS_NULL,
    IS_NOT_NULL;

    /**
     * Map the text used by a client to an operand. Both the enum-style names
     * (e.g. NOT_EQUAL) and the labels displayed by the frontend's search filter
     * (e.g. "Not Equal") are accepted, in any case, since the frontend sends its
     * display labels as-is.
     * @param text
     * @return null if the text doesn't name a known operand.
     */
    public static ListOperand map(String text) {
        if (text == null)
            return null;
        // Treat spaces and underscores the same so that "Not Equal", "not equal"
        // and "NOT_EQUAL" all resolve to the same operand.
        text = text.trim().toLowerCase().replace(' ', '_');
        switch(text) {
            case "equal": return EQUAL;
            case "not_equal": return NOT_EQUAL;
            case "contains": return CONTAINS;
            case "regex":
            case "matches_regex": return MATCHES_REGEX;
            case "is_null": return IS_NULL;
            case "is_not_null": return IS_NOT_NULL;
        }
        return null;
    }

}
