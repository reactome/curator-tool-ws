package org.reactome.curation.service;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.config.CuratorToolEnv;
import org.reactome.curation.model.CurationAttribute;
import org.reactome.curation.model.InstanceList;
import org.reactome.curation.model.ListOperand;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.model.SimpleSchemaClass;
import org.reactome.curation.repository.CurationFileRepository;
import org.reactome.curation.repository.CurationRepository;
//import org.reactome.server.graph.aop.LazyFetchAspect;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.result.Referrals;
import org.reactome.server.graph.repository.AdvancedDatabaseObjectRepository;
import org.reactome.server.graph.service.helper.AttributeClass;
import org.reactome.server.graph.service.helper.AttributeProperties;
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
    @Autowired
    private CurationRepository curationRepository;
    // For file-based repository
    @Autowired
    private CurationFileRepository fileRepository;
    
    @Autowired
    private CuratorToolEnv toolEnv;
    
    // Helper with auto filling literature reference
    @Autowired
    private LiteratureReferenceAttributeAutoFiller lrFiller;
    
    
    public CurationService() {
        // Load clsName2Attributes to avoid any thread issue: clsName2Attributes
        // may be loaded multiple times unnecessarily.
        try {
            logger.info("Loading clsName2Attributes...");
            clsName2Attributes = loadClsName2Attributes();
            // TODO: Eliminate this hack to get round class name changes between curation and web schemas
            clsName2Attributes.put("ReactionLikeEvent", clsName2Attributes.get("ReactionlikeEvent"));
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
    
    public DatabaseObject commit(DatabaseObject obj) throws Exception {
        return curationRepository.commit(obj);
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
            Collection<?> relationships = (Collection<?>) value; // check if extends collection
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
    
    public boolean isInstanceType(String clsName, String attName) throws Exception {
        // Have to call this method first to make sure all attributes have been loaded.
        // In the server env, there should be no performance penality since the map 
        // should be loaded anyway
        this.getAttributes(clsName);
        Map<String, CurationAttribute> attName2Att = clsName2attName2Attribute.get(clsName);
        if (attName2Att == null || !attName2Att.keySet().contains(attName))
            throw new IllegalArgumentException(attName + " is not defined in " + clsName);
        CurationAttribute att = attName2Att.get(attName);
        AttributeProperties attProps = att.getProperties();
        List<AttributeClass> attClses = attProps.getAttributeClasses();
        for (AttributeClass attCls : attClses) {
            if (attCls.isValueTypeDatabaseObject())
                return true; // We will not mixed two types of attributes together
        }
        return false;
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
        countInstances(schemaClassTree);
        return schemaClassTree;
    }
    
    /**
     * Automatically fill the attributes of a LiteratureReference instance based on its PMID.
     * @param instance
     * @return
     * @throws Exception
     */
    public SimpleInstance fillLiteratureReference(SimpleInstance instance) throws Exception {
        if (!instance.getSchemaClassName().equals(ReactomeJavaConstants.LiteratureReference))
            throw new IllegalArgumentException("The passed instance is not a LiteratureReference.");
        lrFiller.process(instance);
        return instance;
    }
    
    /**
     * A helper method to count instances for each class and then add to its respective 
     * class object.
     * @param cls the root class, i.e., DatabaseObject.
     * @throws Exception
     */
    private void countInstances(SimpleSchemaClass cls) {
        Integer count = countInstances(cls.getName(), null);
        cls.setCount(count);
        if (cls.getChildren() == null || cls.getChildren().size() == 0)
            return;
        for (SimpleSchemaClass child : cls.getChildren())
            countInstances(child);
    }
    
    public Integer countInstances(String clsName, 
                                  String query) {
        return curationRepository.countInstances(clsName, query);
    }
    
    public InstanceList listInstances(String className,
                                      int skip,
                                      int limit,
                                      List<String> attributes,
                                      List<String> attributeTypes,
                                      List<ListOperand> operands,
                                      List<String> searchKeys) {
        return curationRepository.listInstances(className, skip, limit, attributes,
                    attributeTypes, operands, searchKeys);
    }
    
    public InstanceList listInstances(String className,
                                      int skip,
                                      int limit,
                                      String text) {
        return curationRepository.listInstances(className, skip, limit, text);
    }
    
    public SimpleInstance findInstance(String displayName,
                                       List<String> clsNames) {
        return curationRepository.findInstance(displayName, clsNames);
    }

    public Long getNextDbId(){
        return curationRepository.nextDbId();
    }

    public List<String> getSchemaClasses() {
        return curationRepository.getSchemaClasses();
    }
    
    public void persitInstances(List<SimpleInstance> instances,
                                String accountName) throws Exception {
        fileRepository.persist(instances, getFileForPersistedInstances(accountName));
    }
    
    public List<SimpleInstance> loadInstances(String accountName) throws Exception {
        return fileRepository.load(getFileForPersistedInstances(accountName));
    }
    
    private String getFileForPersistedInstances(String accountName) {
        File file = new File(toolEnv.getFileRepoDir(), accountName + ".json");
        return file.getAbsolutePath();
    }
    
    public void deletePersistedInstances(String accountName) throws Exception {
        fileRepository.deleteFile(getFileForPersistedInstances(accountName));
    }

    public List<SimpleInstance> getEventTree(String speciesName,
                                             String className,
                                             String attributes,
                                             String attributeTypes,
                                             String operands,
                                             String searchKeys) {
        return curationRepository.getEventTree(speciesName, className, attributes, attributeTypes, operands, searchKeys);
    }

    public Map<String, List<Map<String, Object>>> getHierarchicalPlotData(Long dbId) {
        return curationRepository.getHierarchicalPlotData(dbId);
    }

    public Map<String, List<Map<String, Object>>> getReactionPlotData(Long dbId) {
        return curationRepository.getReactionPlotData(dbId);
    }
    
    public SimpleInstance fetchReactionWithParticipants(Long dbId) {
        return curationRepository.fetchReactionWithParticipants(dbId);
    }

    /**
     * A placeholder for a QA check in order to prototype the interaction with the front-end
     * @param dbId
     * @param checkType
     * @param editedAttributeNames
     * @param editedAttributeValues
     * @return A list of modified attributes that have no value (such values come in as "null" in the API call)
     */
    public List<List<String>> getTestQACheckReport(
            Long dbId,
            String checkType,
            List<String> editedAttributeNames,
            List<String> editedAttributeValues) {
        List<List<String>> ret = new ArrayList<>();
        int i = 0;
        for (String attr : editedAttributeNames) {
            String val = editedAttributeValues.get(i);
            if (checkType.equals("NonNullCheck")) {
                if (val.equals("null")) {
                    if (ret.isEmpty()) {
                        ret.add(Arrays.asList("dbId", "Attribute Name", "Attribute Value"));
                    }
                    ret.add(Arrays.asList(dbId.toString(), attr, val));
                }
            } else if (checkType.equals("NegativeValueCheck")) {
                try {
                    if (Integer.parseInt(val) < 0) {
                        if (ret.isEmpty()) {
                            ret.add(Arrays.asList("dbId", "Attribute Name", "Attribute Value"));
                        }
                        ret.add(Arrays.asList(dbId.toString(), attr, val));
                    }
                } catch (NumberFormatException e) {
                    // quiesce
                }
            }
            i++;
        }
        return ret;
    }



    public Boolean delete(DatabaseObject obj) {
        return curationRepository.delete(obj);
    }

    public Collection<Referrals> getReferrers(Long dbId) throws Exception {
        return curationRepository.getReferrers(dbId);
    }
}
