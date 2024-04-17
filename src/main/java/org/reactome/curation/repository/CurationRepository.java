package org.reactome.curation.repository;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.lang.WordUtils;
import org.gk.model.Instance;
import org.neo4j.cypherdsl.core.Condition;
import org.neo4j.cypherdsl.core.Cypher;
import org.neo4j.cypherdsl.core.Functions;
import org.neo4j.cypherdsl.core.Node;
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReading;
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingUpdate;
import org.reactome.curation.exceptions.DatabaseObjectNotFoundException;
import org.reactome.curation.model.CuratorToolWSUtils;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.server.graph.domain.model.DatabaseObject;
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
 * Apparently the default auto-generated code from Neo4jRepository cannot be used for our purpose.
 * It is extreme slow to load the whole reference graph for a object and cannot find a simple way to control
 * it. Therefore, we may need to write our own cypher query based CRUD operations. All read-only queries
 * should be based on graph-core.
 * We use a class, instead of extending Neo4jRepository interface, to implement this curation repository so that
 * we have a much better control (e.g. handling dbId, etc). This is a better approach!
 * @author wug
 * TODO: Make sure no ReactomeTransient properties are saved!
 *
 */
@Repository
@Data
public class CurationRepository {
    private static final Logger logger = LoggerFactory.getLogger(CurationRepository.class);
    // To be used to set the relationship properties
    private static final String STOICHIOMETRY = "stoichiometry";
    private static final String ORDER = "order";

    // A list of static queries we will use
    private final String MAX_DB_ID_QUERY = "MATCH (n:DatabaseObject) RETURN MAX(n.dbId)";
    private final String LIST_CLASSES_QUERY = "MATCH (d:DatabaseObject) RETURN DISTINCT d.schemaClass";

    //    @Autowired. Don't do here. Let constructor handle it so that we can customize some initialization.
    private Neo4jClient neo4jClient;
    //    @Autowired
    private Neo4jTemplate neo4jTemplate;

    // We will handle dbId at the Java layer for performance reason and easy control.
    // Pay attention to this since it is probably the utmost important information to avoid database collapse!!!
    private Long maxDbId;
    // Cache this for performance
    private Map<String, Map<String, Relationship>> cls2field2rel = new HashMap<>();

    public CurationRepository(Neo4jClient neo4jClient, Neo4jTemplate neo4jTemplate) {
        this.neo4jClient = neo4jClient;
        this.neo4jTemplate = neo4jTemplate;
        // Some house keeping when the repository starts
        createDbIdIndex();
        maxDbId = getMaxDbId();
    }

