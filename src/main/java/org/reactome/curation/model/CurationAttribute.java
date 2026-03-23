package org.reactome.curation.model;

import org.gk.schema.GKSchemaAttribute;
import org.reactome.server.graph.service.helper.AttributeProperties;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * This is a wrapper of AttributeProperties in package org.reactome.server.graph.service.helper 
 * from graph-core enhanced with curation tool specific information (e.g. categories and defined).
 * @author wug
 *
 */
@Data
@NoArgsConstructor
@JsonInclude(Include.NON_NULL)
public class CurationAttribute {
    
    private AttributeProperties properties;
    private DefiningType definingType;
    private Category category;
    private String name;
    
    public static enum Category {
        MANDATORY,
        REQUIRED,
        OPTIONAL,
        NOMANUALEDIT,
        NOTDEFINED;
        
        /**
         * Convert from an old integer based category to an Enum
         * @param index
         * @return
         */
        public static Category getCategory(int index) {
            switch(index) {
                case GKSchemaAttribute.MANDATORY : return MANDATORY;
                case GKSchemaAttribute.REQUIRED : return REQUIRED;
                case GKSchemaAttribute.OPTIONAL : return OPTIONAL;
                case GKSchemaAttribute.NOMANUALEDIT : return NOMANUALEDIT;
            }
            return NOTDEFINED;
        }
    }
    
    public static enum DefiningType {
         ALL_DEFINING,
         ANY_DEFINING,
         NONE_DEFINING,
         UNDEFINED;
        
        public static DefiningType getDefiningType(int index) {
            switch(index) {
                case GKSchemaAttribute.ALL_DEFINING : return ALL_DEFINING;
                case GKSchemaAttribute.ANY_DEFINING : return ANY_DEFINING;
                case GKSchemaAttribute.NONE_DEFINING : return NONE_DEFINING;
                case GKSchemaAttribute.UNDEFINED : return UNDEFINED;
            }
            throw new IllegalArgumentException("The index is not known: " + index);
        }
    }
    
    /**
     * Helper class to encapsulate defining attribute values with their metadata.
     */
    public static class DefiningAttributeValue {
        private final Object value;
        private final DefiningType definingType;
        private final boolean isReference;
        
        public DefiningAttributeValue(Object value, 
                                     DefiningType definingType,
                                     boolean isReference) {
            this.value = value;
            this.definingType = definingType;
            this.isReference = isReference;
        }
        
        public Object getValue() {
            return value;
        }
        
        public CurationAttribute.DefiningType getDefiningType() {
            return definingType;
        }
        
        public boolean isReference() {
            return isReference;
        }
    }
    
}
