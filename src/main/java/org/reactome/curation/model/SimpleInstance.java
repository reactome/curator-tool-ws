package org.reactome.curation.model;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Similar to SimpleSchemaClass, this is a a very simple port of the original GKInstance in Java MySQL API 
 * to model the instance for the web frontend for easy parsing and tight control.
 * @author wug
 *
 */
@Data
@NoArgsConstructor
@JsonInclude(Include.NON_NULL)
public class SimpleInstance {
    
    private Long dbId;
    private String displayName;
    private String schemaClassName;
    private Map<String, Object> attributes;

    public void setAttribute(String attributeName, Object value) {
        if (attributes == null)
            attributes = new HashMap<>();
        attributes.put(attributeName, value);
    }
    
}