    private Map<String, Relationship> getField2rel(Class<? extends DatabaseObject> cls) {
        if (cls2field2rel.containsKey(cls.getName()))
            return cls2field2rel.get(cls.getName());
        // Need to build field2rel relationship
        Map<String, Relationship> field2rel = new HashMap<>();
        Class<?> _class = cls;
        while (!_class.equals(Object.class)) {
            for (Field field : _class.getDeclaredFields()) {
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
     * Get the next dbId that can be used to create a new DatabaseObject.
     * This is a synchronized method so that it can be accessed by one thread only 
     * to avoid any id conflict.
     * @return
     */
    public synchronized Long nextDbId() {
        return ++maxDbId;
    }

    private Long getMaxDbId() {
        return neo4jClient.query(MAX_DB_ID_QUERY).fetchAs(Long.class).one().get();
    }
    
    /**
     * Delete an object as specified.
     * @param dbId
     * @return true if nothing wrong. The detailed information is not returned here.
     * @throws Exception
     */
    @Transactional
    public Boolean delete(DatabaseObject obj) {
        // Make sure there is a node having this dbId
        if (!neo4jTemplate.existsById(obj.getDbId(), obj.getClass())) {
            throw new IllegalArgumentException("Cannot find an instance with dbId = " + obj.getDbId() +
                    " and class = " + obj.getClassName());
        }
        // Build a dsl query to delete the node having this dbId
        Node objNode = Cypher.node(getNodeLabel(obj))
                             .named(getNodeName(obj))
                             .withProperties("dbId", Cypher.literalOf(obj.getDbId()));
        var query = Cypher.match(objNode).detachDelete(objNode).build();
//        System.out.println(Renderer.getDefaultRenderer().render(query));
        // Commit the deletion
        neo4jClient.query(query.getCypher()).run(); // This may return something. But for the time being, we don't care
                                                    // as long as no exception is thrown.
        return true;
    }
    
    /**
     * Check if the display name needs to be updated.
     * @param obj
     * @throws Exception
     */
    private void updateDisplayName(DatabaseObject obj) throws Exception {
        // To be updated
//        String newDisplayName = InstanceDisplayNameGenerator
    }

    /**
     * Store a new DatabaseObject. The DatabaseObject should be new and doesn't have a POSITIVE
     * dbId assigned yet. The implementation of this method is based on dsl to avoid loading
     * value object to reduce the query time.
     * TODO: If the passed obj has a new DatabaseObject referred, this method will not return the DB_ID for that object.
     * For the time being, the client should call findByDbId to reload the passed object to get the DB_ID for that object.
     * This may need to change in the future. 
     * Note: There is a bug in the original graph-core code regarding the order of StoichiometryObject
     * used in input, output, and hasComponent. The order is _displayName based, which most likely is
     * not true. This needs a further investigation.
     * TODO: Check relationships encoded by specific classes, e.g., input, output, hasMember.
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
        // Only instance that has not been in the database can be stored
        if (obj.getDbId() != null && neo4jTemplate.existsById(obj.getDbId(), obj.getClass())) {
            throw new IllegalStateException(obj + " is in the database and cannot be stored. Call update instead.");
        }
        // Make sure the display name is still correct.
        updateDisplayName(obj);
        // Get all get methods
        Map<String, Object> field2value = DatabaseObjectUtils.getAllFields(obj, false); // Use "false" to avoid empty fields
        // Make sure existing DatabaseObject referred by the passed obj still exists to
        // avoid overwriting other curators' editing
        DatabaseObject deleted = findAnyDeletedValue(field2value);
        if (deleted != null)
            throw new DatabaseObjectNotFoundException(deleted);
        // First save this object
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
            }
            else
                storeValueObj(value);
        }
        // Now we can start to store
        if (obj.getDbId() == null || obj.getDbId() < 0)
            obj.setDbId(nextDbId());
        //TODO: To be updated to use the values directly. Right now, some system-level attributes annotated
        // with ReactomeTransient are also saved! That is not good!
        // To do save, we create a DatabaseObject without relationships first
        DatabaseObject proxyNode = obj.getClass().getConstructor().newInstance();
        for (String field : field2value.keySet()) {
            if (field2rel.containsKey(field)) 
                continue; // Defer this for the time being
            // Escape schemaClass
            if (field.equals("schemaClass")) // This is inferred from the class name. So there is no need to copy!
                continue;
            Object value = field2value.get(field);
            Method method = CuratorToolWSUtils.getSetMethod(field, value, obj);
            if (method == null)
                throw new IllegalStateException("Cannot find method to set " + field + " in class, " + obj.getClass().getSimpleName());
            method.invoke(proxyNode, value);
        }
        // Don't forget to copy the dbId there. It is not in the above loop.
        proxyNode.setDbId(obj.getDbId());
        neo4jTemplate.save(proxyNode);
        // Working on relationships 
        Node objNode = Cypher.node(getNodeLabel(obj)).named(getNodeName(obj)).withProperties("dbId", Cypher.literalOf(obj.getDbId()));
        // Save the schemaClass here since it is escaped. But we need it!
        var setPropStat = Cypher.match(objNode).set(objNode.property("schemaClass").to(Cypher.literalOf(proxyNode.getSchemaClass())));
        neo4jClient.query(setPropStat.build().getCypher()).run();
        
        OngoingReading stat = Cypher.match(objNode);
        List<org.neo4j.cypherdsl.core.Relationship> relationships = new ArrayList<>();
        for (String field : field2value.keySet()) {
            if (!field2rel.containsKey(field))
                continue;
            Relationship rel = field2rel.get(field);
            Object value = field2value.get(field);
            if (value instanceof List) {
                // For list, we need to handle for all and stoichiometry for Complex.hasComponent,
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
     * A helper to store value object. This method is used as an helper for recursive calling of store(DatabaseObject).
     * @param value
     * @return
     * @throws Exception
     */
    private DatabaseObject storeValueObj(Object value) throws Exception {
        if (value instanceof DatabaseObject) {
            DatabaseObject valueObj = (DatabaseObject) value;
            if (valueObj.getDbId() == null || valueObj.getDbId() < 0)
                return store(valueObj); // We should not let Spring creates another transaction for this call (see https://www.marcobehler.com/guides/spring-transaction-management-transactional-in-depth)
        }
        else if (value instanceof StoichiometryObject) {
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
        Node valueNode = Cypher.node(getNodeLabel(valueObj)).withProperties("dbId", Cypher.literalOf(valueObj.getDbId())).named(getNodeName(valueObj));
        stat = stat.match(valueNode);
        org.neo4j.cypherdsl.core.Relationship relationship = null;
        if (rel.direction() == Relationship.Direction.OUTGOING) {
            relationship = objNode.relationshipTo(valueNode, rel.type());
        }
        else if (rel.direction() == Relationship.Direction.INCOMING)
            relationship = objNode.relationshipFrom(valueNode, rel.type());
        else // This should never happen
            relationship = objNode.relationshipBetween(valueNode, rel.type());
        relationship = relationship.withProperties(relProp);
        relationships.add(relationship);
        return stat;
    }

    private String getNodeLabel(DatabaseObject obj) {
        // This is a hack for convenience in case a SimpleInstance is used
        // for attribute values
        String clsName = null;
        if (obj instanceof SimpleInstance)
            clsName = DatabaseObject.class.getSimpleName();
        else
            clsName = obj.getClass().getSimpleName();
        int index = clsName.lastIndexOf(".");
        return clsName.substring(index + 1);
    }

    /**
     * A utility to create a unique name for a node
     * @param obj
     * @return
     */
    private String getNodeName(DatabaseObject obj) {
        return "obj_" + obj.getDbId();
    }

    /**
     * Make sure all object values referred by a DatabaseObject have not deleted. This method checks for
     * DatabaseObjects having dbIds assigned.
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
            }
            else {
                DatabaseObject deleted = checkIfDeleted(value);
                if (deleted != null)
                    return deleted;
            }
        }
        return null;
    }

    /**
     * Return the deleted object from the passed value.
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
        }
        else if (value instanceof StoichiometryObject) {
            DatabaseObject dObj = ((StoichiometryObject)value).getObject();
            if (dObj.getDbId() == null || dObj.getDbId() < 0)
                return null;
            if (!neo4jTemplate.existsById(dObj.getDbId(), DatabaseObject.class))
                return dObj;
        }
        return null; // Don't care
    }

    /**
     * Find an instance with the matched display name in the specified classes.
     * For example, find a Species instance with displayName = homo sapiens.
     * @param displayName
     * @param clsNames
     * @return
     */
    public SimpleInstance findInstance(String displayName,
                                       List<String> clsNames) {
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
        var queryBuild = query.returning(instance.property("dbId"),
                instance.property("displayName"),
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
     * Get a list of objects in SimpleInstance.
     * Note: If performance is an issue, try to create an index on displayName 
     * if it is not here like this: 
     * CREATE INDEX ON :DatabaseObject(displayName)
     */
    public List<SimpleInstance> listInstances(String className,
                                              int skip,
                                              int limit,
                                              String text) {
        var instance = Cypher.node(className).named("inst");
        Condition condition = createDisplayNameQueryCondition(text, instance);
        var query = Cypher.match(instance);
        if (condition != null)
            query.where(condition);
        var queryBuild = query.returning(instance.property("dbId"), 
                                         instance.property("displayName"),
                                         instance.property("schemaClass"))
             .orderBy(instance.property("displayName"))
             .skip(skip)
             .limit(limit)
             .build();
        Collection<Map<String, Object>> all = neo4jClient.query(queryBuild.getCypher())
                .fetch()
                .all();
        List<SimpleInstance> rtn = new ArrayList<>();
        for (Map<String, Object> map : all) {
            // This should not occur. However, just in case
            if (map.get("inst.dbId") == null) {
                logger.error("Return result with dbId = null: " + className + ", " + skip + ", " + limit);
                continue;
            }
            SimpleInstance inst = constructInstance(map, className);
            rtn.add(inst);
        }
        return rtn;
    }

    private SimpleInstance constructInstance(Map<String, Object> map,
                                             String className) {
        SimpleInstance inst = new SimpleInstance();
        inst.setDbId(Long.parseLong(map.get("inst.dbId").toString()));
        inst.setDisplayName(map.get("inst.displayName").toString());
        Object schemaClassName = map.get("inst.schemaClass");
        if (schemaClassName != null)
            inst.setSchemaClassName(schemaClassName.toString());
        else {
            inst.setSchemaClassName(className); // Just in case! This should not happen!
            logger.warn("No schemaClass name in the database for instance with dbId = " + inst.getDbId());
        }
        return inst;
    }

    /**
     * Populate attribute values in inst fron contents of map
     * @param inst
     * @param map
     * @return inst with attribute values populated
     */
    private SimpleInstance populateAttrValues(SimpleInstance inst, Map<String, Object> map) {
        for (String key : map.keySet()) {
            switch(key) {
                case "n.dbId":
                    inst.setDbId(Long.parseLong(map.get("n.dbId").toString()));
                    break;
                case "n.displayName":
                    Object displayName = map.get(key);
                    if (displayName != null) {
                        inst.setDisplayName(displayName.toString());
                    } else {
                        logger.warn(String.format("No {key} in the database for instance with dbId = ", key) + inst.getDbId());
                    }
                    break;
                case "n.schemaClass":
                    Object schemaClassName = map.get(key);
                    if (schemaClassName != null) {
                        inst.setSchemaClassName(schemaClassName.toString());
                    } else {
                        logger.warn(String.format("No {key} in the database for instance with dbId = ", key) + inst.getDbId());
                    }
                    break;
                default:
                    // n.speciesName, n._doRelease, r.order, r.stoichiometry, match
                    if (!key.equals("p.dbId")) {
                        Object obj = map.get(key);
                        String attrName;
                        if (key.equals("match")) {
                            attrName = key;
                            if (obj != null) {
                                inst.setAttribute(attrName, obj);
                            }
                        } else {
                            attrName = key.split("\\.")[1];
                            if (obj != null) {
                                inst.setAttribute(attrName, obj.toString());
                            }
                        }
                    }
            }
        }
        return inst;
    }

    /**
     * Note that in each returned instance a match attribute is set to true if that instance's displayName
     * contains searchKey
     * @param speciesName
     * @return List of top event instances that match speciesName (or any species if speciesName == "All").
     *
     */
    private List<SimpleInstance> getTopEvents(String speciesName) {
        String query =
                String.format("MATCH (n:TopLevelPathway) %s " +
                    "RETURN n.dbId, n.displayName, n.schemaClass, n.speciesName, n._doRelease",
                            !speciesName.equalsIgnoreCase("All") ?
                                    String.format("WHERE n.speciesName = '%s'", speciesName) :
                                    "");

        Collection<Map<String, Object>> all = neo4jClient.query(query)
                .fetch()
                .all();
        List<SimpleInstance> rtn = new ArrayList<>();
        for (Map<String, Object> map : all) {
            SimpleInstance inst = new SimpleInstance();
            inst =  populateAttrValues(inst, map);
            rtn.add(inst);
        }
        sortByDisplayName(rtn);
        return rtn;
    }

    /**
     * Sort events alphabetically by displayName
     * @param events
     */
    public static void sortByDisplayName(List<SimpleInstance> events) {
        Collections.sort(events, new Comparator() {
            public int compare(Object obj1, Object obj2) {
                SimpleInstance instance1 = (SimpleInstance) obj1;
                SimpleInstance instance2 = (SimpleInstance) obj2;
                String dn1 = instance1.getDisplayName();
                if (dn1 == null) dn1 = "";
                String dn2 = instance2.getDisplayName();
                if (dn2 == null) dn2 = "";
                int rtn = dn1.compareTo(dn2);
                if (rtn == 0) {
                    return instance1.getDbId().compareTo(instance2.getDbId());
                }
                return rtn;
            }
        });
    }

    /**
     * Note that in each returned instance a match attribute is set to true if either:
     * 1. (attributeType == "primitive") that instance's attribute matches searchKey (in the way stipulated via operand), or
     * 2. (attribute == "instance") at least one instance that is connected the returned instance via relationship: attribute
     *     has either displayName or dbId that matches searchKey (in the way stipulated via operand)
     *
     * @param className
     * @param attributesCsv
     * @param attributesCsv,
     * @param operandsCsv
     * @param searchKeysCsv
     * @return a map of parent dbId's to a map of child dbId's to their corresponding event, related to that parent via a hasEvent relationship
     */
    private Map<Long, Map<Long, SimpleInstance>> getAllEvents(
            String className,
            String attributesCsv,
            String attributeTypesCsv,
            String operandsCsv,
            String searchKeysCsv) {
        Map<Long, Map<Long, SimpleInstance>> parentDbId2DbId2SimpleInstance = new HashMap();
        List<String> attributes = attributesCsv != null ? Arrays.asList(attributesCsv.split(",")) : Collections.emptyList();
        List<String> attributeTypes = attributeTypesCsv != null ? Arrays.asList(attributeTypesCsv.split(",")) : Collections.emptyList();
        List<String> operands = operandsCsv != null ? Arrays.asList(operandsCsv.split(",")) : Collections.emptyList();
        List<String> searchKeys = searchKeysCsv != null ? Arrays.asList(searchKeysCsv.split(",")) : Collections.emptyList();

        String queryRoot = "MATCH (p:Event)-[r:hasEvent]->(n:Event)";
        String queryReturnClause = " RETURN DISTINCT p.dbId, " +
                "n.dbId, n.displayName, n.schemaClass, n.speciesName, n._doRelease, r.order, r.stoichiometry";
        String matchClausePrefix = String.format(", CASE WHEN n.schemaClass = '%s' ", className);
        String matchClause = "";
        String matchClauseSuffix = "THEN true ELSE false END as match";
        boolean foundInstanceAttribute = false;
        for (int i = 0; i < operands.size(); i++) {
            String attribute = attributes.get(i);
            String attributeType = attributeTypes.get(i);
            String operand = operands.get(i);
            String searchKey = searchKeys.get(i);

            if (attributeType.equals("instance")) {
                if (!foundInstanceAttribute) {
                    queryRoot += " OPTIONAL MATCH ";
                } else {
                    queryRoot += ", ";
                }
                queryRoot += String.format("(n:Event)-[rel%d:%s]->(q%d)", i, attribute, i);
            }

            if (!searchKey.equals("na") || operand.contains("NULL")) {
                switch (operand) {
                    case "Equals":
                        if (attributeType.equals("primitive")) {
                            matchClause += String.format("AND toString(n.%s) = '%s' ", attribute, searchKey);
                        } else {
                            // attributeType.equals("instance")
                            matchClause += String.format("AND (toString(q%d.displayName) = '%s' OR toString(q%d.dbId) = '%s') ",
                                    i, searchKey, i, searchKey);
                        }
                        break;
                    case "!=":
                        if (attributeType.equals("primitive")) {
                            matchClause += String.format("AND toString(n.%s) <> '%s' ", attribute, searchKey);
                        } else {
                            // attributeType.equals("instance")
                            matchClause += String.format("AND (toString(q%d.displayName) <> '%s' AND toString(q%d.dbId) <> '%s') ",
                                    i, searchKey, i, searchKey);
                        }
                        break;
                    case "Contains":
                        if (attributeType.equals("primitive")) {
                            matchClause += String.format("AND toString(n.%s) =~ '(?i).*%s.*' ", attribute, searchKey);
                        } else {
                            // attributeType.equals("instance")
                            matchClause += String.format("AND (toString(q%d.displayName) =~ '(?i).*%s.*' OR toString(q%d.dbId) =~ '(?i).*%s.*') ",
                                    i, searchKey, i, searchKey);
                        }
                        break;
                    case "Does Not Contain":
                        if (attributeType.equals("primitive")) {
                            matchClause += String.format("AND toString(n.%s) !~ '(?i).*%s.*' ", attribute, searchKey);
                        } else {
                            // attributeType.equals("instance")
                            matchClause += String.format("AND (toString(q%d.displayName) !~ '(?i).*%s.*' AND toString(q%d.dbId) !~ '(?i).*%s.*') ",
                                    i, searchKey, i, searchKey);
                        }
                        break;
                    case "Use REGEXP":
                        if (attributeType.equals("primitive")) {
                            matchClause += String.format("AND toString(n.%s) =~ '%s' ", attribute, searchKey);
                        } else {
                            // attributeType.equals("instance")
                            matchClause += String.format("AND (toString(q%d.displayName) =~ '%s' OR toString(q%d.dbId) =~ '%s') ",
                                    i, searchKey, i, searchKey);
                        }
                        break;
                    case "IS NOT NULL":
                    case "IS NULL":
                        matchClause += String.format("AND n.%s %s ", attribute, operand);
                        break;
                    default:
                }
            }
        }
        // Assemble the full query
        String query = queryRoot + queryReturnClause;
        if (!matchClause.equals("")) {
            query += matchClausePrefix + matchClause + matchClauseSuffix;
        }
        // Execute the query
        Collection<Map<String, Object>> all = neo4jClient.query(query)
                .fetch()
                .all();
        // Populate parentDbId2DbId2SimpleInstance with query results
        for (Map<String, Object> map : all) {
            Long parentDbId = Long.parseLong(map.get("p.dbId").toString());
            if (!parentDbId2DbId2SimpleInstance.keySet().contains(parentDbId)) {
                parentDbId2DbId2SimpleInstance.put(parentDbId, new HashMap());
            }
            SimpleInstance inst = new SimpleInstance();
            inst =  populateAttrValues(inst, map);
            if (parentDbId2DbId2SimpleInstance.get(parentDbId).keySet().contains(inst.getDbId())) {
                SimpleInstance previouslyFoundInstance = parentDbId2DbId2SimpleInstance.get(parentDbId).get(inst.getDbId());
                if (previouslyFoundInstance.getAttributes().containsKey("match") &&
                        previouslyFoundInstance.getAttribute("match").toString() == "false") {
                    // Note that the query may return the same instance multiple times, each time for a given value
                    // of a multivalued Instance field - and in the case only one of those Instance values matches
                    // our query - we want to find the one that does match
                    parentDbId2DbId2SimpleInstance.get(parentDbId).put(inst.getDbId(), inst);
                }
            } else {
                parentDbId2DbId2SimpleInstance.get(parentDbId).put(inst.getDbId(), inst);
            }
        }
        return parentDbId2DbId2SimpleInstance;
    }


    /**
     * Populate into hasEvent field of inst the list of children (recursively)
     * @param inst
     * @param parentDbId2DbId2SimpleInstance
     * @param recursive
     * @return flag to indicate that at least one child (recursively) matched searchKey
     * (see: getTopEvents() and getAllEvents()
     */
    private boolean populateChildren(
            SimpleInstance inst,
            Map<Long, Map<Long, SimpleInstance>> parentDbId2DbId2SimpleInstance,
            boolean recursive) {
        boolean expandNodeFlag = false;
        Long dbId = inst.getDbId();
        if (parentDbId2DbId2SimpleInstance.keySet().contains(dbId)) {
            List<SimpleInstance> childEvents = new ArrayList(parentDbId2DbId2SimpleInstance.get(dbId).values());
            inst.setAttribute("hasEvent", childEvents);
            if (recursive) {
                for (SimpleInstance childInst : childEvents) {
                    if (!expandNodeFlag && childInst.getAttributes().containsKey("match") &&
                            childInst.getAttribute("match").toString() == "true") {
                        expandNodeFlag = true;
                    }
                    boolean match = populateChildren(childInst, parentDbId2DbId2SimpleInstance, recursive);
                    if (!expandNodeFlag && match) {
                        expandNodeFlag = true;
                    }
                }
            }
            if (expandNodeFlag) {
                // If any of inst's children (recursively) have match attribute set to true,
                // set inst's attribute expand to true also - this will be used by the front-end
                // to check which tree nodes should be expanded
                inst.getAttributes().put("expand", expandNodeFlag);
            }
        }
        return expandNodeFlag;
    }

    /**
     *
     * @param speciesName
     * @param className
     * @param attributes
     * @param attributeTypes
     * @param operands
     * @param searchKeys
     * @return List of top events with their respective children populated into their hasEvent attribute, recursively,
     * where the top event matches speciesName (or any species if speciesName == "All"),
     * and the nodes:
     * 1. In the case attributeType == "primitive" - with attribute matching searchKey, or
     * 2. In the case of attributeType == "instance" - with either dbId or displayName
     *    in any node related to the node of className via relationship attribute - matching searchKey:
     * have attribute match set to true, and all parents of the
     * those matching nodes have attribute expand set to true.
     */
    public List<SimpleInstance> getEventTree(
            String speciesName,
            String className,
            String attributes,
            String attributeTypes,
            String operands,
            String searchKeys) {
        logger.info("Getting TopLevelPathway events..");
        List<SimpleInstance> topEvents = getTopEvents(speciesName);
        Integer topEventsCount = topEvents.size();
        logger.info(String.format("Retrieved %d top events. Getting all events..", topEventsCount));
        Map<Long, Map<Long, SimpleInstance>> parentDbId2DbId2SimpleInstance =
                getAllEvents(className, attributes, attributeTypes, operands, searchKeys);
        logger.info(String.format("Retrieved events for %d parents. Building events tree..",
                parentDbId2DbId2SimpleInstance.keySet().size()));
        for (SimpleInstance inst: topEvents) {
            populateChildren(inst, parentDbId2DbId2SimpleInstance, true);
        }
        logger.info("Events tree is ready.");
        return topEvents;
    }

    /**
     * @param dbId
     * @return For a given event identified by dbId, a list of "nodes" and "edges", that themselves are maps of node/edge
     * attribute->value respectively
     */
    public Map<String, List<Map<String, Object>>> getHierarchicalPlotData(Long dbId) {
        // Lists of nodes and edges that will be accumulated from the result of the cypher query below
        List<Map<String, Object>> nodes = new ArrayList();
        List<Map<String, Object>> edges = new ArrayList();
        // A map of node displayName to the id that is unique within the plot that will be generated in the front end
        // This map is needed to prevent node duplicates (different id but the same displayName)
        Map<String, String> node2Id = new HashMap();
        // Data structure that will be returned by this function
        Map<String, List<Map<String, Object>>> ret = new HashMap();
        // Retrieve all events in hasEvent relationship with the event dbId, and their preceding events (if they exist)
        String query = String.format("MATCH(n:Event{dbId:%d})-[r:hasEvent]->(e:Event) " +
                "OPTIONAL MATCH (e:Event)-[:precedingEvent]->(c:Event) " +
                "RETURN e.dbId, e.schemaClass, e.displayName, c.dbId, c.displayName", dbId);
        // Execute the query
        Collection<Map<String, Object>> all = neo4jClient.query(query)
                .fetch()
                .all();

        // Get edges and nodes
        Integer id = 0;
        for (Map<String, Object> map : all) {
            // label2DbId is needed to store dbId for each node label - used to construct a schema_view/instance/dbId
            // link below
            Map<String, String> label2DbId = new HashMap();
            String source = null;
            String sourceDbId;
            String schemaClass = map.get("e.schemaClass").toString();
            String target = wordWrap(map.get("e.displayName").toString());
            String targetDbId = map.get("e.dbId").toString();
            label2DbId.put(target, targetDbId);
            if (map.get("c.displayName") != null) {
                source = wordWrap(map.get("c.displayName").toString());
                sourceDbId = map.get("c.dbId").toString();
                label2DbId.put(source, sourceDbId);
            }
            // Create the node if it doesn't already exist
            for (String label : label2DbId.keySet()) {
                String labelDbId = label2DbId.get(label);
                if (label != null && !node2Id.keySet().contains(label)) {
                    node2Id.put(label, id.toString());
                    Map<String, Object> node = new HashMap();
                    node.put("class", schemaClass);
                    node.put("id", id.toString());
                    node.put("label", label);
                    node.put("description", String.format("%s: %s", schemaClass, label));
                    node.put("url", String.format("http://localhost:4200/schema_view/instance/%s", labelDbId));
                    nodes.add(node);
                    id++;
                }
            }
            // Create an edge between the preceding event's node and the node above
            if (source != null) {
                String sourceId = node2Id.get(source);
                String targetId = node2Id.get(target);
                Map<String, Object> edge = new HashMap();
                edge.put("source", sourceId);
                edge.put("target", targetId);
                edge.put("width", 1.0);
                edge.put("edgeEndShape", "black_arrow");
                edges.add(edge);
            }
        }
        ret.put("nodes", nodes);
        ret.put("edges", edges);
        return ret;
    }

    /**
     *
     * @param dbId
     * @return For a given Reaction event identified by dbId, a list of "nodes" and "edges", that themselves are maps of node/edge
     *      * attribute->value respectively
     */
    public Map<String, List<Map<String, Object>>> getReactionPlotData(Long dbId) {
        // Lists of nodes and edges that will be accumulated from the result of the cypher query below
        List<Map<String, Object>> nodes = new ArrayList();
        List<Map<String, Object>> edges = new ArrayList();
        // A map of node displayName to the id that is unique within the plot that will be generated in the front end
        // This map is needed to prevent node duplicates (different id but the same displayName)
        Map<String, String> node2Id = new HashMap();
        // Data structure that will be returned by this function
        Map<String, List<Map<String, Object>>> ret = new HashMap();
        // A cypher query to retrieve all the relevant data for the Reaction plot in the front end
        String query = String.format("MATCH(n:Reaction{dbId:%d}) " +
                "OPTIONAL MATCH (n)-[ri:input]->(i) " +
                "OPTIONAL MATCH (i)-[:crossReference]->(icr) " +
                "OPTIONAL MATCH (i)-[:referenceEntity]->(ire) " +
                "OPTIONAL MATCH (n)-[ro:output]->(o) " +
                "OPTIONAL MATCH (o)-[:crossReference]->(ocr) " +
                "OPTIONAL MATCH (o)-[:referenceEntity]->(ore) " +
                "OPTIONAL MATCH (n)-[:regulatedBy]->(reg) " +
                "OPTIONAL MATCH (reg)-[:regulator]->(r) " +
                "OPTIONAL MATCH (r)-[:crossReference]->(rcr) " +
                "OPTIONAL MATCH (r)-[:referenceEntity]->(rre) " +
                "OPTIONAL MATCH (n)-[:catalystActivity]->(cat) " +
                "OPTIONAL MATCH (cat)-[:physicalEntity]->(c) " +
                "OPTIONAL MATCH (c)-[:crossReference]->(ccr) " +
                "OPTIONAL MATCH (c)-[:referenceEntity]->(cre) " +
                "RETURN n.displayName, n.schemaClass, " +
                "ri.stoichiometry, i.dbId, i.displayName, i.schemaClass, icr.displayName, ire.displayName, " +
                "ro.stoichiometry, o.dbId, o.displayName, o.schemaClass, ocr.displayName, ore.displayName ," +
                "reg.schemaClass, r.dbId, r.displayName, r.schemaClass, rcr.displayName, rre.displayName, " +
                "cat.schemaClass, c.dbId, c.displayName, c.schemaClass, ccr.displayName, cre.displayName", dbId);

        // Execute the query
        Collection<Map<String, Object>> all = neo4jClient.query(query)
                .fetch()
                .all();

        // Add 3 dummy nodes: central Reaction one (populated when the cypher query results are processed below),
        // plus two nodes for inputs to go into/outputs to come out from respectively
        Map<String, Object>  node = new HashMap();
        node.put("class", "DummyIO");
        node.put("id", "1");
        nodes.add(node);
        node2Id.put("inputsTarget", "1");
        // outputsSource node
        node = new HashMap();
        node.put("class", "DummyIO");
        node.put("id", "2");
        nodes.add(node);
        node2Id.put("outputsSource", "2");

        Integer id = 3;
        // Now connect via edges DummyCentral to DummyInputsTarget and DummyOutputsSource
        Map<String, Object> edge = new HashMap();
        String sourceId = node2Id.get("inputsTarget");
        String targetId = "0";
        edge.put("edgeEndShape","");
        edge.put("source", sourceId);
        edge.put("target", targetId);
        edge.put("width", 1.0);
        edge.put("targetAnchor", "0");
        edges.add(edge);
        edge = new HashMap();
        sourceId = "0";
        targetId = node2Id.get("outputsSource");
        edge.put("edgeEndShape","");
        edge.put("source", sourceId);
        edge.put("target", targetId);
        edge.put("width", 1.0);
        edge.put("sourceAnchor", "4");
        edges.add(edge);

        // Get edges and nodes
        for (Map<String, Object> map : all) {
            // label2DbId is needed to store dbId for each node label - used to construct a schema_view/instance/dbId
            // link below
            Map<String, String> label2DbId = new HashMap();
            // prefixes from the cypher query above, in the order in which they should be processed
            String[] prefixes =
                    {"n", "ri", "icr", "ire", "i", "ro", "ocr", "ore", "o", "reg", "r", "rcr", "rre", "cat", "c", "ccr", "cre"};
            // Used to store schemaClass of either regulatedBy or catalystActivity instance
            String regCatSchemaClass = "";
            // Used to store stoichiometry of either input or output relationship
            String ioStoichiometry = "";
            String schemaClass = null;
            String displayName;
            for (String prefix : prefixes) {
                if (prefix.equals("n")) {
                    // Populate description of the central dummy node
                    schemaClass = map.get(String.format("%s.schemaClass", prefix)) != null ?
                            map.get(String.format("%s.schemaClass", prefix)).toString() : null;
                    displayName = map.get(String.format("%s.displayName", prefix)).toString();
                    if (!node2Id.keySet().contains("central")) {
                        node2Id.put("central", "0");
                        node = new HashMap();
                        node.put("class", "DummyCentral");
                        node.put("id", "0");
                        node.put("description", schemaClass + ": " + displayName);
                        nodes.add(node);
                    }
                    schemaClass = null;
                } else if (prefix.endsWith("cr") || prefix.endsWith("re")) {
                    // process crossReference and referenceEntity - their class will affect the node shape
                    // in the Reaction plot in the front-end
                    if (map.get(String.format("%s.displayName", prefix)) != null) {
                        String dispName = map.get(String.format("%s.displayName", prefix)).toString();
                        if (dispName.startsWith("COMPOUND")) {
                            schemaClass = "Compound";
                        } else if (dispName.startsWith("UniProt")) {
                            schemaClass = "Protein";
                        } else if (dispName.startsWith("ENSEMBL")) {
                            if (dispName.contains("ENSG")) {
                                schemaClass = "Gene";
                            } else if (dispName.contains("ENST")) {
                                schemaClass = "RNA";
                            }
                        } else if (dispName.contains("RNA")) {
                            schemaClass = "RNA";
                        }
                    }
                } else if (prefix.equals("ri") || prefix.equals("ro")) {
                    // Retrieve stoichiometry from input/output relationships
                    Object stoichiometry = map.get(String.format("%s.stoichiometry", prefix));
                    ioStoichiometry = stoichiometry != null && Integer.parseInt(stoichiometry.toString()) > 1 ?
                            map.get(String.format("%s.stoichiometry", prefix)).toString() : "";
                } else if (map.get(String.format("%s.schemaClass", prefix)) != null) {
                    if (prefix.equals("reg") || prefix.equals("cat")) {
                        // Retrieve schemaClass from either regulatedBy or catalystActivity instance - it will affect
                        // the edge-end shape between it and the central node - in the Reaction plot in the front end
                        regCatSchemaClass = map.get(String.format("%s.schemaClass", prefix)).toString();
                    } else {
                        // Deal with all the remaining prefixes for which schemaClass, displayName and dbId are returned
                        // by the cypher query above
                        if (schemaClass == null) {
                            // Note that schemaClass may have been populated from crossReference and referenceEntity above
                            // as that's more specific, it trumps the schema class retrieved here - hence the null check above.
                            schemaClass = map.get(String.format("%s.schemaClass", prefix)) != null ?
                                    map.get(String.format("%s.schemaClass", prefix)).toString() : null;
                        }
                        displayName = map.get(String.format("%s.displayName", prefix)) != null ?
                                wordWrap(map.get(String.format("%s.displayName", prefix)).toString()) : null;

                        String nodeDbId = map.get(String.format("%s.dbId", prefix)) != null ?
                                map.get(String.format("%s.dbId", prefix)).toString() : null;
                        String displayNamePlusStoichiometry = displayName;
                        if (ioStoichiometry != "" && (prefix.equals("i") || prefix.equals("o"))) {
                            displayNamePlusStoichiometry = ioStoichiometry + " " + displayName;
                        }
                        String prefixPlusDisplayName = prefix + displayName;
                        label2DbId.put(prefixPlusDisplayName, nodeDbId);
                        // Create the node
                        if (!node2Id.keySet().contains(prefixPlusDisplayName)) {
                            // NB. Sometimes the same displayName can be shared between e.g. input and catalystActivity,
                            // but within a give input/output/catalystActivity/regulatedBy group of nodes, each displayName
                            // must be unique.
                            node2Id.put(prefixPlusDisplayName, id.toString());
                            node = new HashMap();
                            node.put("class", schemaClass);
                            node.put("id", id.toString());
                            // In input/output node names in the plot we want to display stoichiometry, if that's > 1
                            node.put("label", displayNamePlusStoichiometry);
                            node.put("description", String.format("%s: %s", schemaClass, displayName));
                            node.put("url", String.format("http://localhost:4200/schema_view/instance/%s", label2DbId.get(prefixPlusDisplayName)));
                            nodes.add(node);
                            id++;

                            // Create the edge
                            edge = new HashMap();
                            if (prefix.equals("o")) {
                                sourceId = "2";
                                targetId = node2Id.get(prefixPlusDisplayName);
                            } else if (prefix.equals("i")) {
                                sourceId = node2Id.get(prefixPlusDisplayName);
                                targetId = "1";
                            } else {
                                sourceId = node2Id.get(prefixPlusDisplayName);
                                targetId = "0";
                            }
                            // N.B. edge-end shape is dictated by the value of regCatSchemaClass, unless it is the output edge -
                            // in which case it gets a black arrow end
                            edge.put("edgeEndShape",
                                    prefix.equals("c") && regCatSchemaClass.equals("CatalystActivity") ? "circle" :
                                            regCatSchemaClass.equals("PositiveRegulation") ? "white_arrow" :
                                                    regCatSchemaClass.equals("NegativeRegulation") ? "pipe" :
                                                            sourceId.equals("2") ? "black_arrow" : "");
                            edge.put("source", sourceId);
                            edge.put("target", targetId);
                            if (prefix.equals("i") || prefix.equals("o")) {
                                // For input/output nodes, if label the edge with stoichimetry, if that's > 1
                                edge.put("stoichiometry", ioStoichiometry);
                            } else {
                                // This below is to spread the entry points of different types of edges across the
                                // central node (so that edge-end shapes - e.g. white circle, white arrow or a line)
                                // don't end up onscuring each other
                                if (regCatSchemaClass.equals("CatalystActivity")) {
                                    edge.put("targetAnchor", "1");
                                } else if (regCatSchemaClass.equals("NegativeRegulation")) {
                                    edge.put("targetAnchor", "2");
                                } else if (regCatSchemaClass.equals("PositiveRegulation")) {
                                    edge.put("targetAnchor", "3");
                                } else {
                                    edge.put("targetAnchor", "0");
                                }
                            }

                            edge.put("width", 1.0);
                            edges.add(edge);
                            ioStoichiometry = "";
                            regCatSchemaClass = "";
                            schemaClass = null;
                        }
                    }
                }
            }
        }
        ret.put("nodes", nodes);
        ret.put("edges", edges);
        return ret;
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
     * Update the DatabaseObject stored in the database. The current implementation is first to delete
     * this object and then store it. This two-step process probably is the cleanest and the most simply 
     * one, but with performance penalty. 
     * @param obj
     * @return The original DB_ID should be returned if update works fine.
     * @throws Exception
     * TODO: Make sure there is only one transaction applied.
     */
    @Transactional
    public DatabaseObject update(DatabaseObject obj) throws Exception {
        boolean deleted = delete(obj);
        if (!deleted)
            throw new IllegalStateException("Cannot delete the object first to update: " + 
                                obj.getDisplayName() + " [" + obj.getDbId() + "]");
        return store(obj);
    }
    
    /**
     * Though the client to this object can call update or store, it is recommended to
     * call this method directly and let it to figure out the passed object should be 
     * store or update.
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
     * Get the list of class names in the database.
     * @return
     */
    public List<String> getSchemaClasses() {
        return neo4jClient.query(LIST_CLASSES_QUERY).fetchAs(String.class).all().stream().collect(Collectors.toList());
    }

    /**
     * Use this method to create index for DatabaseObject's DB_ID. 
     */
    public void createDbIdIndex() {
        // This should be called once so the query is kept here
        String query = "CREATE INDEX db_id_index IF NOT EXISTS FOR (n:DatabaseObject) ON (n.dbId)";
        neo4jClient.query(query).run(); // Nothing is needed but still need to get something. Otherwise Cypher is not sent.
        // Create another index for _displayName for named based search (e.g. contains)
        query = "CREATE TEXT INDEX databaseobject_text_index_displayname IF "
                + "NOT EXISTS FOR (n:DatabaseObject) ON (n.displayName)";
        neo4jClient.query(query).run(); 
//        // Create range index for order by displayName
//        query = "CREATE RANGE INDEX databaseobject_range_index_displayname IF NOT EXISTS for (n:DatabaseObject) on (n.displayName)";
//        neo4jClient.query(query).run();
        // For node lookup: by creating this index, we limit the search! (try profile in cypher!).
        query = "CREATE LOOKUP INDEX node_label_lookup_index IF NOT EXISTS FOR (n) ON EACH labels(n)";
        neo4jClient.query(query).run();
    }

    /**
     * Try and word-wrap by spaces to lines no longer than 20 chars. If that doesn't work (for example
     * for very long complex names that don't have spaces in them), split label by commas, and within
     * each line further split it by hyphens, up to the max length of 20 chars.
     * @param label
     * @return label with words wrapped as described above
     */
    private String wordWrap(String label) {
        String ret = WordUtils.wrap(label, 20);
        if (ret.length() == label.length()) {
            // No spaces in label - in that case:
            // First split on commas
            String[] arr = label.split(",");
            // Then, in case any part after split is still longer than 20 char, split it into subparts
            // that are at most 20 chars
            StringBuilder sb = new StringBuilder();
            for (String part : arr) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(WordUtils.wrap(part.replaceAll("-"," "), 20)
                        .replaceAll("\n","-\n"));
            }
            ret = sb.toString();
        }
        return ret;
    }
}
