package org.reactome.curation.controller;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.reactome.curation.model.CurationAttribute;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.service.CurationService;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * This class is used to do a two away converting between a DatabaseObject and a SimpleInstance object
 * for the controller.
 * @author wug
 *
 */
@Component // So a Bean for this class can be auto-created.
public class DatabaseObjectInstanceConverter {
    
    @Autowired
    private CurationService curationService;
    
    public DatabaseObjectInstanceConverter() {    
    }
    
    /**
     * Convert a DatabaseObject object to a SimpleInstance object.
     * @param databaseObject
     * @return
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public SimpleInstance convert(DatabaseObject databaseObject) throws Exception {
        SimpleInstance instance = convertInShell(databaseObject);
        List<CurationAttribute> attributes = curationService.getAttributes(databaseObject.getClassName());
        for (CurationAttribute attribute : attributes) {
            // There is a bug with deletion. Disable it for the time being
            // These attributes don't have properties
            if (attribute.getProperties() == null)
                continue;
            String methodName = "get" + attribute.getName().substring(0, 1).toUpperCase() + attribute.getName().substring(1);
            Method method = databaseObject.getClass().getMethod(methodName);
            Object value = method.invoke(databaseObject);
            if (value == null)
                continue;
            Object convertedValue = null;
            // It actually returns a LinkedHashSet from the data model. Therefore, check
            // as collection
            if (value instanceof Collection<?>) {
                Collection<?> valueList = (Collection<?>) value;
                convertedValue = new ArrayList<>();
                for (Object value1 : valueList) {
                    if (value1 instanceof DatabaseObject) {
                        SimpleInstance convertedValue1 = convertInShell((DatabaseObject)value1);
                        ((List)convertedValue).add(convertedValue1);
                    }
                    else
                        ((List)convertedValue).add(value1); // Nothing needs to be done
                }
            }
            else {
                if (value instanceof DatabaseObject)
                    convertedValue = convertInShell((DatabaseObject)value);
                else
                    convertedValue = value;
            }
            instance.setAttribute(attribute.getName(), convertedValue);
        }
        return instance;
    }
    
    private SimpleInstance convertInShell(DatabaseObject databaseObject) {
        SimpleInstance instance = new SimpleInstance();
        instance.setDbId(databaseObject.getDbId());
        instance.setDisplayName(databaseObject.getDisplayName());
        instance.setSchemaClassName(databaseObject.getClassName());
        return instance;
    }

}
