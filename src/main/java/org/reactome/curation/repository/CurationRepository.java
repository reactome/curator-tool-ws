package org.reactome.curation.repository;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.gk.model.ReactomeJavaConstants;
import org.neo4j.cypherdsl.core.Condition;
import org.neo4j.cypherdsl.core.Cypher;
import org.neo4j.cypherdsl.core.Functions;
import org.neo4j.cypherdsl.core.Node;
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReading;
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReadingWithoutWhere;
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingUpdate;
import org.neo4j.cypherdsl.core.StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere;
import org.reactome.curation.exceptions.DatabaseObjectNotFoundException;
import org.reactome.curation.model.InstanceList;
import org.reactome.curation.model.ListOperand;
import org.reactome.curation.model.NamedReferrerList;
import org.reactome.curation.model.Referrer;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.util.DatabaseObjectDisplayNameGenerator;
import org.reactome.server.graph.domain.annotations.ReactomeTransient;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.Deleted;
import org.reactome.server.graph.domain.model.DeletedInstance;
import org.reactome.server.graph.domain.model.InstanceEdit;
import org.reactome.server.graph.domain.model.Species;
import org.reactome.server.graph.domain.model.Taxon;
import org.reactome.server.graph.service.helper.StoichiometryObject;
import org.reactome.server.graph.service.util.DatabaseObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.neo4j.core.Neo4jTemplate;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import lombok.Data;

/**
 * Apparently the default auto-generated code from Neo4jRepository cannot be
 * used for our purpose. It is extreme slow to load the whole reference graph
 * for a object and cannot find a simple way to control it. Therefore, we may
 * need to write our own cypher query based CRUD operations. All read-only
 * queries should be based on graph-core. We use a class, instead of extending
 * Neo4jRepository interface, to implement this curation repository so that we
 * have a much better control (e.g. handling dbId, etc). This is a better
 * approach!
 *
 * @author wug TODO: Make sure no ReactomeTransient properties are saved!
 */
@Repository
@Data
@SuppressWarnings("unchecked")
public class CurationRepository {
    private static final Logger logger = LoggerFactory.getLogger(CurationRepository.class);
    // To be used to set the relationship properties
    private static final String STOICHIOMETRY = "stoichiometry";
    private static final String ORDER = "order";

    // A list of static queries we will use
    private final String MAX_DB_ID_QUERY = "MATCH (n:DatabaseObject) RETURN MAX(n.dbId)";
    private final String LIST_CLASSES_QUERY = "MATCH (d:DatabaseObject) RETURN DISTINCT d.schemaClass";

    // @Autowired. Don't do here. Let constructor handle it so that we can customize
    // some initialization.
    private Neo4jClient neo4jClient;
    // @Autowired
    private Neo4jTemplate neo4jTemplate;
//    @Autowired
    private CypherQueryUtilities queryUtilities;

    // We will handle dbId at the Java layer for performance reason and easy
    // control.
    // Pay attention to this since it is probably the utmost important information
    // to avoid database collapse!!!
    private Long maxDbId;
    // Cache this for performance
    private Map<String, Map<String, Relationship>> cls2field2rel = new HashMap<>();

    public CurationRepository(Neo4jClient neo4jClient, Neo4jTemplate neo4jTemplate, CypherQueryUtilities queryUtilities) {
        this.neo4jClient = neo4jClient;
        this.neo4jTemplate = neo4jTemplate;
        this.queryUtilities = queryUtilities;
        // Some house keeping when the repository starts
        createDbIdIndex();
        maxDbId = getMaxDbId();
    }

    private Map<String, Relationship> getField2rel(Class<?> cls) {
        if (cls2field2rel.containsKey(cls.getName()))
            return cls2field2rel.get(cls.getName());
        // Need to build field2rel relationship
        Map<String, Relationship> field2rel = new HashMap<>();
        Class<?> _class = cls;
        while (!_class.equals(Object.class)) {
            for (Field field : _class.getDeclaredFields()) {
                // Escape field labeled as ReactomeTransient. These fields should not be stored
                // They are used more likely for performance purpose only.
                if (field.getAnnotation(ReactomeTransient.class) != null) {
                    continue;
                }
                if (field.getAnnotation(Relationship.class) != null) {
                    field2rel.put(field.getName(), field.getAnnotation(Relationship.class));
                }
            }
            // Didn't find the field in the given class. Check the Superclass.
            _class = _class.getSuperclass();
        }
        cls2field2rel.put(cls.getName(), field2rel);
        return field2rel;
    }

    /**
     * Get the next dbId that can be used to create a new DatabaseObject. This is a
     * synchronized method so that it can be accessed by one thread only to avoid
     * any id conflict.
     *
     * @return
     */
    public synchronized Long nextDbId() {
        return ++maxDbId;
    }

    private Long getMaxDbId() {
        return neo4jClient.query(MAX_DB_ID_QUERY).fetchAs(Long.class).one().get();
    }

    /**
     * Perform deletion based on Deleted object. The Deleted object should give us dbIds
     * for objects to be deleted. The following steps are performed related to deletion by Deleted object:
     * 1). Based on the deleted dbIds in the deleted object, a list of DeletedInstance objects
     * will be created. Each DeletedInstance object will have null dbId and referred in the Deleted object.
     * 2). The Deleted object will be saved first. This save step will cascade to save all DeletedInstance.
     * 3). After that all instances whose dbIds are in the deleted dbIds will be deleted from the database.
     * @param deleted
     * @param ie
     * @return
     * @throws Exception
     */
    @Transactional
    public Deleted deleteByDeleted(Deleted deleted,
                                   List<DatabaseObject> toBeDeleted,
                                   InstanceEdit ie) throws Exception {
        // Step 1: Create DeletedInstance for the passed toBeDeleted object list
        List<DeletedInstance> deletedInstances = new ArrayList<>();
        for (DatabaseObject obj : toBeDeleted) {
            DeletedInstance deletedInstance = new DeletedInstance();
            deletedInstance.setDbId(null); // Make sure dbId is null
            deletedInstance.setDeletedInstanceDbId(obj.getDbId().intValue());
            deletedInstance.setClazz(obj.getClassName());
            deletedInstance.setDeletedStId(obj.getStId());
            deletedInstance.setName(obj.getDisplayName());
            deletedInstance.setCreated(ie); // Don't forget to set created.
            // Check species is there by checking the method
            Map<String, Object> field2value = DatabaseObjectUtils.getAllFields(obj, true);
            if (field2value.containsKey(ReactomeJavaConstants.species)) {
                Object value = field2value.get(ReactomeJavaConstants.species);
                if (value instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Taxon> speciesList = (List<Taxon>) value;
                    deletedInstance.setSpecies(speciesList);
                }
                else if (value instanceof Taxon) {
                    deletedInstance.setSpecies(List.of((Taxon) value));
                }
            }
            // Display name
            String displayName = DatabaseObjectDisplayNameGenerator.generateDeletedInstanceName(deletedInstance);
            deletedInstance.setDisplayName(displayName);
            deletedInstances.add(deletedInstance);
        }
        deleted.setDeletedInstance(deletedInstances);
        deleted.setCreated(ie); // Don't forget to set created.
        // Step 2: Store the Deleted object (cascade to store all DeletedInstance)
        store(deleted);
        // Step 3: Delete all instances whose dbIds are in the toBeDeleted list
        for (DatabaseObject obj : toBeDeleted) {
            delete(obj, ie);
        }
        return deleted;
    }

