package org.reactome.curation.model;

/**
 * A list of operands that are used to query instances.
 */
public enum ListOperand {
    
    EQUAL,
    NOT_EQUAL,
    CONTAIN,
    IS_NULL,
    IS_NOT_NULL;
 
    public static ListOperand map(String text) {
        text = text.toLowerCase();
        switch(text) {
            case "equal": return EQUAL;
            case "not_equal": return NOT_EQUAL;
            case "contain": return CONTAIN;
            case "is_null": return IS_NULL;
            case "is_not_null": return IS_NOT_NULL;
        }
        return null;
    }

}
