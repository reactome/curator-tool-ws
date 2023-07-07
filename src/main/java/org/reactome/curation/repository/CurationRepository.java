package org.reactome.curation.repository;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.neo4j.cypherdsl.core.Cypher;
import org.neo4j.cypherdsl.core.Node;
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReading;
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingUpdate;
import org.neo4j.cypherdsl.core.renderer.Renderer;
import org.reactome.curation.exceptions.DatabaseObjectNotFoundException;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.service.helper.StoichiometryObject;
import org.reactome.server.graph.service.util.DatabaseObjectUtils;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.neo4j.core.Neo4jTemplate;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import lombok.Data;


/**
 * Apparently the default auto-generated code from Neo4jRepository cannot be used for our purpose.
 * It is extreme to load the whole reference graph for a object and cannot find a simple way to control
 * it. Therefore, we may need to write our own cyphery based CRUD operations. All read-only queries
 * should be based on graph-core.
 * We use a class, instead of extending Neo4jRepository interface, to implement this curation repository so that
 * we have a much better control (e.g. handling dbId, etc). This is a better approach.
 * @author wug
 *
 */
@Repository
@Data
public class CurationRepository {
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
     * Store a new DatabaseObject. The DatabaseObject should be new and doesn't have a POSITIVE
     * dbId assigned yet. The implementation of this method is based on dsl to avoid loading
     * value object to reduce the query time.
     * TODO: If the passed obj has a new DatabaseObject referred, this method will not return the DB_ID for that object.
     * For the time being, the client should call findByDbId to reload the passed object to get the DB_ID for that object.
     * This may need to change in the future. 
     * Note: There is a bug in the original graph-core code regarding the order of StoichiometryObject
     * used in input, output, and hasComponent. The order is _displayName based, which most likely is
     * not true. This needs a further investigation.
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
    public Long store(DatabaseObject obj) throws Exception {
        if (obj.getDbId() != null && obj.getDbId() > 0)
            throw new IllegalArgumentException(obj + " has dbId set. Use update to update its content in the database.");
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
        obj.setDbId(nextDbId());
        // To do save, we create a DatabaseObject without relationships first
        DatabaseObject proxyNode = obj.getClass().getConstructor().newInstance();
        for (String field : field2value.keySet()) {
            if (field2rel.containsKey(field)) 
                continue; // Defer this for the time being
            // Escape schemaClass
            if (field.equals("schemaClass"))
                continue;
            Object value = field2value.get(field);
            Method method = obj.getClass().getMethod("set" + field.substring(0, 1).toUpperCase() + field.substring(1),
                    value.getClass());
            if (method == null)
                throw new IllegalStateException("Cannot find method to set " + field + " in class, " + obj.getClass().getSimpleName());
            method.invoke(proxyNode, value);
        }
        // Don't forget to copy the dbId there. It is not in the above loop.
        proxyNode.setDbId(obj.getDbId());
        neo4jTemplate.save(proxyNode);
        // Working on relationships 
        Node objNode = Cypher.node(getNodeLabel(obj)).named(getNodeName(obj)).withProperties("dbId", Cypher.literalOf(obj.getDbId()));
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
        return obj.getDbId();
    }
    
    /**
     * A helper to store value object. This method is used as an helper for recursive calling of store(DatabaseObject).
     * @param value
     * @return
     * @throws Exception
     */
    private Long storeValueObj(Object value) throws Exception {
        if (value instanceof DatabaseObject) {
            DatabaseObject valueObj = (DatabaseObject) value;
            if (valueObj.getDbId() == null || valueObj.getDbId() < 0)
                return store(valueObj); // We should not Spring creates another transaction for this call (see https://www.marcobehler.com/guides/spring-transaction-management-transactional-in-depth)
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
        String clsName = obj.getClass().getSimpleName();
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
    }

}