    /**
     * Delete an object as specified.
     * @param obj
     * @return true if nothing wrong. The detailed information is not returned here.
     * @throws Exception
     */
    @Transactional
    public Boolean delete(DatabaseObject obj, InstanceEdit ie) throws Exception {
        // Make sure there is a node having this dbId
        if (!neo4jTemplate.existsById(obj.getDbId(), obj.getClass())) {
            throw new DatabaseObjectNotFoundException(obj);
        }

        if (ie != null) {
            Collection<NamedReferrerList> referrers = getReferrers(obj.getDbId());
            if (referrers != null && !referrers.isEmpty()) {
                // Better to add first in case something is wrong during deletion
                if (ie.getDbId() == null || ie.getDbId() < 0)
                    ie = (InstanceEdit) store(ie); // The cast should be safe
                for (NamedReferrerList referList: referrers) {
                    for (SimpleInstance referrer : referList.getReferrers()) {
                        this.queryUtilities.addModifiedIE(referrer, ie, neo4jClient);
                    }
                }
            }
        }

        // Build a dsl query to delete the node having this dbId
        Node objNode = Cypher.node(getNodeLabel(obj)).named(getNodeName(obj)).withProperties("dbId",
                Cypher.literalOf(obj.getDbId()));
        var query = Cypher.match(objNode).detachDelete(objNode).build();
        //        System.out.println(Renderer.getDefaultRenderer().render(query));
        // Commit the deletion
        neo4jClient.query(query.getCypher()).run(); // This may return something. But for the time being, we don't care
        // as long as no exception is thrown.
        return true;
    }

    /**
     * Store the DatabaseObject's shell representation (dbId and displayName only) so that we can
     * refer to it from other objects.
     * @param obj
     * @return
     * @throws Exception
     */
    @Transactional
    public DatabaseObject storeShell(DatabaseObject obj) throws Exception {
        // Only instance that has not been in the database can be stored
        if (obj.getDbId() != null && neo4jTemplate.existsById(obj.getDbId(), obj.getClass())) {
            throw new IllegalStateException(obj + " is in the database and cannot be stored. Call update instead.");
        }
        if (obj.getDbId() == null || obj.getDbId() < 0)
            obj.setDbId(nextDbId());
        // To do save, we create a DatabaseObject without relationships first
        DatabaseObject proxyNode = obj.getClass().getConstructor().newInstance();
        proxyNode.setDbId(obj.getDbId());
        // Don't forget to copy the dbId there. It is not in the above loop.
        proxyNode.setDbId(obj.getDbId());
        neo4jTemplate.save(proxyNode);
        return obj;
    }


    /**
     * Store a new DatabaseObject. The DatabaseObject should be new and doesn't have
     * a POSITIVE dbId assigned yet. The implementation of this method is based on
     * dsl to avoid loading value object to reduce the query time. TODO: If the
     * passed obj has a new DatabaseObject referred, this method will not return the
     * DB_ID for that object. For the time being, the client should call findByDbId
     * to reload the passed object to get the DB_ID for that object. This may need
     * to change in the future. Note: There is a bug in the original graph-core code
     * regarding the order of StoichiometryObject used in input, output, and
     * hasComponent. The order is _displayName based, which most likely is not true.
     * This needs a further investigation. TODO: Check relationships encoded by
     * specific classes, e.g., input, output, hasMember.
     *
     * @param obj
     * @return
     * @throws IllegalAccessException
     * @throws InstantiationException
     * @throws SecurityException
     * @throws NoSuchMethodException
     * @throws InvocationTargetException
     * @throws IllegalArgumentException
     */
    @Transactional
    public DatabaseObject store(DatabaseObject obj) throws Exception {
        // Step 1: Store a shell first if the object is new
        if (obj.getDbId() == null || obj.getDbId() < 0) 
            obj = storeShell(obj); // Assign a new dbId and create a node for the object.
        // Step 2: Get all fields and their values
        Map<String, Object> field2value = DatabaseObjectUtils.getAllFields(obj, false); // Use "false" to avoid empty
        // fields
        // Step 3: Check if any referred object has been deleted
        // Make sure existing DatabaseObject referred by the passed obj still exists to
        // avoid overwriting other curators' editing
        DatabaseObject deleted = findAnyDeletedValue(field2value);
        if (deleted != null)
            throw new DatabaseObjectNotFoundException(deleted);
        // Step 4: Make sure all referred objects (new) are stored first
        Map<String, Relationship> field2rel = getField2rel(obj.getClass());
        // Recursive calling to store all new instances
        for (String field : field2value.keySet()) {
            if (!field2rel.containsKey(field))
                continue;
            Object value = field2value.get(field);
            if (value instanceof List) {
                List<?> list = (List<?>) value;
                for (var tmp : list) {
                    storeValueObj(tmp);
                }
            } else
                storeValueObj(value);
        }
        // Step 5: Store the node properties first
        storeNodeProperties(obj, field2value, field2rel);

        // Step 6: Working on relationships
        Node objNode = Cypher.node(getNodeLabel(obj)).named(getNodeName(obj)).withProperties("dbId",
                Cypher.literalOf(obj.getDbId()));
        OngoingReading stat = Cypher.match(objNode);
        List<org.neo4j.cypherdsl.core.Relationship> relationships = new ArrayList<>();
        for (String field : field2value.keySet()) {
            if (!field2rel.containsKey(field))
                continue;
            Relationship rel = field2rel.get(field);
            Object value = field2value.get(field);
            if (value instanceof List) {
                // For list, we need to handle for all and stoichiometry for
                // Complex.hasComponent,
                // Polymer.repeatedUnit, and ReactionlikeEvent.input, output.
                List<?> list = (List<?>) value;
                for (int i = 0; i < list.size(); i++) {
                    var tmp = list.get(i);
                    stat = handleValueObj(objNode, tmp, rel, i, relationships, stat);
                }
            } 
            else {
                stat = handleValueObj(objNode, value, rel, null, relationships, stat);
            }
        }
        OngoingUpdate update = null;
        for (org.neo4j.cypherdsl.core.Relationship relationship : relationships) {
            if (update == null)
                update = stat.create(relationship);
            else
                update = update.create(relationship);
        }
        if (update != null) {
            //            var query = update.build();
            //            System.out.println(Renderer.getDefaultRenderer().render(query));
            // Commit these changes
            neo4jClient.query(update.build().getCypher()).run(); // Nothing should be returned
        }
        return obj;
    }

    /**
     * A helper method to store node properties only without relationships.
     * @param obj
     * @param field2value
     * @param field2rel
     * @throws Exception
     */
    private void storeNodeProperties(DatabaseObject obj,
                                     Map<String, Object> field2value,
                                     Map<String, Relationship> field2rel) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("MATCH (n:").append(getNodeLabel(obj))
        .append(" {dbId: $dbId}) ");

        // Build SET clauses for all primitive (non-relationship) fields
        List<String> setClauses = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        params.put("dbId", obj.getDbId());

        for (Map.Entry<String, Object> entry : field2value.entrySet()) {
            String field = entry.getKey();
            Object value = entry.getValue();
            if (value == null)
                continue; // skip null values

            if (field2rel.containsKey(field))
                continue; // skip relationship fields

            // Add parameterized property assignment
            setClauses.add("n." + field + " = $" + field);
            params.put(field, value);
        }

        // If there are properties to set
        if (!setClauses.isEmpty()) {
            sb.append("SET ").append(String.join(", ", setClauses)).append(" ");
        }

        sb.append("RETURN n");

