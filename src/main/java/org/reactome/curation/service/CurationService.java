package org.reactome.curation.service;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.neo4j.cypherdsl.core.internal.SchemaNames;
import org.reactome.curation.model.CurationAttribute;
import org.reactome.curation.model.SimpleSchemaClass;
import org.reactome.curation.repository.CurationRepository;
//import org.reactome.server.graph.aop.LazyFetchAspect;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.result.SchemaClassCount;
import org.reactome.server.graph.repository.AdvancedDatabaseObjectRepository;
import org.reactome.server.graph.repository.SchemaRepository;
import org.reactome.server.graph.service.helper.AttributeClass;
import org.reactome.server.graph.service.helper.AttributeProperties;
import org.reactome.server.graph.service.helper.SchemaNode;
import org.reactome.server.graph.service.util.DatabaseObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Data;

@Data
@Service
//@NoArgsConstructor
//@EnableScheduling
//@EnableAsync
//@EntityScan({"org.reactome.server.graph.domain.model"})
//@EnableNeo4jRepositories({"org.reactome.server.graph.repository", "org.reactome.curation.repository"})
@SuppressWarnings("unchecked")
public class CurationService {
    private static final Logger logger = LoggerFactory.getLogger(CurationService.class);
    
    private Map<String, List<CurationAttribute>> clsName2Attributes;
    // A map for quick search
    private Map<String, Map<String, CurationAttribute>> clsName2attName2Attribute;
    // Cache the class tree for quick service
    private SimpleSchemaClass schemaClassTree;
    
    // For queries
    @Autowired
    private AdvancedDatabaseObjectRepository objectRepository;
    // To get the class attributes
//    @Autowired
//    private DatabaseObjectUtils databaseObjectUtils;
    // To handle some schema class related stuff
    @Autowired
    private SchemaRepository schemaRepository;
    @Autowired
    private CurationRepository curationRepository;
    
    public CurationService() {
        // Load clsName2Attributes to avoid any thread issue: clsName2Attributes
        // may be loaded multiple times unnecessarily.
        try {
            logger.info("Loading clsName2Attributes...");
            clsName2Attributes = loadClsName2Attributes();
            clsName2attName2Attribute = initMapForClassDefinitions(clsName2Attributes);
            logger.info("Done loading.");
        }
        catch(Exception e) {
            logger.info("Cannot load clsName2Attributes: " + e.getMessage(), e);
        }
    }
    
    private Map<String, Map<String, CurationAttribute>> initMapForClassDefinitions(Map<String, List<CurationAttribute>> clsName2Attributes) {
        Map<String, Map<String, CurationAttribute>> clsName2attName2Att = new HashMap<>();
        for (String clsName : clsName2Attributes.keySet()) {
            List<CurationAttribute> attributes = clsName2Attributes.get(clsName);
            Map<String, CurationAttribute> attName2Att = attributes.stream()
                    .collect(Collectors.toMap(CurationAttribute::getName, Function.identity()));
            clsName2attName2Att.put(clsName, attName2Att);
        }
        return clsName2attName2Att;
    }
    
    public DatabaseObject findById(Long dbId) {
       return objectRepository.findById(dbId, 1);
    }
    
    /**
     * Store a new DatabaseObject into a repository.
     * @param obj
     * @return
     * @throws Exception
     */
    public Long store(DatabaseObject obj) throws Exception {
        return curationRepository.store(obj);
    }
    
