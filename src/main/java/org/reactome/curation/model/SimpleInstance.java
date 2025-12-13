package org.reactome.curation.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.reactome.server.graph.domain.model.DatabaseObject;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Similar to SimpleSchemaClass, this is a a very simple port of the original
 * GKInstance in Java MySQL API to model the instance for the web frontend for
 * easy parsing and tight control.
 * 
 * TODO: This is implemented as a subclass to DatabaseObject for convenience. However, more
 * tests may be needed to make sure there is no side effects existing. We may consider 
 * to use this class completely without going to DatabaseObject, just like Java API to
 * MySQL database.
 * 
 * @author wug
 *
 */
@JsonInclude(Include.NON_NULL)
@JsonDeserialize(using = SimpleInstanceDeserializer.class)
@SuppressWarnings("serial")
public class SimpleInstance extends DatabaseObject {

//    private Long dbId;
//    private String displayName;
    private boolean isStructureModified;
    private String schemaClassName;
    private Map<String, Object> attributes;
    private List<String> modifiedAttributes; // Names of attributes that have been updated.
    // Use this optional information to create InstanceEdit for the created or modified slot
    private Long defaultPersonId;
    // Updated new instances after committed for the front-end
    private Map<Long, Long> newInstOld2NewId;
    
    public Map<Long, Long> getNewInstOld2NewId() {
        return newInstOld2NewId;
    }

    public boolean isStructureModified() {
        return isStructureModified;
    }

    public void setStructureModified(boolean isStructureModified) {
        this.isStructureModified = isStructureModified;
    }

    public void setNewInstOld2NewId(Map<Long, Long> newInstOld2NewId) {
        this.newInstOld2NewId = newInstOld2NewId;
    }

    public void setDefaultPersonId(Long id) {
        this.defaultPersonId = id;
    }
    
    public Long getDefaultPersonId() {
        return this.defaultPersonId;
    }

    public void setAttribute(String attributeName, Object value) {
        if (attributes == null)
            attributes = new HashMap<>();
        attributes.put(attributeName, value);
    }
    
    public List<String> getModifiedAttributes() {
        return modifiedAttributes;
    }

    public void setModifiedAttributes(List<String> modifiedAttributes) {
        this.modifiedAttributes = modifiedAttributes;
    }

    public Object getAttribute(String attributeName) {
        if (attributes == null)
            return null;
        return attributes.get(attributeName);
    }

    public String getSchemaClassName() {
        return schemaClassName;
    }

    public void setSchemaClassName(String schemaClassName) {
        this.schemaClassName = schemaClassName;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }
    
    /**
     * Clone this SimpleInstance with dbId, displayName, and schemaClassName. 
     * @return
     */
    public SimpleInstance cloneInstance() {
        SimpleInstance rtn = new SimpleInstance();
        rtn.setDbId(this.getDbId());
        rtn.setDisplayName(this.getDisplayName());
        rtn.setSchemaClassName(this.getSchemaClassName());
        rtn.setStId(this.getStId());
        if (getAttributes() != null)
            rtn.setAttributes(new HashMap<>(getAttributes()));
        return rtn;
    }
    
    public Class<? extends DatabaseObject> getGraphModelClass() {
        String schemaClassName = getSchemaClassName();
        if (schemaClassName == null)
            return null; 
        // Assumed all graph model classes are in the same package
        String packageName = DatabaseObject.class.getPackageName();
        String clsName = packageName + "." + schemaClassName;
        try {
            Class<?> rawClass = Class.forName(clsName);
            if (DatabaseObject.class.isAssignableFrom(rawClass)) {
                @SuppressWarnings("unchecked")
                Class<? extends DatabaseObject> modelClass = (Class<? extends DatabaseObject>) rawClass;
                return modelClass;
            } else {
                throw new IllegalArgumentException(clsName + " is not a subclass of DatabaseObject.");
            }
        }
        catch(ClassNotFoundException e) {
            throw new RuntimeException("Class not found: " + e.getMessage(), e);
        }
    }
    
    @Override
    public String toString() {
        // Generate a JSON-like string for the instance for the front-end
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"dbId\":").append(getDbId()).append(",");
        sb.append("\"displayName\":\"").append(getDisplayName()).append("\",");
        // We'd like to use simple name, SimpleInstance, here. 
        sb.append("\"schemaClassName\":\"").append(getSchemaClassName()).append("\"");
        sb.append("}");
        return sb.toString();
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
                } 
                else if ("defaultPersonId".equals(fieldName)) {
                    instance.setDefaultPersonId(fieldValue.asLong());
                }
                else if ("isStructureModified".equals(fieldName)) {
                    instance.setStructureModified(fieldValue.asBoolean());
                }
                else if ("modifiedAttributes".equals(fieldName)) {
                    // This should be an array
                    List<String> names = new ArrayList<>();
                    for (JsonNode subNode : fieldValue) {
                        names.add(deserializeNode(subNode) + "");
                    }
                    instance.setModifiedAttributes(names);
                }
                else if ("attributes".equals(fieldName)) {
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
