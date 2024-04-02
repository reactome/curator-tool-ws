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
     * @param searchKey
     * @return List of top event instances that match speciesName (or any species if speciesName == "All").
     *
     */
    private List<SimpleInstance> getTopEvents(String speciesName, String searchKey) {
        String query =
                String.format("MATCH (n:Event) WHERE %s NOT (n)<-[:hasEvent]-() RETURN n.dbId, " +
                "n.displayName, n.schemaClass, n.speciesName, n._doRelease %s",
                        !speciesName.equalsIgnoreCase("All") ?
                                String.format("n.speciesName = '%s' and ", speciesName) :
                                "",
                        searchKey != null ?
                                String.format(", CASE WHEN n.displayName =~ '(?i).*%s.*' THEN true ELSE false END as match", searchKey) :
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
     * Note that in each returned instance a match attribute is set to true if that instance's displayName
     * contains searchKey
     *
     * @param searchKey
     * @return a map of parent dbId's to the list of events, related to that parent via a hasEvent relationship
     */
    private Map<Long, List<SimpleInstance>> getAllEvents(String searchKey) {
        Map<Long, List<SimpleInstance>> parentDbId2SimpleInstances = new HashMap();
        String query =
                String.format("MATCH (p:Event)-[r:hasEvent]->(n:Event) RETURN DISTINCT p.dbId, " +
                                "n.dbId, n.displayName, n.schemaClass, n.speciesName, n._doRelease, r.order, r.stoichiometry %s ",
                        searchKey != null ?
                                // TODO: confirm if the regex match needs to be case-insensitive or not: '(?i).*%s.*' vs '.*%s.*'
                                String.format(", CASE WHEN n.displayName =~ '.*%s.*' THEN true ELSE false END as match", searchKey) :
                                "");


        Collection<Map<String, Object>> all = neo4jClient.query(query)
                .fetch()
                .all();
        List<SimpleInstance> rtn = new ArrayList<>();
        for (Map<String, Object> map : all) {
            Long parentDbId = Long.parseLong(map.get("p.dbId").toString());
            if (!parentDbId2SimpleInstances.keySet().contains(parentDbId)) {
                parentDbId2SimpleInstances.put(parentDbId, new ArrayList<SimpleInstance>());
            }
            SimpleInstance inst = new SimpleInstance();
            inst =  populateAttrValues(inst, map);
            parentDbId2SimpleInstances.get(parentDbId).add(inst);
        }
        return parentDbId2SimpleInstances;
    }


    /**
     * Populate into hasEvent field of inst the list of children (recursively)
     * @param inst
     * @param parentDbId2SimpleInstances
     * @param recursive
     * @return flag to indicate that at least one child (recursively) matched searchKey
     * (see: getTopEvents() and getAllEvents()
     */
    private boolean populateChildren(
            SimpleInstance inst,
            Map<Long, List<SimpleInstance>> parentDbId2SimpleInstances,
            boolean recursive) {
        boolean expandNodeFlag = false;
        Long dbId = inst.getDbId();
        if (parentDbId2SimpleInstances.keySet().contains(dbId)) {
            List<SimpleInstance> childEvents = parentDbId2SimpleInstances.get(dbId);
            inst.setAttribute("hasEvent", childEvents);
            if (recursive) {
                for (SimpleInstance childInst : childEvents) {
                    if (!expandNodeFlag && childInst.getAttributes().containsKey("match") &&
                            childInst.getAttributes().get("match").toString() == "true") {
                        expandNodeFlag = true;
                    }
                    boolean match = populateChildren(childInst, parentDbId2SimpleInstances, recursive);
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
     * @param searchKey
     * @return List of top events with their respective children populated into their hasEvent attribute, recursively,
     * where the top event matches speciesName (or any species if speciesName == "All"),
     * and the nodes matching searchKey have attribute match set to true, and all parents of the
     * those matching nodes have attribute expand set to true.
     */
    public List<SimpleInstance> getEventTree(String speciesName, String searchKey) {
        logger.info("Getting top events..");
        List<SimpleInstance> topEvents = getTopEvents(speciesName, searchKey);
        Integer topEventsCount = topEvents.size();
        logger.info(String.format("Retrieved %d top events. Getting all events..", topEventsCount));
        Map<Long, List<SimpleInstance>> parentDbId2SimpleInstances = getAllEvents(searchKey);
        logger.info(String.format("Retrieved events for %d parents. Building events tree..",
                parentDbId2SimpleInstances.keySet().size()));
        for (SimpleInstance inst: topEvents) {
            populateChildren(inst, parentDbId2SimpleInstances, true);
        }
        logger.info("Events tree is ready.");
        return topEvents;
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

}