    public List<CurationAttribute> getAttributes(String clsName) throws Exception {
        if (clsName2Attributes == null) {
            logger.error("clsName2Attributes is not initialized.");
            return Collections.EMPTY_LIST; // Just in case
        }
        List<CurationAttribute> attributes = clsName2Attributes.get(clsName);
        if (attributes == null)
            return Collections.EMPTY_LIST;
        CurationAttribute any = attributes.stream().findAny().get();
        if (any.getProperties() != null)
            return attributes; // Loaded
        // Need to load attributes if needed
        Set<AttributeProperties> properties = DatabaseObjectUtils.getAttributeTable(clsName);
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

    /**
     * Update an attribute value for the passed DatabaseObject. The DatabaseObject should have
     * updated value already. 
     * @param o
     * @param attName
     * @return
     * @throws NoSuchMethodException
     */
    //TODO: Consider moving this method to some repository. 
    public boolean update(DatabaseObject o,
                          String attName) throws Exception {
        DatabaseObject saved = findById(o.getDbId());
        if (saved == null) {
            logger.error("Updated an object that doesn't exist. dbId: " + o.getDbId());
            throw new IllegalArgumentException(o + " doesn't exist.");
        }

        // TODO: move to a string utility class
        // Find method called set{AttName} (e.g. setHasEvent, or setText, setName)
        Class<?> classOfObject = o.getClass();
        String attributeName = attName.substring(0, 1).toUpperCase() + attName.substring(1);
        Method setMethod = classOfObject.getMethod("set" + attributeName, List.class);
        Method getMethod = classOfObject.getMethod("get" + attributeName);
        Object value = getMethod.invoke(o);
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
        setMethod.invoke(saved, value);
        curationRepository.store(saved);
        return true;
    }

    private boolean isRelationship(Object o, String attName) {
        if (clsName2attName2Attribute == null) {
            logger.error("clsName2attName2Attribute is not initialized.");
            throw new IllegalStateException("clsName2attName2Attribute is not initialized.");
        }
        Map<String, CurationAttribute> attName2Att = clsName2attName2Attribute.get(o.getClass().getSimpleName());
        if (attName2Att == null || !attName2Att.keySet().contains(attName))
            throw new IllegalArgumentException(attName + " is not defined in " + o.getClass().getCanonicalName());
        CurationAttribute att = attName2Att.get(attName);
        List<AttributeClass> attClasses = att.getProperties().getAttributeClasses();
        AttributeClass attCls = attClasses.stream().findAny().get();
        return attCls.isValueTypeDatabaseObject();
    }
    
    public SimpleSchemaClass loadSchemaClassTree() throws Exception {
        if (schemaClassTree == null) {
            // Load it 
            InputStream is = getClass().getClassLoader().getResourceAsStream("schema_classes_tree.json");
            ObjectMapper mapper = new ObjectMapper();
            schemaClassTree = mapper.readValue(is, SimpleSchemaClass.class);
        }
        // In the editing env, the numbers of instances may change even for the same session
        // Therefore, this needs to be refreshed for each call.
        attachInstanceCounts(schemaClassTree);
        return schemaClassTree;
    }
    
    /**
     * A helper method to count instances for each class and then add to its respective 
     * class object.
     * @param root the root class, i.e., DatabaseObject.
     * @throws Exception
     */
    private void attachInstanceCounts(SimpleSchemaClass root) throws Exception {
        Collection<SchemaClassCount> clsCounts = schemaRepository.getSchemaClassCounts();
        SchemaNode rootNode = DatabaseObjectUtils.getGraphModelTree(clsCounts);
        // Traversal the tree to assign the counts from the above collection
        Map<String, SimpleSchemaClass> name2class = new HashMap<>();
        traversalTree(root, name2class);
        // Copy the count from rootNode
        copyCount(rootNode, name2class);
    }
    
    private void copyCount(SchemaNode schemaNode, Map<String, SimpleSchemaClass> name2class) {
        SimpleSchemaClass schemaClass = name2class.get(schemaNode.getClassName());
        if (schemaClass != null) {
            schemaClass.setCount(schemaNode.getCount());
        }
        if (schemaNode.getChildren() == null || schemaNode.getChildren().size() == 0)
            return;
        for (SchemaNode child : schemaNode.getChildren())
            copyCount(child, name2class);
    }
    
    private void traversalTree(SimpleSchemaClass cls, Map<String, SimpleSchemaClass> name2class) {
        name2class.put(cls.getName(), cls);
        if (cls.getChildren() == null || cls.getChildren().size() == 0)
            return;
        for (SimpleSchemaClass child : cls.getChildren())
            traversalTree(child, name2class);
    }

    public Long getNextDbId(){
        return curationRepository.nextDbId();
    }

    public List<String> getSchemaClasses() {
        return curationRepository.getSchemaClasses();
    }
}
