package org.reactome.curation.service;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.neo4j.core.schema.Relationship;
import org.reactome.curation.model.CurationAttribute;
import org.reactome.curation.repository.CurationRepository;
//import org.reactome.server.graph.aop.LazyFetchAspect;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.result.QueryResultWrapper;
import org.reactome.server.graph.repository.AdvancedDatabaseObjectRepository;
import org.reactome.server.graph.service.helper.AttributeProperties;
import org.reactome.server.graph.service.helper.RelationshipDirection;
import org.reactome.server.graph.service.util.DatabaseObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Service
@NoArgsConstructor
//@EnableScheduling
//@EnableAsync
//@EntityScan({"org.reactome.server.graph.domain.model"})
//@EnableNeo4jRepositories({"org.reactome.server.graph.repository", "org.reactome.curation.repository"})
@SuppressWarnings("unchecked")
public class CurationService {
    private static final Logger logger = LoggerFactory.getLogger(CurationService.class);
    
    private Map<String, List<CurationAttribute>> clsName2Attributes;
    
    // For curation specific stuff
    @Autowired
    private CurationRepository curationRepository;
    // For queries
    @Autowired
    private AdvancedDatabaseObjectRepository objectRepository;
    // To get the class attributes
    @Autowired
    private DatabaseObjectUtils databaseObjectUtils;
    
    public DatabaseObject findById(Long dbId) {
       return objectRepository.findById(dbId, 1);
    }
    
    public List<CurationAttribute> getAttributes(String clsName) throws Exception {
        if (clsName2Attributes == null) {
            logger.info("Loading clsName2Attributes...");
            clsName2Attributes = loadClsName2Attributes();
            logger.info("Done loading.");
        }
        if (clsName2Attributes == null)
            return Collections.EMPTY_LIST; // Just in case
        List<CurationAttribute> attributes = clsName2Attributes.get(clsName);
        if (attributes == null)
            return Collections.EMPTY_LIST;
        CurationAttribute any = attributes.stream().findAny().get();
        if (any.getProperties() != null)
            return attributes; // Loaded
        // Need to load attributes if needed
        Set<AttributeProperties> properties = databaseObjectUtils.getAttributeTable(clsName);
        // For quick assignment
        Map<String, AttributeProperties> name2prop = properties.stream()
                .collect(Collectors.toMap(AttributeProperties::getName, Function.identity()));
        attributes.forEach(att -> att.setProperties(name2prop.get(att.getName())));
        return attributes;
    }
    
    private Map<String, List<CurationAttribute>> loadClsName2Attributes() throws Exception {
        // There is no need to add "resources" before the file name even though this file is placed inside
        // the resources folder. Spring should figure it out.
        InputStream is = getClass().getClassLoader().getResourceAsStream("curation_schema_attributes.json");
        ObjectMapper mapper = new ObjectMapper();
        TypeReference<HashMap<String, List<CurationAttribute>>> typeRef = new TypeReference<>(){};
        return mapper.readValue(is, typeRef);
    }

    public boolean update(DatabaseObject o,
                          String attName // hasEvent -> setHasEvent
                                        ) throws NoSuchMethodException {
        DatabaseObject saved = objectRepository.findById(o.getDbId(), RelationshipDirection.OUTGOING);
        if (saved == null)
            throw new IllegalArgumentException(o + " is not found!");

        // TODO: move to a string utility class
        // Find method called set{AttName} (e.g. setHasEvent, or setText, setName)
        Class classOfObject = o.getClass();
        String attributeName = attName.substring(0, 1).toUpperCase() + attName.substring(1);
        Method setMethod = classOfObject.getMethod("set" + attributeName, List.class);
        Method getMethod = classOfObject.getMethod("get" + attributeName);
        Object value; // could also always consider value a collection

        try {
            value = getMethod.invoke(o);
        } catch (InvocationTargetException | IllegalAccessException e) {
            throw new WebServerException("Method " + getMethod.getName() + " is not found on class " + o.getClass().getName(), e);
        }

        if(isRelationship(o, attName)){
            Collection<?> relationships = (Collection<?>) value;
            for(Object relationship : relationships){
                // Use references for physical entities, value may need to be queried
                DatabaseObject relationshipObject = (DatabaseObject) relationship;
                Long dbId = relationshipObject.getDbId();
                if(dbId == null){
                    // call save function with mint dbId logic called there
                }
            }
        }

        try {
            setMethod.invoke(saved, value);
            curationRepository.save(saved);
        } catch (InvocationTargetException | IllegalAccessException e) {
            throw new WebServerException("Method " + setMethod.getName() + " is not found on class " + o.getClass().getName(), e);
        }
        return true;
    }

    // TODO: make this function public in graph-core/ReflectionUtils
    public List<Field> getAllFields(List<Field> fields, Class<?> type) {
        fields.addAll(Arrays.asList(type.getDeclaredFields()));
        if (type.getSuperclass() != null && !type.getSuperclass().equals(Object.class)) {
            fields = getAllFields(fields, type.getSuperclass());
        }
        return fields;
    }

    public boolean isRelationship(Object o, String attName){
        List<Field> objectFields = getAllFields(new ArrayList<>(), o.getClass());
        for(Field field: objectFields){
            if(field.getAnnotation(Relationship.class) != null && field.getName().equals(attName)){
                Annotation annotation = field.getAnnotation(Relationship.class);
                logger.info(annotation.toString());
                return true;
            }
        }
        return false;
    }

    public Long getMaxDbId(){
        Long maxDbId = curationRepository.getMaxDbId();
        if(maxDbId != null) {return maxDbId;}
        else {return null;}
    }

    public Long generateDbId() {
        Long generatedDbId = curationRepository.generateDbId();
        // Using a random number to prevent curators getting same dbId
        Random random = new Random();
        Long randomLong = random.nextLong();
        Long newDbId = generatedDbId + randomLong;
        return newDbId;
    }

    public List<String> getSchema() {
        return curationRepository.getSchema();
    }
}