        // Execute the Cypher query
        neo4jClient.query(sb.toString())
        .bindAll(params)
        .run();
    }

    /**
     * Query species abbreviation for a given species dbId.
     * 
     * @param speciesDbId
     * @return abbreviation or null if not found
     * @throws Exception
     */
    public String querySpeciesAbbreviation(Long speciesDbId) {
        return queryUtilities.querySpeciesAbbreviation(speciesDbId, neo4jClient);
    }

    /**
     * A helper to store value object. This method is used as an helper for
     * recursive calling of store(DatabaseObject).
     *
     * @param value
     * @return
     * @throws Exception
     */
    private DatabaseObject storeValueObj(Object value) throws Exception {
        if (value instanceof DatabaseObject) {
            DatabaseObject valueObj = (DatabaseObject) value;
            if (valueObj.getDbId() == null || valueObj.getDbId() < 0)
                return store(valueObj); // We should not let Spring creates another transaction for this call (see
            // https://www.marcobehler.com/guides/spring-transaction-management-transactional-in-depth)
        } else if (value instanceof StoichiometryObject) {
            StoichiometryObject stoiObj = (StoichiometryObject) value;
            if (stoiObj.getObject().getDbId() == null || stoiObj.getObject().getDbId() < 0)
                return store(stoiObj.getObject());
        }
        return null;
    }

    /**
     * A helper method to create relationship and match a node.
     */
    private OngoingReading handleValueObj(Node objNode,
                                          Object value,
                                          Relationship rel,
                                          Integer order,
                                          List<org.neo4j.cypherdsl.core.Relationship> relationships,
                                          OngoingReading stat) {
        DatabaseObject valueObj = null;
        Map<String, Object> relProp = new HashMap<>();
        if (order != null)
            relProp.put(ORDER, order);
        if (value instanceof DatabaseObject)
            valueObj = (DatabaseObject) value;
        else if (value instanceof StoichiometryObject) {
            StoichiometryObject stoiObj = (StoichiometryObject) value;
            valueObj = stoiObj.getObject(); // This occur for three classes only.
            // We have stoichiometry for this type of data
            relProp.put(STOICHIOMETRY, stoiObj.getStoichiometry());
        }
        if (valueObj == null)
            return stat; // Nothing needs to be done
        Node valueNode = Cypher.node(getNodeLabel(valueObj))
                .withProperties("dbId", Cypher.literalOf(valueObj.getDbId())).named(getNodeName(valueObj));
        stat = stat.match(valueNode);
        org.neo4j.cypherdsl.core.Relationship relationship = null;
        if (rel.direction() == Relationship.Direction.OUTGOING) {
            relationship = objNode.relationshipTo(valueNode, rel.type());
        } else if (rel.direction() == Relationship.Direction.INCOMING)
            relationship = objNode.relationshipFrom(valueNode, rel.type());
        else // This should never happen
            relationship = objNode.relationshipBetween(valueNode, rel.type());
        relationship = relationship.withProperties(relProp);
        relationships.add(relationship);
        return stat;
    }

    private String getNodeLabel(DatabaseObject obj) {
        return this.queryUtilities.getNodeLabel(obj);
    }

    /**
     * A utility to create a unique name for a node
     *
     * @param obj
     * @return
     */
    private String getNodeName(DatabaseObject obj) {
        return this.queryUtilities.getNodeName(obj);
    }

    /**
     * Make sure all object values referred by a DatabaseObject have not deleted.
     * This method checks for DatabaseObjects having dbIds assigned.
     *
     * @return
     */
    private DatabaseObject findAnyDeletedValue(Map<String, Object> field2value) {
        for (String field : field2value.keySet()) {
            Object value = field2value.get(field);
            if (value instanceof List) { // This is a list
                List<?> list = (List<?>) value;
                if (list.isEmpty())
                    continue;
                for (Object listObj : list) {
                    DatabaseObject deleted = checkIfDeleted(listObj);
                    if (deleted != null)
                        return deleted;
                }
            } else {
                DatabaseObject deleted = checkIfDeleted(value);
                if (deleted != null)
                    return deleted;
            }
        }
        return null;
    }

    /**
     * Return the deleted object from the passed value.
     *
     * @param value
     * @return
     */
    private DatabaseObject checkIfDeleted(Object value) {
        if (value instanceof DatabaseObject) {
            DatabaseObject dObj = (DatabaseObject) value;
            if (dObj.getDbId() == null || dObj.getDbId() < 0)
                return null;
            if (!neo4jTemplate.existsById(dObj.getDbId(), DatabaseObject.class))
                return dObj;
        } else if (value instanceof StoichiometryObject) {
            DatabaseObject dObj = ((StoichiometryObject) value).getObject();
            if (dObj.getDbId() == null || dObj.getDbId() < 0)
                return null;
            if (!neo4jTemplate.existsById(dObj.getDbId(), DatabaseObject.class))
                return dObj;
        }
        return null; // Don't care
    }

    /**
     * Find all instances for the given dbIds. The returned instances are fully loaded.
     * @param dbIds
     * @return
     * @deprecated: Don't call this method. This is highly inefficient and may cause out of memory issue.
     */
    @Deprecated
    public List<DatabaseObject> findInstances(List<Long> dbIds) {
        String query = "" +
                "MATCH (n:DatabaseObject) " +
                "WHERE n.dbId IN $dbIds " +
                "OPTIONAL MATCH (n)-[r]-(m) " +
                "WITH n, r, m " +
                "ORDER BY TYPE(r) ASC, r.order ASC " +
                "RETURN n, COLLECT(r), COLLECT(m)";
        return neo4jTemplate.findAll(query, Map.of("dbIds", dbIds), DatabaseObject.class);
    }

    /**
     * Find an instance with the matched display name in the specified classes. For
     * example, find a Species instance with displayName = homo sapiens.
     *
     * @param displayName
     * @param clsNames
     * @return
     */
    public SimpleInstance findInstance(String displayName, List<String> clsNames) {
        var instance = Cypher.anyNode().named("inst");
        var displayNameProp = instance.property("displayName");
        // Create condition for labels (i.e. clsNames)
        Condition labels = null;
        for (String clsName : clsNames) {
            if (labels == null)
                labels = instance.hasLabels(clsName);
            else
                labels = labels.or(instance.hasLabels(clsName));
        }
        Condition condition = displayNameProp.matches("(?i)" + displayName); // Perform a case insensitive search

        condition = labels.and(condition);
        var query = Cypher.match(instance);
        query.where(condition);
        var queryBuild = query
                .returning(instance.property("dbId"), instance.property("displayName"),
                        instance.property("schemaClass"))
                .limit(1) // Just need one instance
                .build();
        Optional<Map<String, Object>> result = neo4jClient.query(queryBuild.getCypher()).fetch().first();
        if (result.isEmpty())
            return null;
        // Use the first class is dangerous! But use it for the time being.
        SimpleInstance inst = constructInstance(result.get(), clsNames.get(0));
        return inst;
    }

    /**
     * Get a list of objects in SimpleInstance. Note: If performance is an issue,
     * try to create an index on displayName if it is not here like this: CREATE
     * INDEX ON :DatabaseObject(displayName)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public InstanceList listInstances(String className, int skip, int limit, String text) {
        if (text == null || text.trim().length() == 0) {
            List emptyList = Collections.EMPTY_LIST;
            return listInstances(className, skip, limit, emptyList, emptyList, emptyList, emptyList);
        }
        else {
            // if the query (text) is a number (passed as a string), to an equal map for dbId
            if (text.matches("\\d+")) {
                // Otherwise, for display name contains
                List<String> attributes = Collections.singletonList("dbId");
                List<String> attributeTypes = Collections.singletonList("integer");
                List<ListOperand> operands = Collections.singletonList(ListOperand.EQUAL);
                List<String> searchKeys = Collections.singletonList(text);
                return listInstances(className, skip, limit, attributes, attributeTypes, operands, searchKeys);
            }
            else {
                // Otherwise, for display name contains
                List<String> attributes = Collections.singletonList("displayName");
                List<String> attributeTypes = Collections.singletonList("string");
                List<ListOperand> operands = Collections.singletonList(ListOperand.CONTAINS);
                List<String> searchKeys = Collections.singletonList(text);
                return listInstances(className, skip, limit, attributes, attributeTypes, operands, searchKeys);
            }
        }
    }

    /**
     * This is basically a shortcut of attribute-based search for a pathway diagram. The implementation 
     * may call listInstances(). However, we'd like to support a pathway id based search here. 
     * @param pathwayId
     * @return
     */
    public SimpleInstance fetchPathwayDiagramForPathway(Long pathwayId) {
        String query = "" +
                "MATCH (inst:PathwayDiagram)-[:representedPathway]->(p:Pathway) " +
                "WHERE p.dbId =  " + pathwayId + " " +
                "RETURN inst.dbId, inst.displayName, inst.schemaClass " +
                "LIMIT 1";
        Optional<Map<String, Object>> result = neo4jClient.query(query).fetch().first();
        if (result.isEmpty())
            return null;
        SimpleInstance inst = constructInstance(result.get(), ReactomeJavaConstants.PathwayDiagram);
        return inst;
    }
    
    /**
     * Fetch normal pathway ids (non-disease pathways) represented by a given pathway diagram.
     * @param pdDiagramId
     * @return
     */
    public Collection<Long> fetchNormalPathwayIdsForDiagram(Integer pdDiagramId) {
        String cypher =
                "MATCH (pd:PathwayDiagram {dbId: $dbId})-[:representedPathway]->(p:Pathway) " +
                "WHERE NOT (p)-[:disease]->() " +
                "RETURN p.dbId AS dbId";
        Collection<Long> pathwayIds = neo4jClient.query(cypher)
                .bind(pdDiagramId).to("dbId")
                .fetchAs(Long.class)
                .all();
        return pathwayIds;
    }
    
    public Map<Long, String> fetchSchemaClasses(List<Long> dbIds) {
        String cypher = "MATCH (d:DatabaseObject) WHERE d.dbId IN $ids RETURN d.dbId as dbId, d.schemaClass as schemaClass";
        Collection<Map<String, Object>> results = neo4jClient.query(cypher).bind(dbIds).to("ids").fetch().all();
        Map<Long, String> id2SchemaClass = new HashMap<>();
        for (Map<String, Object> map : results) {
            Long dbId = Long.parseLong(map.get("dbId").toString());
            String clsName = map.get("schemaClass").toString();
            id2SchemaClass.put(dbId, clsName);
        }
        return id2SchemaClass;
    }
    
    /**
     * Fetch reaction ids for a given pathway id.
     * @param pathwayId
     * @return
     */
    public Collection<Long> fetchPathwayReactionIds(Long pathwayId) {
        String cypher =
                "MATCH (p:Pathway {dbId: $dbId})-[:hasEvent*]->(r:ReactionLikeEvent) " +
                "RETURN DISTINCT r.dbId AS dbId";
        Collection<Long> reactionIds = neo4jClient.query(cypher)
                .bind(pathwayId).to("dbId")
                .fetchAs(Long.class)
                .all();
        return reactionIds;
    }

    /**
     * Get a list of objects in SimpleInstance.
     * TODO: The performance may be slow with multiple match. Need to 
     * pay attention to the speed.
     * TODO: Better to use raw Cypher query directly. cypher dsl is just too complicated, unncessary!
     */
    public InstanceList listInstances(String className,
                                      int skip,
                                      int limit,
                                      List<String> attributes,
                                      List<String> attributeTypes,
                                      List<ListOperand> operands,
                                      List<String> searchKeys) {
        // Make sure the four arrays have the same lengths
        if (attributes.size() != attributeTypes.size() || 
                attributes.size() != operands.size()  || 
                attributes.size() != searchKeys.size()) {
            // Handle the case where the arrays are not of the same length
            throw new IllegalArgumentException("All arrays must have the same length");
        }

        // Start with instances for the query class
        var instance = Cypher.node(className).named("inst");
        OngoingReading query = Cypher.match(instance);

        List<Condition> attributeConditions = new ArrayList<>();
        List<org.neo4j.cypherdsl.core.Relationship> relationships = new ArrayList<>();
        List<Condition> relationshipConditions = new ArrayList<>();
        // Check if optional match should be used
        List<Boolean> optionalRelationships = new ArrayList<>();
        List<String> optionalWiths = new ArrayList<>();

        for (int i = 0; i < attributes.size(); i++) {
            if (attributeTypes.get(i).equals("instance")) {
                // Use DatabaseObject as the generic class to avoid type checking
                // attributes should be unique
                var attributeNode = Cypher.node("DatabaseObject").named(attributes.get(i));
                // For the time being, we don't care about the direction
                // TODO: check that attribute names match their schema class. The attribute name
                // may not be the same as the relationship name!!!
                // check attribute with stoichiometry: input/output/hasComponent.
                // Need to give the relationship different name
                var relName = "r_" + i;
                var relationship = instance.relationshipBetween(attributeNode, attributes.get(i)).named(relName);
                relationships.add(relationship);
                if (operands.get(i) == ListOperand.IS_NULL || operands.get(i) == ListOperand.IS_NOT_NULL) {
                    // Special relationship condition
                    var relationShipCondition = operands.get(i) == ListOperand.IS_NULL ? 
                            Cypher.name(relName).isNull() :
                                Cypher.name(relName).isNotNull();
                    relationshipConditions.add(relationShipCondition);

                    if (operands.get(i) == ListOperand.IS_NULL) {
                        optionalRelationships.add(true); 
                        optionalWiths.add(relName);
                    }
                    else {
                        optionalRelationships.add(false);
                        optionalWiths.add(null);
                    }
                }
                else {
                    relationshipConditions
                    .add(createQueryCondition("displayName", operands.get(i), searchKeys.get(i), attributeNode));
                    optionalRelationships.add(false);
                    optionalWiths.add(null);
                }
            }
            else {
                attributeConditions
                .add(createQueryCondition(attributes.get(i), operands.get(i), searchKeys.get(i), instance));
            } 

        }

        // Need to combine attribute conditions into a single condition
        // If we have more than one attribute condition.
        Condition combinedAttributeConditions = null;
        for (Condition attCondition : attributeConditions) {
            if (attCondition != null) {
                if (combinedAttributeConditions == null) {
                    combinedAttributeConditions = attCondition;
                } else {
                    combinedAttributeConditions = combinedAttributeConditions.and(attCondition);
                }
            }
        }
        if (combinedAttributeConditions != null) // This is quite danger to cast like this. However, no better way to do that
            ((OngoingReadingWithoutWhere)query).where(combinedAttributeConditions);

        for (int j = 0; j < relationships.size(); j++) {
            if (optionalRelationships.get(j)) {
                query.optionalMatch(relationships.get(j));
                var optionalWith = optionalWiths.get(j);
                if (optionalWith != null) // Most likely we need to consider using raw cypher directly.
                    query = query.with(instance, Cypher.name(optionalWith));
            }
            else
                query.match(relationships.get(j));
            var where = relationshipConditions.get(j);
            if (where != null) // Usually this should not be null. But just in case.
                // Quite danger to cast like this. 
                if (query instanceof OngoingReadingWithoutWhere)
                    ((OngoingReadingWithoutWhere)query).where(where);
                else if (query instanceof OrderableOngoingReadingAndWithWithoutWhere)
                    ((OrderableOngoingReadingAndWithWithoutWhere)query).where(where);
                else 
                    logger.error("Have not handled query type for where: " + query.getClass().getName());
        }
        // Count the total instances based on these conditions and relationships
        // Make sure distinct is used to avoid duplicated: e.g. for is not null, multiple relationships
        // will return the same instance multiple times.
        query.with(Functions.countDistinct(instance).as("totalCount"), 
                Functions.collectDistinct(instance).as("instances"))
        .unwind(Cypher.name("instances")).as(Cypher.name("inst"));

        var queryBuild = query
                .returning(Cypher.name("totalCount"), Cypher.name("inst").property("dbId"),
                        Cypher.name("inst").property("displayName"), Cypher.name("inst").property("schemaClass"))
                .orderBy(Cypher.name("inst").property("displayName")).skip(skip).limit(limit).build();

        //        System.out.println("query: " + Renderer.getDefaultRenderer().render(queryBuild));

        Collection<Map<String, Object>> all = neo4jClient.query(queryBuild.getCypher()).fetch().all();
        List<SimpleInstance> instances = new ArrayList<>();
        Integer totalCount = null;
        for (Map<String, Object> map : all) {
            if (totalCount == null)
                totalCount = Integer.parseInt(map.get("totalCount").toString());
            // This should not occur. However, just in case
            if (map.get("inst.dbId") == null) {
                logger.error("Return result with dbId = null: " + className + ", " + skip + ", " + limit);
                continue;
            }
            SimpleInstance inst = constructInstance(map, className);
            instances.add(inst);
        }
        InstanceList instanceList = new InstanceList();
        instanceList.setTotalCount(totalCount);
        instanceList.setInstances(instances);
        return instanceList;
    }

    private SimpleInstance constructInstance(Map<String, Object> map, String className) {
        return queryUtilities.constructInstance(map, className);
    }

    /**
     * Populate attribute values in inst fron contents of map
     *
     * @param inst
     * @param map
     * @return inst with attribute values populated
     */
    private void populateAttrValues(SimpleInstance inst, Map<String, Object> map) {
        for (String key : map.keySet()) {
            switch (key) {
            case "n.dbId":
                inst.setDbId(Long.parseLong(map.get("n.dbId").toString()));
                break;
            case "n.displayName":
                Object displayName = map.get(key);
                if (displayName != null) {
                    inst.setDisplayName(displayName.toString());
                } else {
                    logger.warn(
                            String.format("No {key} in the database for instance with dbId = ", key) + inst.getDbId());
                }
                break;
            case "n.schemaClass":
                Object schemaClassName = map.get(key);
                if (schemaClassName != null) {
                    inst.setSchemaClassName(schemaClassName.toString());
                } else {
                    logger.warn(
                            String.format("No {key} in the database for instance with dbId = ", key) + inst.getDbId());
                }
                break;
            default:
                // n.speciesName, n._doRelease, n.realseDate, r.order, r.stoichiometry, match
                if (!key.equals("p.dbId")) {
                    Object obj = map.get(key);
                    String attrName = key.split("\\.")[1];
                    if (obj != null) {
                        inst.setAttribute(attrName, obj);
                    }
                }
            }
        }
    }

    /**
     * Note that in each returned instance a match attribute is set to true if that
     * instance's displayName contains searchKey
     *
     * @param speciesName
     * @return List of top event instances that match speciesName (or any species if
     *         speciesName == "All").
     */
    private List<SimpleInstance> getTopEvents(String speciesName) {
        // Need some specific information for event tree. Therefore,
        // we have our own query
        String query = String.format(
                "MATCH (n:TopLevelPathway) %s "
                        + "RETURN n.dbId, n.displayName, n.schemaClass, n.speciesName, n.doRelease, n.releaseDate, n.hasDiagram",
                        !speciesName.equalsIgnoreCase("All") ? String.format("WHERE n.speciesName = '%s'", speciesName) : "");

        Collection<Map<String, Object>> all = neo4jClient.query(query).fetch().all();
        List<SimpleInstance> rtn = new ArrayList<>();
        for (Map<String, Object> map : all) {
            SimpleInstance inst = new SimpleInstance();
            populateAttrValues(inst, map);
            rtn.add(inst);
        }
        sortByDisplayName(rtn);
        return rtn;
    }

    /**
     * Sort events alphabetically by displayName
     *
     * @param events
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void sortByDisplayName(List<SimpleInstance> events) {
        Collections.sort(events, new Comparator() {
            public int compare(Object obj1, Object obj2) {
                SimpleInstance instance1 = (SimpleInstance) obj1;
                SimpleInstance instance2 = (SimpleInstance) obj2;
                String dn1 = instance1.getDisplayName();
                if (dn1 == null)
                    dn1 = "";
                String dn2 = instance2.getDisplayName();
                if (dn2 == null)
                    dn2 = "";
                int rtn = dn1.compareTo(dn2);
                if (rtn == 0) {
                    return instance1.getDbId().compareTo(instance2.getDbId());
                }
                return rtn;
            }
        });
    }
    
    public Map<String, Long> fetchCompartmentNamesAndDbIds() {
        String query = "MATCH (c:Compartment) RETURN c.dbId AS dbId, c.displayName AS name";
        Collection<Map<String, Object>> results = neo4jClient.query(query).fetch().all();
        Map<String, Long> compartmentMap = new HashMap<>();
        for (Map<String, Object> row : results) {
            Long dbId = Long.parseLong(row.get("dbId").toString());
            String name = row.get("name") != null ? row.get("name").toString() : "";
            compartmentMap.put(name, dbId);
        }
        return compartmentMap;
    }

    /**
     * Note that in each returned instance a match attribute is set to true if
     * either: 1. (attributeType == "primitive") that instance's attribute matches
     * searchKey (in the way stipulated via operand), or 2. (attribute ==
     * "instance") at least one instance that is connected the returned instance via
     * relationship: attribute has either displayName or dbId that matches searchKey
     * (in the way stipulated via operand)
     *
     * @param className
     * @param attributesCsv
     * @param attributesCsv,
     * @param operandsCsv
     * @param searchKeysCsv
     * @return a map of parent dbId's to a map of child dbId's to their
     *         corresponding event, related to that parent via a hasEvent
     *         relationship
     */
    //TODO: _doRelease has not been imported yet. Need to update this back after it is fixed.
    private Map<Long, Map<Long, SimpleInstance>> getAllEvents() {
        Map<Long, Map<Long, SimpleInstance>> parentDbId2DbId2SimpleInstance = new HashMap<>();
        String query = "MATCH (p:Event)-[r:hasEvent]->(n:Event) "
                + "RETURN DISTINCT p.dbId, n.dbId, n.displayName, n.speciesName, "
                + "n.schemaClass, n.releaseDate, n.hasDiagram, r.order";
        // Execute the query
        Collection<Map<String, Object>> all = neo4jClient.query(query).fetch().all();
        // Populate parentDbId2DbId2SimpleInstance with query results
        for (Map<String, Object> map : all) {
            Long parentDbId = Long.parseLong(map.get("p.dbId").toString());
            Map<Long, SimpleInstance> id2inst = parentDbId2DbId2SimpleInstance.get(parentDbId);
            if (id2inst == null) {
                id2inst = new HashMap<>();
                parentDbId2DbId2SimpleInstance.put(parentDbId, id2inst);
            }
            // This is the child instance
            SimpleInstance inst = new SimpleInstance();
            populateAttrValues(inst, map);
            id2inst.put(inst.getDbId(), inst);
        }
        return parentDbId2DbId2SimpleInstance;
    }

    /**
     * Populate into hasEvent field of inst the list of children (recursively)
     *
     * @param inst
     * @param parentDbId2DbId2SimpleInstance
     * @param recursive
     * @return flag to indicate that at least one child (recursively) matched
     *         searchKey (see: getTopEvents() and getAllEvents()
     */
    private void populateChildren(SimpleInstance inst,
                                  Map<Long, Map<Long, SimpleInstance>> parentDbId2DbId2SimpleInstance) {
        if (!parentDbId2DbId2SimpleInstance.containsKey(inst.getDbId()))
            return;
        Long dbId = inst.getDbId();
        List<SimpleInstance> childEvents = new ArrayList<>(parentDbId2DbId2SimpleInstance.get(dbId).values());
        // The same simple instance may be reused via hasEvent: e.g. Cell Cycle Checkpoints is listed
        // in two branches. One of them may use dbIds only. Therefore, we need to clone this list
        List<SimpleInstance> cloned = childEvents.stream().map(child -> child.cloneInstance()).collect(Collectors.toList());
        // Need to sort hasEvent list based on order
        cloned.sort((i1, i2) -> Integer.parseInt(i1.getAttribute("order") + "") - (Integer.parseInt(i2.getAttribute("order") + "")));
        inst.setAttribute("hasEvent", cloned);
        for (SimpleInstance childEvent : cloned) {
            populateChildren(childEvent, parentDbId2DbId2SimpleInstance);
        }
    }

    /**
     * This method will return anything listed under TopLevelEvents regardless their species assignment.
     * @return List of top events.
     */
    public List<SimpleInstance> getEventTree(String speciesName) {
        logger.debug("Getting TopLevelPathway events..");
        List<SimpleInstance> topEvents = getTopEvents(speciesName);
        Integer topEventsCount = topEvents.size();
        logger.debug(String.format("Retrieved %d top events. Getting all events..", topEventsCount));
        Map<Long, Map<Long, SimpleInstance>> parentDbId2DbId2SimpleInstance = getAllEvents();
        logger.debug(String.format("Retrieved events for %d parents. Building events tree..",
                parentDbId2DbId2SimpleInstance.keySet().size()));
        for (SimpleInstance inst : topEvents) {
            populateChildren(inst, parentDbId2DbId2SimpleInstance);
        }
        logger.debug("Events tree is ready.");
        return topEvents;
    }


    /**
     * Use this method to fetch a ReactionLikeEvent with all participants filled, e.g.,
     * regulators and physicalEntity in CAS should be loaded too. This is a convenient way
     * to query a reaction for adding to a pathway diagram view to avoid multiple calling.
     * TODO: Most likely it would be better to let this method to figure out the renderabe type
     * of an entity should be. For the time being, let the front-end code do this.
     * TODO: Need to determine complex or entityset drug by doing a new layer query!!!
     * @param dbId
     * @return
     */
    public SimpleInstance fetchReactionWithParticipants(Long dbId) {
        // Start with the reaction
        var node = Cypher.node("ReactionLikeEvent").named("node").withProperties("dbId", Cypher.literalOf(dbId));
        var query = Cypher.match(node);
        // Input
        var inputNode = Cypher.node(ReactomeJavaConstants.PhysicalEntity).named("input");
        query = query.optionalMatch(node.relationshipTo(inputNode, "input").named("rel_input"));
        // Reference to get the type of node
        var inputRefNode = Cypher.node(ReactomeJavaConstants.ReferenceEntity).named("inputRef");
        query = query.optionalMatch(inputNode.relationshipTo(inputRefNode, ReactomeJavaConstants.referenceEntity).named("rel_inputRef"));

        // Output
        var outputNode = Cypher.node(ReactomeJavaConstants.PhysicalEntity).named("output");
        query = query.optionalMatch(node.relationshipTo(outputNode, "output").named("rel_output"));
        var outputRefNode = Cypher.node(ReactomeJavaConstants.ReferenceEntity).named("outputRef");
        query = query.optionalMatch(outputNode.relationshipTo(outputRefNode, ReactomeJavaConstants.referenceEntity).named("rel_outputRef"));

        // Catalyst
        var casNode = Cypher.node(ReactomeJavaConstants.CatalystActivity).named("cas");
        var catalystNode = Cypher.node(ReactomeJavaConstants.PhysicalEntity).named("catalyst");
        query = query.optionalMatch(node.relationshipTo(casNode, "catalystActivity").named("rel_catalystActivity"))
                .optionalMatch(casNode.relationshipTo(catalystNode, "physicalEntity").named("rel_catalyst"));
        var caRefNode = Cypher.node(ReactomeJavaConstants.ReferenceEntity).named("catalystRef");
        query = query.optionalMatch(catalystNode.relationshipTo(caRefNode, ReactomeJavaConstants.referenceEntity).named("rel_caRef"));

        // activator
        // Note: Follow the implementation in the Java desktop version, Requirement is treated
        // as a type of activator, not processed explicitly.
        var regulationNode = Cypher.node(ReactomeJavaConstants.PositiveRegulation).named("posRegulatedBy");
        var activatorNode = Cypher.node(ReactomeJavaConstants.PhysicalEntity).named("activator");
        query = query.optionalMatch(node.relationshipTo(regulationNode, "regulatedBy").named("rel_pos_regulation"))
                .optionalMatch(regulationNode.relationshipTo(activatorNode, "regulator").named("rel_pos_regulator"));
        var activatorRefNode = Cypher.node(ReactomeJavaConstants.ReferenceEntity).named("activatorRef");
        query = query.optionalMatch(activatorNode.relationshipTo(activatorRefNode, ReactomeJavaConstants.referenceEntity).named("rel_cativatorRef"));

        // inhibitor
        regulationNode = Cypher.node(ReactomeJavaConstants.NegativeRegulation).named("negRegulatedBy");
        var inhibitorNode = Cypher.node(ReactomeJavaConstants.PhysicalEntity).named("inhibitor");
        query = query.optionalMatch(node.relationshipTo(regulationNode, "regulatedBy").named("rel_neg_regulation"))
                .optionalMatch(regulationNode.relationshipTo(inhibitorNode, "regulator").named("rel_neg_regulator"));
        var inhibitorRefNode = Cypher.node(ReactomeJavaConstants.ReferenceEntity).named("inhibitorRef");
        query = query.optionalMatch(inhibitorNode.relationshipTo(inhibitorRefNode, ReactomeJavaConstants.referenceEntity).named("rel_inhibitorRef"));

        // Combine all returned objects together for easy parsing
        var queryBuild = query.with(Cypher.name("node"), Cypher.name("input"), Cypher.name("inputRef"),
                Cypher.name("output"), Cypher.name("outputRef"),
                Cypher.name("catalyst"), Cypher.name("catalystRef"),
                Cypher.name("activator"), Cypher.name("activatorRef"),
                Cypher.name("inhibitor"), Cypher.name("inhibitorRef"),
                Cypher.name("rel_input"), Cypher.name("rel_output"))
                .unwind(Cypher.raw("[{node: node, role: 'reaction'}, "
                        + "{node: input, rel: rel_input, role: 'input', ref: inputRef}, "
                        + "{node: output, rel: rel_output, role: 'output', ref: outputRef}, "
                        + "{node: catalyst, role: 'catalyst', ref: catalystRef}, "
                        + "{node: activator, role: 'activator', ref: activatorRef}, "
                        + "{node: inhibitor, role: 'inhibitor', ref: inhibitorRef}]")) // Use raw to avoid any modification!!!
                .as(Cypher.name("data"))
                .returningDistinct(Cypher.name("data").property("node").property("dbId").as("inst.dbId"), // Prefix inst. so that we can use existed method.
                        Cypher.name("data").property("node").property("displayName").as("inst.displayName"),
                        Cypher.name("data").property("node").property("schemaClass").as("inst.schemaClass"),
                        Cypher.name("data").property("role").as("role"),
                        Cypher.name("data").property("rel").property("stoichiometry").as("stoichiometry"),
                        Cypher.name("data").property("ref").property("schemaClass").as("inst.ref.schemaClass")).build();

        //        System.out.println(Renderer.getDefaultRenderer().render(queryBuild));
        // Process the query result and model it into a SimpleInstance to return
        Collection<Map<String, Object>> all = neo4jClient.query(queryBuild.getCypher()).fetch().all();
        SimpleInstance reaction = null;
        // Do two iterations: the first to initialize the reaction
        for (Map<String, Object> map : all) {
            // Escape it if nothing there
            if (map.get("inst.dbId") == null || map.get("role") == null)
                continue;
            String role = map.get("role").toString();
            if (role.equals("reaction")) {
                reaction = constructInstance(map, null);
            }
        }
        if (reaction == null) {
            logger.error("Cannot find a ReactionLikeEvent with dbId = " + dbId);
            return null;
        }
        // Do others
        List<SimpleInstance> catalysts = null;
        List<SimpleInstance> activators = null;
        List<SimpleInstance> inhibitors = null;
        List<SimpleInstance> inputs = null;
        List<SimpleInstance> outputs = null;
        for (Map<String, Object> map : all) {
            // Escape it if nothing there
            if (map.get("inst.dbId") == null || map.get("role") == null)
                continue;
            String role = map.get("role").toString();
            // Need to check stoichiometry for input and output
            if (role.equals("input") || role.equals("output")) {
                Integer stoi = Integer.parseInt(map.get("stoichiometry").toString());
                SimpleInstance inst = constructInstance(map, null);
                List<SimpleInstance> targetList = null;
                if (role.equals("input")) {
                    if (inputs == null)
                        inputs = new ArrayList<>();
                    targetList = inputs;
                }
                else if (role.equals("output")) {
                    if (outputs == null)
                        outputs = new ArrayList<>();
                    targetList = outputs;
                }
                for (int i = 0; i < stoi; i++) {
                    if (i == 0)
                        targetList.add(inst);
                    else {
                        // To make the front-end parsing easy, we will clone inst so that
                        // the generated json will not use dbId as output!
                        SimpleInstance clone = inst.cloneInstance();
                        targetList.add(clone);
                    }
                }
            }
            else { // catalyst, activator and inhibitor
                List<SimpleInstance> targetList = null;
                if (role.equals("catalyst")) {
                    if (catalysts == null)
                        catalysts = new ArrayList<>();
                    targetList = catalysts;
                }
                else if (role.equals("activator")) {
                    if (activators == null)
                        activators = new ArrayList<>();
                    targetList = activators;
                }
                else if (role.equals("inhibitor")) {
                    if (inhibitors == null)
                        inhibitors = new ArrayList<>();
                    targetList = inhibitors;
                }
                if (targetList == null)
                    targetList = new ArrayList<>();
                SimpleInstance inst = constructInstance(map, null);
                targetList.add(inst);
            }
        }
        if (inputs != null)
            reaction.setAttribute("input", inputs);
        if (outputs != null)
            reaction.setAttribute("output", outputs);
        if (catalysts != null)
            reaction.setAttribute("catalyst", catalysts);
        if (activators != null)
            reaction.setAttribute("activator", activators);
        if (inhibitors != null)
            reaction.setAttribute("inhibitor", inhibitors);
        return reaction;
    }


    private Condition createDisplayNameQueryCondition(String text, Node instance) {
        Condition condition = null;
        if (text != null) {
            // Find display names containing text using regex
            var displayName = instance.property("displayName");
            condition = displayName.matches(".*(?i)" + text + ".*");
        }
        return condition;
    }

    private Condition createQueryCondition(String attribute, 
                                           ListOperand operand, 
                                           String searchKey, 
                                           Node instance) {
        Condition condition = null;
        var attributeProp = instance.property(attribute);
        switch (operand) {
        // We will convert everything to string to avoid type checking (e.g. dbId should be integer)
        case EQUAL:
            condition = attributeProp.isNotNull()
            .and(Functions.toString(attributeProp).isEqualTo(Cypher.literalOf(searchKey)));
            break;
        case NOT_EQUAL:
            condition = attributeProp.isNotNull()
            .and(Functions.toString(attributeProp).isNotEqualTo(Cypher.literalOf(searchKey)));
            break;
        case CONTAINS:
            // Regardless if the attribute value is string or other type
            // we will use this regex
            // Need to convert the value as string to be used in regex for any type of property
            // Always use lower case to avoid any confusion
            if (searchKey != null) // searchKey may be null for IS_NULL or IS_NOT_NULL
                searchKey = searchKey.toLowerCase(); // Only here to avoid equal check
            condition = attributeProp.isNotNull()
                    .and(Functions.toString(attributeProp)
                            .matches(".*(?i)" + searchKey + ".*"));
            break;
        case IS_NOT_NULL:
            condition = attributeProp.isNotNull();
            break;
        case IS_NULL:
            condition = attributeProp.isNull();
            break;
        default:
        }
        return condition;
    }

    public Integer countInstances(String clsName, String text) {
        var instance = Cypher.node(clsName).named("inst");
        Condition condition = createDisplayNameQueryCondition(text, instance);
        var query = Cypher.match(instance);
        if (condition != null)
            query.where(condition);
        var queryBuild = query.returning(Functions.count(instance)).build();
        return neo4jClient.query(queryBuild.getCypher()).fetchAs(Integer.class).one().get();
    }

    /**
     * Update the DatabaseObject stored in the database. The current implementation
     * is first to delete this object and then store it. This two-step process
     * probably is the cleanest and the most simply one, but with performance
     * penalty.
     *
     * @param obj
     * @return The original DB_ID should be returned if update works fine.
     * @throws Exception TODO: Make sure there is only one transaction applied.
     */
    @Transactional
    public DatabaseObject update(DatabaseObject obj) throws Exception {
        // Make sure there is a node for this object first
        if (!existsById(obj.getDbId())) {
            throw new IllegalStateException(
                    "Cannot update an object that does not exist in the database: " + obj.getDisplayName() + " ["
                            + obj.getDbId() + "]");
        }
        // Reset the node first to keep referrals
        resetNode(obj);
        // Then store the object again
        return store(obj);
    }

    /**
     * This method is used to delete all attribute relationships of an object but
     * still keep the object node itself and relationships to other objects by other
     * objects (i.e. referrals). Furthermore, all primitive attributes will be reset to
     * null except dbId. Call this method before storing an updated object so that the referrals
     * to this object are kept. This method is basically an opposite operation to store().
     * @param obj
     */
    public void resetNode(DatabaseObject obj) {
        // Get all attribute relationships
        Map<String, Relationship> field2rel = getField2rel(obj.getClass());

        // Step 1: Build Cypher to delete all relationships and reset primitive attributes
        StringBuilder sb = new StringBuilder();
        sb.append("MATCH (n:").append(getNodeLabel(obj)).append(" {dbId: $dbId}) ");
        int i = 0;
        for (String field : field2rel.keySet()) {
            // We will try to delete an relationship as long as it is defined in the model
            // even though it may not exist in the database for this object
            // By doing this, we don't need to load the object first to find out which relationship exists
            Relationship rel = field2rel.get(field);
            String alias = "r" + (++i);
            // Use OPTIONAL MATCH to avoid any problem if there is no such relationship just in case
            if (rel.direction() == Relationship.Direction.OUTGOING) {
                sb.append(String.format("OPTIONAL MATCH (n)-[%s:%s]->() ", alias, rel.type()));
            } else if (rel.direction() == Relationship.Direction.INCOMING) {
                sb.append(String.format("OPTIONAL MATCH ()-[%s:%s]->(n) ", alias, rel.type()));
            }
        }

        // Add DELETE clause if relationships exist
        if (i > 0) {
            sb.append("DELETE ");
            for (int j = 1; j <= i; j++) {
                if (j > 1) sb.append(", ");
                sb.append("r" + j);
            }
            sb.append(" WITH n ");
        }

        // Add reset of node properties except dbId
        sb.append("SET n = { dbId: n.dbId } RETURN n");
        logger.debug("Reset node Cypher: " + sb.toString());
        //        System.out.println("Reset node Cypher: " + sb.toString());

        // Execute single Cypher query
        neo4jClient.query(sb.toString())
        .bind(obj.getDbId()).to("dbId")
        .run();
    }

    /**
     * Though the client to this object can call update or store, it is recommended
     * to call this method directly and let it to figure out the passed object
     * should be store or update.
     *
     * @param obj
     * @return
     * @throws Exception
     */
    @Transactional
    public DatabaseObject commit(DatabaseObject obj) throws Exception {
        if (obj.getDbId() != null && neo4jTemplate.existsById(obj.getDbId(), obj.getClass()))
            return update(obj);
        else
            return store(obj);
    }

    /**
     * Check if an instance with dbId exists in the database.
     * @param dbId
     * @return
     */
    public boolean existsById(Long dbId) {
        return neo4jTemplate.existsById(dbId, DatabaseObject.class);
    }

    /**
     * Get the list of class names in the database.
     *
     * @return
     */
    public List<String> getSchemaClasses() {
        return neo4jClient.query(LIST_CLASSES_QUERY).fetchAs(String.class).all().stream().collect(Collectors.toList());
    }

    /**
     * Use this method to create index for DatabaseObject's DB_ID.
     */
    public void createDbIdIndex() {
        queryUtilities.createDbIdIndex(this.neo4jClient);
    }

    private Collection<Referrer> getReferralsTo(Node instanceNode, org.neo4j.cypherdsl.core.Relationship rel) {
        var query = Cypher.match(instanceNode);

        var queryBuilder = query.optionalMatch(rel)
                .returning(Cypher.name("inst").property("dbId"), // inst is the referred instance (i.e. the instance referring to the query instance)
                           Cypher.name("inst").property("displayName"), 
                           Cypher.name("inst").property("schemaClass"),
                           Functions.type(rel).as("rel"))
                .orderBy(Cypher.name("inst").property("displayName")).limit(1000).build();
        // Support both incoming and outgoing relationships
        Collection<Map<String, Object>> relationships = neo4jClient.query(queryBuilder.getCypher()).fetch().all();

        ArrayList<Referrer> referrals = new ArrayList<>();
        for (Map<String, Object> map : relationships) {
            // This should not occur. However, just in case
            if (map.get("inst.dbId") == null) {
                logger.error("Return result with dbId = null: " + rel.toString());
                continue;
            }
            if (map.get("inst.schemaClass") == null) { // We have to have schemaClass to construct SimpleInstance
                logger.error("Return result with schemaClass = null for dbId = " + map.get("inst.dbId"));
                continue;
            }
            SimpleInstance inst = constructInstance(map, map.get("inst.schemaClass").toString());
            String attributeName = map.get("rel") + "";

            Referrer ref = new Referrer(attributeName, inst);
            referrals.add(ref);
        }
        return referrals;
    }

    public Collection<NamedReferrerList> getReferrers(Long dbId) throws ClassNotFoundException {
        var instanceNode = Cypher.node(ReactomeJavaConstants.DatabaseObject).named("ref").withProperties("dbId", Cypher.literalOf(dbId));
        var attributeNode = Cypher.node("DatabaseObject").named("inst");

        var rel = instanceNode.relationshipTo(attributeNode).named("r_");
        Collection<Referrer> outgoingReferences = this.getReferralsTo(instanceNode, rel);

        var rel1 = instanceNode.relationshipFrom(attributeNode).named("r_");
        Collection<Referrer> incomingReferences = this.getReferralsTo(instanceNode, rel1);

        Collection<NamedReferrerList> finalReferrals = new ArrayList<>();
        // Incoming relationships from other instances to the query instance. This is opposite to the direction
        // for relationship query.
        finalReferrals.addAll(this.checkReferrers(outgoingReferences, Relationship.Direction.INCOMING));
        finalReferrals.addAll(this.checkReferrers(incomingReferences, Relationship.Direction.OUTGOING));

        return finalReferrals;
    }

    private Collection<NamedReferrerList> checkReferrers(Collection<Referrer> references,
                                                         Relationship.Direction direction) throws ClassNotFoundException {
        Map<String, List<SimpleInstance>> referrers = new HashMap<>();
        Collection<NamedReferrerList> listRefs = new ArrayList<>();
        for (Referrer ref : references) {
            String clsName = DatabaseObject.class.getPackageName() + '.' + ref.getSimpleInstance().getSchemaClassName();
            Class<?> cls = Class.forName(clsName);
            Map<String, Relationship> field2relRefObj = this.getField2rel(cls);
            Relationship relationshipFromRef = field2relRefObj.get(ref.getAttributeName());
            if(relationshipFromRef != null) {
                if (relationshipFromRef.direction().equals(direction)) {
                    if(referrers.containsKey(ref.getAttributeName()))
                    {
                        referrers.get(ref.getAttributeName()).add(ref.getSimpleInstance());
                    }
                    else {
                        List<SimpleInstance> insts = new ArrayList<>();
                        insts.add(ref.getSimpleInstance());
                        referrers.put(ref.getAttributeName(), insts);
                    }
                }
            }
        }
        for(String key : referrers.keySet()){
            List<SimpleInstance> insts = referrers.get(key);
            NamedReferrerList curatorToolReferrerList = new NamedReferrerList();
            curatorToolReferrerList.setAttributeName(key);
            curatorToolReferrerList.setReferrers(insts);
            listRefs.add(curatorToolReferrerList);
        }
        return listRefs;
    }

    public Set<Species> grepSpecies(Long dbId, String followAttributes, String schemaClass) {
        return this.queryUtilities.grepSpecies(dbId, followAttributes, schemaClass, neo4jClient);
    }
    
    public List<Taxon> queryInstanceTaxon(DatabaseObject obj) {
        return this.queryUtilities.queryInstanceTaxon(obj, neo4jClient);
    }
    
    public boolean complexOrSetHasDrug(Long dbId) {
        return this.queryUtilities.complexOrSetHasDrug(dbId, neo4jClient);
    }
    
    public Collection<Long> getReferenceEntityDbIdsForPEId(Long dbId) {
        if (dbId == null)
            return Collections.EMPTY_SET;
        return queryUtilities.getReferenceEntityDbIdsForPEId(dbId, neo4jClient);
    }
    
    public Collection<Long> getMemberIdsForEntitySet(Long setId) {
        if (setId == null)
            return Collections.EMPTY_SET;
        return queryUtilities.getMemberDbIdsForEntitySet(setId, neo4jClient);
    }
}
