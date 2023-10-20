package org.reactome.curation.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Similar to SimpleSchemaClass, this is a a very simple port of the original
 * GKInstance in Java MySQL API to model the instance for the web frontend for
 * easy parsing and tight control.
 * 
 * @author wug
 *
 */
@Data
@NoArgsConstructor
@JsonInclude(Include.NON_NULL)
@JsonDeserialize(using = SimpleInstanceDeserializer.class)
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

class SimpleInstanceDeserializer extends JsonDeserializer<SimpleInstance> {

    @Override
    public SimpleInstance deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
            throws IOException {
        ObjectNode objectNode = jsonParser.getCodec().readTree(jsonParser);
        SimpleInstance simpleInstance = (SimpleInstance) deserializeNode(objectNode);
        //TODO: We may need a normalization process for cases like the same instance is referred more than once 
        // here. Make sure they are the same object.
        return simpleInstance;
    }

    private Object deserializeNode(JsonNode node) {
        if (node.isObject()) {
            SimpleInstance instance = new SimpleInstance();
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String fieldName = field.getKey();
                JsonNode fieldValue = field.getValue();
                if ("dbId".equals(fieldName)) {
                    instance.setDbId(fieldValue.asLong());
                } else if ("displayName".equals(fieldName)) {
                    instance.setDisplayName(fieldValue.asText());
                } else if ("schemaClassName".equals(fieldName)) {
                    instance.setSchemaClassName(fieldValue.asText());
                } else if ("attributes".equals(fieldName)) {
                    // attributes is an object
                    Iterator<Map.Entry<String, JsonNode>> attributeFields = fieldValue.fields();
                    while (attributeFields.hasNext()) {
                        Map.Entry<String, JsonNode> attributeField = attributeFields.next();
                        String attributeName = attributeField.getKey();
                        JsonNode attributeValue = attributeField.getValue();
                        if (attributeValue.isArray()) {
                            List<Object> attributeValues = new ArrayList<>();
                            for (JsonNode subNode : attributeValue) {
                                attributeValues.add(deserializeNode(subNode));
                            }
                            instance.setAttribute(attributeName, attributeValues);
                        } 
                        else {
                            instance.setAttribute(attributeName, deserializeNode(attributeValue));
                        }
                    }
                }
            }
            return instance;
        } 
        else if (node.isTextual()) {
            return node.asText();
        } 
        else if (node.isNumber()) {
            return node.numberValue();
        }
        else if (node.isBoolean())
            return node.booleanValue();
        return null;
    }

}
