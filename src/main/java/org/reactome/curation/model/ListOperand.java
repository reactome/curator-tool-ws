package org.reactome.curation.model;

/**
 * A list of operands that are used to query instances.
 */
public enum ListOperand {
    
    EQUAL,
    NOT_EQUAL,
    CONTAINS,
    IS_NULL,
    IS_NOT_NULL,
    REGEX;
 
    public static ListOperand map(String text) {
        text = text.trim().toLowerCase();
        switch(text) {
            case "equal": return EQUAL;
            case "not_equal":
            case "not equal":
                return NOT_EQUAL;
            case "contains":
                return CONTAINS;
            case "regex":
                return REGEX;
            case "is_null": 
            case "is null": 
                return IS_NULL;
            case "is_not_null": 
            case "is not null": 
                return IS_NOT_NULL;
           
        }
        return null;
    }

}
