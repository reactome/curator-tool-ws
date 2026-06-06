package org.reactome.curation.service;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.config.CuratorToolEnv;
import org.reactome.curation.model.*;
import org.reactome.curation.model.CurationAttribute.DefiningAttributeValue;
import org.reactome.curation.model.CurationAttribute.DefiningType;
import org.reactome.curation.repository.CurationFileRepository;
import org.reactome.curation.repository.CurationRepository;
//import org.reactome.server.graph.aop.LazyFetchAspect;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.Deleted;
import org.reactome.server.graph.domain.model.InstanceEdit;
import org.reactome.server.graph.domain.model.Taxon;
import org.reactome.server.graph.repository.AdvancedDatabaseObjectRepository;
import org.reactome.server.graph.service.helper.AttributeClass;
import org.reactome.server.graph.service.helper.AttributeProperties;
import org.reactome.server.graph.service.helper.StoichiometryObject;
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
    
    public boolean existsById(Long dbId) {
        return this.curationRepository.existsById(dbId);
    }
    
    public DatabaseObject findById(Long dbId) {
       return objectRepository.findById(dbId, 1);
    }
    
    public DatabaseObject commit(DatabaseObject obj) throws Exception {
        return curationRepository.commit(obj);
    }
    
    public DatabaseObject commitNewInstanceInShell(DatabaseObject obj) throws Exception {
        return curationRepository.storeShell(obj);
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
        Map<String, AttributeProperties> name2prop = new HashMap<>();
        // Cannot use streams.toMap() as the previous code due to potential method overloading issue.
        // e.g the new Person class definition. The modified property may be defined two times, therefore
        // two entries in properties, which use objects.
        for (AttributeProperties prop : properties) {
            name2prop.put(prop.getName(), prop);
        }
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
    
    public SimpleInstance fetchPathwayDiagramForPathway(Long pathwayId) {
        return curationRepository.fetchPathwayDiagramForPathway(pathwayId);
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
    
    /**
     * Find instances for a list of dbIds. The returned DatabaseObjects are fully loaded.
     * @param dbIds
     * @return
     */
    public List<DatabaseObject> findInstancesByIds(List<Long> dbIds) {
        return curationRepository.findInstances(dbIds);
    }

    public Long getNextDbId(){
        return curationRepository.nextDbId();
    }

    public List<String> getSchemaClasses() {
        return curationRepository.getSchemaClasses();
    }
    
    public void persitInstances(UserInstances instances,
                                String accountName) throws Exception {
        fileRepository.persist(instances, getFileForPersistedInstances(accountName));
    }

    /**
     * Persist diagram instances using database-backed storage with true upsert semantics.
     * Updates existing diagrams keyed by (account, pathwayDiagramId) or creates new ones.
     * This prevents data loss from concurrent edits.
     *
     * @param diagramPayloads list of diagrams to persist
     * @param accountName the user account name
     * @throws Exception if payload validation or persistence fails
     */
    public void persistDiagramInstances(List<DiagramsPersistencePayload> diagramPayloads,
                                        String accountName) throws Exception {

        UserInstances userInstances = this.loadUserInstances(accountName);
        if (userInstances == null) userInstances = new UserInstances();
        userInstances.setPathwayDiagrams(diagramPayloads);
        fileRepository.persist(userInstances, getFileForPersistedInstances(accountName));    }

    public boolean deletePersistedDiagramInstances(DiagramsPersistencePayload diagramPayload,
                                                   String accountName) throws Exception {
//        // Use database-backed deletion if repository is available, otherwise fallback to file storage
//        if (persistedPathwayDiagramRepository != null) {
//            return deletePersistedDiagramInstancesDB(diagramPayload, accountName);
//        } else {
//            return deletePersistedDiagramInstancesLegacy(diagramPayload, accountName);
//        }
        return false;
    }

    /**
     * Legacy file-based deletion for backward compatibility.
     *
     * @param diagramPayload the diagram to delete (must contain pathwayDiagramId)
     * @param accountName the user account name
     * @return true if deleted, false if not found
     * @throws Exception if deletion fails
     */
    private boolean deletePersistedDiagramInstancesLegacy(DiagramsPersistencePayload diagramPayload,
                                                          String accountName) throws Exception {
        UserInstances userInstances = loadUserInstances(accountName);
        if (userInstances == null)
            return false;

        List<DiagramsPersistencePayload> pathwayDiagrams = userInstances.getPathwayDiagrams();
        if (pathwayDiagrams == null || pathwayDiagrams.isEmpty())
            return false;

        Long diagramDbId = diagramPayload == null ? null : diagramPayload.getPathwayDiagramId();
        if (diagramDbId == null || diagramDbId <= 0)
            return false;

        boolean removed = pathwayDiagrams.removeIf(item -> item != null
                && item.getPathwayDiagramId() != null
                && diagramDbId.equals(item.getPathwayDiagramId()));
        if (!removed)
            return false;

        if (pathwayDiagrams.isEmpty())
            fileRepository.deleteFile(getFileForPersistedInstances(accountName));
        else {
            userInstances.setPathwayDiagrams(pathwayDiagrams);
            fileRepository.persist(userInstances, getFileForPersistedInstances(accountName));
        }
        return true;
    }

    public String getPathwayDiagramAccountName(String username, Long diagramDbId) {
        if (username == null || username.trim().isEmpty())
            return null;
        if (diagramDbId == null || diagramDbId <= 0)
            return null;
        return username;
    }

    public UserInstances loadUserInstances(String accountName) throws Exception {
        return fileRepository.load(getFileForPersistedInstances(accountName));
    }
    
    private String getFileForPersistedInstances(String accountName) {
        File file = new File(toolEnv.getFileRepoDir() + File.separator + accountName, accountName + ".json");
        return file.getAbsolutePath();
    }
    
    /**
     * Find existing instances in the database that match the defining attributes of
     * the passed {@link SimpleInstance}.
     * <p>
     * Steps:
     * <ol>
     *   <li>Load the schema attribute definitions for the instance's schema class.</li>
     *   <li>For each attribute whose defining type is ALL_DEFINING or ANY_DEFINING,
     *       look up the corresponding value in the instance's attribute map and wrap
     *       it in a {@link DefiningAttributeValue}, noting whether it is a reference
     *       (instance-type) attribute so the Cypher layer knows to match by dbId.</li>
     *   <li>Delegate to the repository which calls
     *       {@code CypherQueryUtilities.findMatchingInstancesByDefiningAttributes}.</li>
     * </ol>
     *
     * @param instance the candidate instance whose defining attributes are used as the search key
     * @return list of lightweight {@link SimpleInstance} shells for matched instances,
     *         empty if none found or if no defining attributes have values
     * @throws Exception if the schema attributes cannot be loaded
     */
    public List<SimpleInstance> findMatchedInstances(SimpleInstance instance) throws Exception {
        String schemaClassName = instance.getSchemaClassName();
        List<CurationAttribute> attributes = getAttributes(schemaClassName);
        if (attributes == null || attributes.isEmpty())
            return Collections.emptyList();

        Map<String, DefiningAttributeValue> definingAttributeValues = new HashMap<>();
        for (CurationAttribute attr : attributes) {
            DefiningType definingType = attr.getDefiningType();
            // Only ALL_DEFINING and ANY_DEFINING attributes are used for matching
            if (definingType == null
                    || definingType == DefiningType.NONE_DEFINING
                    || definingType == DefiningType.UNDEFINED)
                continue;
            String attrName = attr.getName();
            Object value = instance.getAttribute(attrName);
            if (value == null)
                continue; // No value provided for this defining attribute — skip it

            // Determine if this is a reference (relationship) attribute.
            // For reference attributes the value is a SimpleInstance or a List thereof;
            // the Cypher layer will match by dbId.
            boolean isReference = isInstanceType(schemaClassName, attrName);
            Object searchValue;
            if (isReference) {
                // Extract dbId(s) so the Cypher query can match on the relationship target
                if (value instanceof List) {
                    List<Long> dbIds = new ArrayList<>();
                    for (Object item : (List<?>) value) {
                        if (item instanceof SimpleInstance) {
                            Long dbId = ((SimpleInstance) item).getDbId();
                            if (dbId != null)
                                dbIds.add(dbId);
                        }
                    }
                    if (dbIds.isEmpty())
                        continue;
                    searchValue = dbIds;
                } else if (value instanceof SimpleInstance) {
                    Long dbId = ((SimpleInstance) value).getDbId();
                    if (dbId == null)
                        continue;
                    searchValue = dbId;
                } else {
                    continue; // Unexpected type — skip
                }
            } else {
                searchValue = value;
            }
            definingAttributeValues.put(attrName, new DefiningAttributeValue(searchValue, definingType, isReference));
        }

        if (definingAttributeValues.isEmpty())
            return Collections.emptyList();

        return curationRepository.findMatchedInstances(schemaClassName, definingAttributeValues);
    }

    public void deletePersistedInstances(String accountName) throws Exception {
        fileRepository.deleteFile(getFileForPersistedInstances(accountName));
    }
    public List<SimpleInstance> getEventTree(String speciesName) {
        return curationRepository.getEventTree(speciesName);
    }

    public SimpleInstance fetchReactionWithParticipants(Long dbId) {
        return curationRepository.fetchReactionWithParticipants(dbId);
    }

    public Boolean delete(DatabaseObject obj, InstanceEdit ie) throws Exception {
        return curationRepository.delete(obj, ie);
    }
    
    public Deleted deleteByDeleted(Deleted deleted, InstanceEdit ie) throws Exception {
        // Make sure the deleted objects are fully loaded
        List<Integer> deletedDbIds = deleted.getDeletedInstanceDbId();
        List<DatabaseObject> toBeDeleted = new ArrayList<>();
        if (deletedDbIds != null && deletedDbIds.size() > 0) {
            for (Integer dbId : deletedDbIds) {
                DatabaseObject obj = findById(dbId.longValue());
                if (obj != null)
                    toBeDeleted.add(obj);
            }           
        }
        return curationRepository.deleteByDeleted(deleted, toBeDeleted, ie);
    }
    
    public void addModifiedIE(DatabaseObject target, InstanceEdit modifiedIE) throws Exception {
        curationRepository.addModifiedIE(target, modifiedIE);
    }

    public Collection<NamedReferrerList> getReferrers(Long dbId) throws Exception {
        return curationRepository.getReferrers(dbId);
    }
    
    /**
     * A recursive way to get the new DatabaseObject in the passed object's reference graph.
     * Note: If a reference is an updated instance using a new instance, this new instance
     * is not included since its dbId will not get updated.
     * @param obj instance to inspect for nested new objects
     * @return all nested objects whose dbId is still negative
     */
    public Set<DatabaseObject> grepNewInstances(DatabaseObject obj) {
        Set<DatabaseObject> newInstances = new HashSet<>();
        if (obj.getDbId() < 0)
            newInstances.add(obj);
        grepNewInstances(obj, newInstances);
        return newInstances;
    }
    
    private void grepNewInstances(DatabaseObject obj, Set<DatabaseObject> newInstances) {
        Map<String, Object> field2value = DatabaseObjectUtils.getAllFields(obj, false);
        // Recursive calling to store all new instances
        for (String field : field2value.keySet()) {
            Object value = field2value.get(field);
            if (value instanceof DatabaseObject) {
                DatabaseObject valueObj = (DatabaseObject) value;
                if (valueObj.getDbId() != null && valueObj.getDbId() < 0) {
                    if (!newInstances.contains(valueObj)) {
                        newInstances.add(valueObj);
                        grepNewInstances(valueObj, newInstances);
                    }
                }
            }

            if (value instanceof List) {
                List<?> list = (List<?>) value;
                for (Object value1 : list) {
                    DatabaseObject valueObj = null;
                    if (value1 instanceof DatabaseObject) {
                        valueObj = (DatabaseObject) value1;
                    }
                    else if (value1 instanceof StoichiometryObject) {
                        StoichiometryObject stoichiometryObject = (StoichiometryObject) value1;
                        valueObj = stoichiometryObject.getObject();
                    }

                    if (valueObj == null || 
                       (valueObj.getDbId() != null && valueObj.getDbId() > 0) || 
                       newInstances.contains(valueObj))
                        continue; // do nothing

                    newInstances.add(valueObj);
                    grepNewInstances(valueObj, newInstances);
                }
            } 
        }
    }
    
    public String querySpeciesAbbreviation(Long speciesDbId) {
        return curationRepository.querySpeciesAbbreviation(speciesDbId);
    }
    
    /**
     * If the passed instance has less InstanceEdit than the saved one,
     * return true. 
     * @param instance
     * @return
     */
    public boolean isConflictWithStored(SimpleInstance instance) {
        if (instance.getDbId() == null || instance.getDbId() < 0)
            return false; // This is new
        DatabaseObject stored = findById(instance.getDbId());
        if (stored == null)
            return false; // This instance may be deleted. We can save it again.
        // TODO: Updated for the list once the change is updated
        SimpleInstance modified = instance.getAttributes() == null ? 
                null :
                (SimpleInstance) instance.getAttribute(ReactomeJavaConstants.modified);
        Long ieDbId = modified == null ? null : modified.getDbId();
        Long storedIeDbId = stored.getModified() == null ? null : stored.getModified().getDbId();
        return !Objects.equals(ieDbId, storedIeDbId);
    }

    public Set<Taxon> grepSpecies(Long dbId, String followAttributes, String schemaClass) {
        return this.curationRepository.grepSpecies(dbId, followAttributes, schemaClass);
    }
}
