package org.reactome.curation.repository;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.gk.model.ReactomeJavaConstants;
import org.neo4j.cypherdsl.core.Condition;
import org.neo4j.cypherdsl.core.Cypher;
import org.neo4j.cypherdsl.core.Functions;
import org.neo4j.cypherdsl.core.Node;
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReading;
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingUpdate;
import org.reactome.curation.exceptions.DatabaseObjectNotFoundException;
import org.reactome.curation.exceptions.DatabaseObjectTypeMismatchException;
import org.reactome.curation.model.CurationAttribute.DefiningAttributeValue;
import org.reactome.curation.util.CuratorToolWSUtils;
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
     * Build a Cypher relationship pattern between "(inst)" and the given target alias
     * (empty string for an anonymous node) for an "instance"-typed attribute used in
     * {@link #listInstances}, using the actual Neo4j relationship type and direction
     * from the graph-core domain class's @Relationship annotation rather than assuming
     * the attribute name IS the relationship type.
     *
     * This matters because some attributes are reverse views of a differently-named
     * relationship - e.g. Event.inferredFrom is declared as the INCOMING direction of the
     * "inferredTo" relationship (there is no "inferredFrom" relationship in the graph at
     * all), so a pattern built from the literal attribute name silently matches nothing.
     * Falls back to the previous behavior (attribute name as an undirected relationship
     * type) when the domain class or the annotated field can't be resolved, preserving
     * existing behavior for the common case where the attribute name and relationship
     * type coincide.
     */
    private String buildInstanceRelationshipPattern(String className, String attr, String targetAlias) {
        String type = attr;
        String arrowLeft = "-";
        String arrowRight = "-";
        try {
            Class<?> cls = Class.forName(DatabaseObject.class.getPackageName() + "." + className);
            Relationship rel = getField2rel(cls).get(attr);
            if (rel != null) {
                if (!rel.type().isEmpty())
                    type = rel.type();
                if (rel.direction() == Relationship.Direction.INCOMING) {
                    arrowLeft = "<-";
                    arrowRight = "-";
                } else {
                    arrowLeft = "-";
                    arrowRight = "->";
                }
            }
        } catch (ClassNotFoundException e) {
            logger.warn("Could not resolve domain class for schema class '{}' while mapping attribute '{}' " +
                    "to its relationship type; falling back to an undirected pattern: {}", className, attr, e.getMessage());
        }
        return "(inst)" + arrowLeft + "[:`" + type + "`]" + arrowRight + "(" + targetAlias + ")";
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
            Map<String, Object> field2value = CuratorToolWSUtils.getAllFields(obj, true);
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
        if (!existsById(obj.getDbId())) {
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
                        // Check if structureModified slot needs to be updated too
                        if (CuratorToolWSUtils.getStructureRelatedAttributes().contains(referList.getAttributeName())) {
                            this.queryUtilities.downgradeReviewStatusWithStructureChange(referrer, ie, neo4jClient);
                        }
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
    
    @Transactional
    public void addModifiedIE(DatabaseObject target, InstanceEdit ie) throws Exception {
        if (ie.getDbId() == null || ie.getDbId() < 0)
            ie = (InstanceEdit) store(ie); // The cast should be safe
        this.queryUtilities.addModifiedIE(target, ie, neo4jClient);
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
        if (obj.getDbId() != null && existsById(obj.getDbId())) {
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
        Map<String, Object> field2value = CuratorToolWSUtils.getAllFields(obj, false); // Use "false" to avoid empty
        // fields
        // Step 3: Check if any referred object has been deleted, or resolves to a different
        // schema class than expected (see findAnyInvalidValue()'s javadoc for why the latter
        // check matters just as much as the former: either one, left unchecked, would let Step 6
        // silently wipe every relationship of obj instead of just the bad reference).
        findAnyInvalidValue(field2value);
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
            // For multi-valued attribute, in rare case the returned value is a set (e.g. inferredFrom)
            // This should be treated as a bug at the graph data model layer
            List<?> valueList = null;
            if (value instanceof Set)
                valueList = new ArrayList<>((Set)value);
            else if (value instanceof List) {
                valueList = (List<?>)value;
            }
            if (valueList != null) {
                // For list, we need to handle for all and stoichiometry for
                // Complex.hasComponent,
                // Polymer.repeatedUnit, and ReactionlikeEvent.input, output.
                for (int i = 0; i < valueList.size(); i++) {
                    var tmp = valueList.get(i);
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
     * Make sure every object value referred by a DatabaseObject being saved both still exists
     * AND is stored under the schema class the caller expects - not just that some node with
     * that dbId exists, which is all the older existsById()-only check verified.
     * <p>
     * That distinction matters because of how store()'s Step 6 recreates relationships: it
     * chains one Cypher MATCH per relationship target, then a single CREATE for all of them, in
     * ONE query (see handleValueObj()). Plain (non-OPTIONAL) MATCH clauses are effectively ANDed
     * together - if even one target's MATCH (label + dbId) fails to find a node, because e.g. a
     * stale/mistyped dbId, or two distinct new objects colliding on the same client-assigned
     * placeholder id, resolved it to the wrong class (say a Complex reference that's actually
     * stored as an InstanceEdit) - the WHOLE query returns zero rows, silently discarding every
     * relationship of the object being saved, not just the mismatched one. Since resetNode()
     * (called by update() right before store()) has already deleted the old relationships by
     * that point, the net effect is the object being reduced to a shell.
     * <p>
     * Throwing here, before Step 4/5/6 run, lets @Transactional roll back whatever resetNode()
     * already did instead of leaving that shell behind.
     * <p>
     * This deliberately checks actual Neo4j LABELS (via fetchNodeLabels()), not the "schemaClass"
     * property (fetchSchemaClasses()) checked by an earlier version of this method. A brand-new
     * referenced object - e.g. the InstanceEdit for "modified", created via
     * CurationController.commit()'s two-phase flow (storeShell() first to mint its dbId and a
     * bare node, THEN a later, separate commit() call that fills in its real properties
     * including "schemaClass" via storeNodeProperties()) - already carries the correct label the
     * moment storeShell() saves it, well before that second call runs. The object being saved
     * here may well be committed (Step 4 in CurationController.commit()) BEFORE that second call
     * (Step 5) reaches the new InstanceEdit, so relying on "schemaClass" being set yet would
     * reject this entirely normal, in-progress creation as if the reference were missing.
     */
    private void findAnyInvalidValue(Map<String, Object> field2value) {
        List<DatabaseObject> referenced = collectReferencedObjects(field2value);
        if (referenced.isEmpty())
            return;
        List<Long> dbIds = referenced.stream().map(DatabaseObject::getDbId).distinct().collect(Collectors.toList());
        Map<Long, Set<String>> actualLabels = fetchNodeLabels(dbIds);
        DatabaseObject notFound = findMissingReference(referenced, actualLabels);
        if (notFound != null)
            throw new DatabaseObjectNotFoundException(notFound);
        DatabaseObject[] mismatch = findMismatchedReference(referenced, actualLabels);
        if (mismatch != null)
            throw new DatabaseObjectTypeMismatchException(mismatch[0], mismatch[0].getSchemaClass(),
                    String.valueOf(actualLabels.get(mismatch[0].getDbId())));
    }

    /**
     * Batch-fetches the actual Neo4j labels for a set of dbIds - unlike fetchSchemaClasses()'s
     * "schemaClass" property, every node carries its labels from the moment it's created (see
     * findAnyInvalidValue()'s javadoc), making labels the reliable signal for "does this dbId
     * exist, and as what class" even for an object that's only been storeShell()'d so far.
     */
    Map<Long, Set<String>> fetchNodeLabels(List<Long> dbIds) {
        String cypher = "MATCH (d:DatabaseObject) WHERE d.dbId IN $ids RETURN d.dbId AS dbId, labels(d) AS labels";
        Collection<Map<String, Object>> results = neo4jClient.query(cypher).bind(dbIds).to("ids").fetch().all();
        Map<Long, Set<String>> id2Labels = new HashMap<>();
        for (Map<String, Object> map : results) {
            Long dbId = Long.parseLong(map.get("dbId").toString());
            Object labelsObj = map.get("labels");
            Set<String> labels = new HashSet<>();
            if (labelsObj instanceof Collection) {
                for (Object label : (Collection<?>) labelsObj)
                    labels.add(label.toString());
            }
            id2Labels.put(dbId, labels);
        }
        return id2Labels;
    }

    /**
     * Walks every relationship-typed attribute value in field2value and collects the referenced
     * DatabaseObjects that have a real (positive) dbId - i.e. existing objects the object being
     * saved points to, as opposed to brand-new nested objects still awaiting a dbId.
     */
    private List<DatabaseObject> collectReferencedObjects(Map<String, Object> field2value) {
        List<DatabaseObject> referenced = new ArrayList<>();
        for (Object value : field2value.values()) {
            if (value instanceof Collection) {
                for (Object item : (Collection<?>) value)
                    addReferencedObject(item, referenced);
            } else {
                addReferencedObject(value, referenced);
            }
        }
        return referenced;
    }

    private void addReferencedObject(Object value, List<DatabaseObject> referenced) {
        DatabaseObject dObj = null;
        if (value instanceof DatabaseObject) {
            dObj = (DatabaseObject) value;
        } else if (value instanceof StoichiometryObject) {
            dObj = ((StoichiometryObject) value).getObject();
        }
        if (dObj != null && dObj.getDbId() != null && dObj.getDbId() > 0) {
            referenced.add(dObj);
        }
    }

    /**
     * Pure lookup, split out from findAnyInvalidValue() so the "does every reference still
     * exist" check is unit-testable without a live database: actualLabels is whatever
     * fetchNodeLabels() returned for referenced's dbIds - a dbId missing from that map means no
     * node with that dbId exists at all.
     */
    DatabaseObject findMissingReference(List<DatabaseObject> referenced, Map<Long, Set<String>> actualLabels) {
        for (DatabaseObject ref : referenced) {
            if (!actualLabels.containsKey(ref.getDbId()))
                return ref;
        }
        return null;
    }

    /**
     * Pure comparison, split out from findAnyInvalidValue() so the class-mismatch check is
     * unit-testable without a live database: actualLabels is whatever fetchNodeLabels() returned
     * for referenced's dbIds. Returns a single-element array holding the first mismatched
     * reference (an array, not the DatabaseObject itself, only so callers can tell "no mismatch"
     * apart from "mismatched, and the actual labels happened to be null").
     */
    DatabaseObject[] findMismatchedReference(List<DatabaseObject> referenced, Map<Long, Set<String>> actualLabels) {
        for (DatabaseObject ref : referenced) {
            Set<String> labels = actualLabels.get(ref.getDbId());
            if (labels == null)
                continue; // Missing entirely - findMissingReference()'s concern, not this one's.
            String expectedClass = ref.getSchemaClass();
            if (expectedClass != null && !labels.contains(expectedClass))
                return new DatabaseObject[] { ref };
        }
        return null;
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
        // Quote so regex metacharacters in the name are matched literally.
        Condition condition = displayNameProp.matches("(?i)" + Pattern.quote(displayName)); // Perform a case insensitive search

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
    @SuppressWarnings({"rawtypes"})
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
            Object clsObj = map.get("schemaClass");
            if (clsObj == null)
                continue;
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
     * Get a list of SimpleInstance objects matching the given filter criteria.
     * Primitive-attribute conditions become WHERE clauses; relationship-attribute
     * IS_NULL checks use a NOT-exists pattern predicate; all other relationship
     * conditions become additional MATCH clauses.
     */
    public InstanceList listInstances(String className,
                                      int skip,
                                      int limit,
                                      List<String> attributes,
                                      List<String> attributeTypes,
                                      List<ListOperand> operands,
                                      List<String> searchKeys) {
        if (attributes.size() != attributeTypes.size() ||
                attributes.size() != operands.size()  ||
                attributes.size() != searchKeys.size()) {
            throw new IllegalArgumentException("All arrays must have the same length");
        }

        StringBuilder cypher = new StringBuilder();
        Map<String, Object> params = new HashMap<>();

        cypher.append("MATCH (inst:").append(className).append(") ");

        List<String> whereClauses = new ArrayList<>();
        List<String> relMatchClauses = new ArrayList<>();

        for (int i = 0; i < attributes.size(); i++) {
            String attr = attributes.get(i);
            String attrType = attributeTypes.get(i);
            ListOperand operand = operands.get(i);
            String key = searchKeys.get(i);
            String paramName = "p" + i;
            String nodeAlias = "n" + i;

            if ("instance".equals(attrType)) {
                switch (operand) {
                    case IS_NULL:
                        // Pattern-existence predicate: no OPTIONAL MATCH needed
                        whereClauses.add("NOT " + buildInstanceRelationshipPattern(className, attr, ""));
                        break;
                    case IS_NOT_NULL:
                        relMatchClauses.add("MATCH " + buildInstanceRelationshipPattern(className, attr, ""));
                        break;
                    case CONTAINS:
                        // Quote the key so regex metacharacters (e.g. '+', '*', '(') in the
                        // search term are matched literally instead of being interpreted as regex.
                        params.put(paramName, "(?i).*" + Pattern.quote(key) + ".*");
                        relMatchClauses.add("MATCH " + buildInstanceRelationshipPattern(className, attr, nodeAlias)
                                + " WHERE " + nodeAlias + ".displayName =~ $" + paramName);
                        break;
                    // case REGEX:
                    // just using the key similar to the "contains" case, but user will add their own characters
                    case EQUAL:
                        params.put(paramName, key);
                        relMatchClauses.add("MATCH " + buildInstanceRelationshipPattern(className, attr, nodeAlias)
                                + " WHERE " + nodeAlias + ".displayName = $" + paramName);
                        break;
                    case NOT_EQUAL:
                        params.put(paramName, key);
                        relMatchClauses.add("MATCH " + buildInstanceRelationshipPattern(className, attr, nodeAlias)
                                + " WHERE " + nodeAlias + ".displayName <> $" + paramName);
                        break;
                    default:
                        break;
                }
            } else if ("list".equals(attrType)) {
                // List-valued primitive properties (e.g. geneName: ["TP53", "P53"]).
                // toString() throws a TypeError on arrays in Neo4j 4.x, so we use ANY().
                switch (operand) {
                    case CONTAINS:
                        // Quote the key so regex metacharacters (e.g. '+', '*', '(') in the
                        // search term are matched literally instead of being interpreted as regex.
                        params.put(paramName, "(?i).*" + Pattern.quote(key) + ".*");
                        whereClauses.add("inst." + attr + " IS NOT NULL AND ANY(x IN inst." + attr + " WHERE x IS NOT NULL AND toString(x) =~ $" + paramName + ")");
                        break;
                    case EQUAL:
                        params.put(paramName, key);
                        whereClauses.add("inst." + attr + " IS NOT NULL AND ANY(x IN inst." + attr + " WHERE toString(x) = $" + paramName + ")");
                        break;
                    case NOT_EQUAL:
                        params.put(paramName, key);
                        whereClauses.add("inst." + attr + " IS NOT NULL AND NONE(x IN inst." + attr + " WHERE toString(x) = $" + paramName + ")");
                        break;
                    case IS_NOT_NULL:
                        whereClauses.add("inst." + attr + " IS NOT NULL AND size(inst." + attr + ") > 0");
                        break;
                    case IS_NULL:
                        whereClauses.add("(inst." + attr + " IS NULL OR size(inst." + attr + ") = 0)");
                        break;
                    default:
                        break;
                }
            } else {
                // Scalar primitive attribute conditions
                switch (operand) {
                    case EQUAL:
                        params.put(paramName, key);
                        whereClauses.add("inst." + attr + " IS NOT NULL AND toString(inst." + attr + ") = $" + paramName);
                        break;
                    case NOT_EQUAL:
                        params.put(paramName, key);
                        whereClauses.add("inst." + attr + " IS NOT NULL AND toString(inst." + attr + ") <> $" + paramName);
                        break;
                    case CONTAINS:
                        // Quote the key so regex metacharacters (e.g. '+', '*', '(') in the
                        // search term are matched literally instead of being interpreted as regex.
                        params.put(paramName, "(?i).*" + Pattern.quote(key) + ".*");
                        whereClauses.add("inst." + attr + " IS NOT NULL AND toString(inst." + attr + ") =~ $" + paramName);
                        break;
                    case IS_NOT_NULL:
                        whereClauses.add("inst." + attr + " IS NOT NULL");
                        break;
                    case IS_NULL:
                        whereClauses.add("inst." + attr + " IS NULL");
                        break;
                    default:
                        break;
                }
            }
        }

        if (!whereClauses.isEmpty())
            cypher.append("WHERE ").append(String.join(" AND ", whereClauses)).append(" ");

        for (String relMatch : relMatchClauses)
            cypher.append(relMatch).append(" ");

        // Collect distinct instances first, then paginate with SKIP/LIMIT
        cypher.append("WITH COUNT(DISTINCT inst) AS totalCount, COLLECT(DISTINCT inst) AS instances ");
        cypher.append("UNWIND instances AS inst ");
        cypher.append("RETURN totalCount, inst.dbId AS `inst.dbId`, inst.displayName AS `inst.displayName`, ");
        cypher.append("inst.schemaClass AS `inst.schemaClass` ");
        cypher.append("ORDER BY inst.displayName SKIP toInteger($skip) LIMIT toInteger($limit)");

        params.put("skip", skip);
        params.put("limit", limit);

        Collection<Map<String, Object>> all = neo4jClient.query(cypher.toString())
                .bindAll(params)
                .fetch().all();

        List<SimpleInstance> instances = new ArrayList<>();
        Integer totalCount = null;
        for (Map<String, Object> map : all) {
            if (totalCount == null)
                totalCount = Integer.parseInt(map.get("totalCount").toString());
            if (map.get("inst.dbId") == null) {
                logger.error("Return result with dbId = null: " + className + ", " + skip + ", " + limit);
                continue;
            }
            instances.add(constructInstance(map, className));
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
                        + "OPTIONAL MATCH (pd:PathwayDiagram)-[:representedPathway]->(n) "
                        + "RETURN n.dbId, n.displayName, n.schemaClass, n.speciesName, n.doRelease, n.releaseDate, "
                        + "CASE WHEN pd IS NOT NULL THEN true ELSE false END as `n.hasDiagram`",
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
    private Map<Long, Map<Long, SimpleInstance>> getAllEvents() {
        Map<Long, Map<Long, SimpleInstance>> parentDbId2DbId2SimpleInstance = new HashMap<>();
        String query = "MATCH (p:Event)-[r:hasEvent]->(n:Event) "
                + "OPTIONAL MATCH (pd:PathwayDiagram)-[:representedPathway]->(n) "
                + "RETURN DISTINCT p.dbId, n.dbId, n.displayName, n.speciesName, "
                + "n.schemaClass, n.doRelease, n.releaseDate, "
                + "CASE WHEN pd IS NOT NULL THEN true ELSE false END as `n.hasDiagram`, r.order";
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
            // Quote so regex metacharacters (e.g. '+') in the search text are matched literally.
            condition = displayName.matches(".*(?i)" + Pattern.quote(text) + ".*");
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
                            .matches(".*(?i)" + Pattern.quote(searchKey) + ".*"));
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
     * (field name, relationship type) pairs resetNode() must never clear, even though
     * they're present in getField2rel(). Each of these fields is one end of a pair of
     * differently-named fields that map to the SAME relationship type from opposite
     * directions - e.g. Event.orthologousEvent (OUTGOING) and Event.inferredFrom
     * (INCOMING) both map to "inferredTo", just from opposite ends of the identical edge
     * set, so Neo4j cannot tell "my own attribute" apart from "the other object's reverse
     * view of their own attribute pointing at me". If a brand-new instance A has
     * inferredFrom = B and B is later committed on its own (e.g. as a separate top-level
     * commit after being minted a dbId as a side effect of storing A), resetNode(B) would
     * delete ALL of B's outgoing "inferredTo" edges - including the one representing
     * A.inferredFrom - via B's orthologousEvent mapping, and nothing recreates it.
     *
     * Only the field NOT exposed to curators in curation_schema_attributes.json is
     * excluded here (confirmed via CuratorToolExporter's NOMANUALEDIT locking and by
     * checking the schema JSON directly) - the other, curator-editable field of each pair
     * (inferredFrom, normalPathway, normalReaction, referenceEntity) is deliberately left
     * in normal resetNode processing, since excluding it too would leave a stale duplicate
     * relationship behind whenever a curator actually changes that field's value (Cypher's
     * CREATE in store()'s relationship step always adds a new relationship; it does not
     * check for or replace an existing one - only resetNode's delete keeps that in sync).
     *
     * The key is (field name + relationship type), not just field name, because a field
     * name alone can collide across unrelated classes with a different meaning - e.g.
     * ReferenceEntity.physicalEntity (type "referenceEntity", excluded here) has nothing to
     * do with PhysicalEntityCellType.physicalEntity or CatalystActivity.physicalEntity
     * (both type "physicalEntity", NOT excluded - they're ordinary single-owner fields).
     */
    private static final Set<String> RESET_NODE_EXCLUDED_FIELD_TYPE_KEYS = Set.of(
            "orthologousEvent:inferredTo",
            "diseasePathways:normalPathway",
            "diseaseReactions:normalReaction",
            "physicalEntity:referenceEntity");

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
            Relationship rel = field2rel.get(field);
            if (RESET_NODE_EXCLUDED_FIELD_TYPE_KEYS.contains(field + ":" + rel.type()))
                continue;
            // We will try to delete a relationship as long as it is defined in the model
            // even though it may not exist in the database for this object
            // By doing this, we don't need to load the object first to find out which relationship exists
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
        if (obj.getDbId() != null && existsById(obj.getDbId())) {
            // Check if its class type is switched. If it is, update the node's labels first.
            // update()/store() both locate the existing node via a MATCH keyed on obj's
            // (new) class label - see resetNode() and storeNodeProperties(), both of which
            // do "MATCH (n:<getNodeLabel(obj)> {dbId: $dbId})". If the stored node still
            // carries only the OLD class's labels, that MATCH finds nothing and the whole
            // update silently no-ops: no exception, no persisted change at all. Adding the
            // new class's labels (and removing the old class's labels that no longer apply)
            // in place - rather than deleting and recreating the node - keeps relationships
            // FROM OTHER objects INTO this one (e.g. a Pathway's hasEvent pointing at it)
            // intact, since those relationships are attached to the node itself, not to its
            // labels.
            String storedSchemaClass = fetchSchemaClasses(List.of(obj.getDbId())).get(obj.getDbId());
            if (storedSchemaClass != null && !storedSchemaClass.equals(obj.getSchemaClass())) {
                switchNodeLabels(obj);
            }
            return update(obj);
        }
        else
            return store(obj);
    }

    /**
     * Adds obj's (new) class's labels to its existing node and removes whichever of the node's
     * current labels no longer apply, without deleting/recreating the node - see commit()'s
     * class-switch handling.
     *
     * A node's labels are its full class hierarchy, not just the leaf class - e.g. a Reaction
     * node is labelled DatabaseObject, Event, ReactionLikeEvent, Reaction, Trackable, Deletable,
     * one label per @Node-annotated superclass/interface in its hierarchy (confirmed live via
     * "MATCH (n:Reaction) RETURN labels(n)"). That's exactly what Spring Data Neo4j itself
     * assigns when creating a node of the new class, so it's recomputed here via reflection over
     * obj.getClass() rather than guessing at a single label.
     */
    private void switchNodeLabels(DatabaseObject obj) {
        Set<String> oldLabels = fetchNodeLabels(obj.getDbId());
        Set<String> newLabels = computeHierarchyLabels(obj.getClass());

        Set<String> labelsToRemove = new LinkedHashSet<>(oldLabels);
        labelsToRemove.removeAll(newLabels);
        Set<String> labelsToAdd = new LinkedHashSet<>(newLabels);
        labelsToAdd.removeAll(oldLabels);

        if (labelsToRemove.isEmpty() && labelsToAdd.isEmpty())
            return;

        StringBuilder cypher = new StringBuilder("MATCH (n:DatabaseObject {dbId: $dbId}) ");
        if (!labelsToRemove.isEmpty())
            cypher.append("REMOVE n:").append(String.join(":", labelsToRemove)).append(" ");
        if (!labelsToAdd.isEmpty())
            cypher.append("SET n:").append(String.join(":", labelsToAdd)).append(" ");
        logger.debug("Switch node labels Cypher: " + cypher);
        neo4jClient.query(cypher.toString()).bind(obj.getDbId()).to("dbId").run();
    }

    /**
     * Live labels currently on the node with this dbId (ground truth from Neo4j, rather than
     * assuming the previously stored schemaClass name maps to a loadable Java class).
     */
    private Set<String> fetchNodeLabels(Long dbId) {
        String cypher = "MATCH (n:DatabaseObject {dbId: $dbId}) RETURN labels(n) AS labels";
        Optional<Map<String, Object>> result = neo4jClient.query(cypher).bind(dbId).to("dbId").fetch().one();
        if (result.isEmpty())
            return Collections.emptySet();
        Object labelsValue = result.get().get("labels");
        if (labelsValue instanceof Collection) {
            Set<String> labels = new HashSet<>();
            for (Object label : (Collection<?>) labelsValue)
                labels.add(label.toString());
            return labels;
        }
        return Collections.emptySet();
    }

    /**
     * The full set of node labels Spring Data Neo4j would assign to an instance of cls: one
     * label per @Node-annotated class/interface in cls's hierarchy (superclasses and
     * implemented interfaces, walked transitively), using that type's explicit @Node label(s)
     * if declared, otherwise its simple name.
     */
    private Set<String> computeHierarchyLabels(Class<?> cls) {
        Set<String> labels = new LinkedHashSet<>();
        Set<Class<?>> visited = new HashSet<>();
        Deque<Class<?>> toVisit = new ArrayDeque<>();
        toVisit.add(cls);
        while (!toVisit.isEmpty()) {
            Class<?> current = toVisit.poll();
            if (current == null || !visited.add(current))
                continue;
            org.springframework.data.neo4j.core.schema.Node nodeAnnotation =
                    current.getAnnotation(org.springframework.data.neo4j.core.schema.Node.class);
            if (nodeAnnotation != null) {
                String[] explicitLabels = nodeAnnotation.value().length > 0 ?
                        nodeAnnotation.value() : nodeAnnotation.labels();
                if (explicitLabels.length > 0)
                    labels.addAll(Arrays.asList(explicitLabels));
                else
                    labels.add(current.getSimpleName());
            }
            // Stop at the root of the domain model - DatabaseObject's own superclass (Object)
            // and interfaces (Serializable, Comparable, DatabaseObjectLike) aren't part of the
            // domain's label hierarchy.
            if (current == DatabaseObject.class)
                continue;
            // ArrayDeque rejects null elements, and getSuperclass() is null for interfaces
            // (and would be null for Object, but DatabaseObject.class is never reached via
            // an interface with no superclass since it's a concrete class).
            Class<?> superclass = current.getSuperclass();
            if (superclass != null)
                toVisit.add(superclass);
            toVisit.addAll(Arrays.asList(current.getInterfaces()));
        }
        return labels;
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

    public Set<Taxon> grepSpecies(Long dbId, String followAttributes, String schemaClass) {
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

    /**
     * Find instances whose defining attributes match those of the passed instance.
     * Delegates the Cypher query to {@link CypherQueryUtilities#findMatchingInstancesByDefiningAttributes},
     * then converts each returned dbId into a lightweight {@link SimpleInstance} shell
     * (dbId, displayName, schemaClass only).
     *
     * @param schemaClass        the schema class name of the instance being matched
     * @param definingAttributes map of attribute name → {@link DefiningAttributeValue}
     *                           built from the instance's attributes and schema definition
     * @return list of matching SimpleInstance shells, empty if none found
     */
    public List<SimpleInstance> findMatchedInstances(String schemaClass,
                                                     Map<String, DefiningAttributeValue> definingAttributes) {
        List<Long> dbIds = queryUtilities.findMatchingInstancesByDefiningAttributes(
                schemaClass, definingAttributes, neo4jClient);
        if (dbIds == null || dbIds.isEmpty())
            return Collections.emptyList();
        // Fetch lightweight shells (dbId, displayName, schemaClass) for each matched id
        String query = "MATCH (n:DatabaseObject) " +
                       "WHERE n.dbId IN $dbIds " +
                       "RETURN n.dbId AS `inst.dbId`, n.displayName AS `inst.displayName`, " +
                       "n.schemaClass AS `inst.schemaClass`";
        Collection<Map<String, Object>> rows = neo4jClient.query(query)
                .bindAll(Map.of("dbIds", dbIds))
                .fetch().all();
        return rows.stream()
                   .map(row -> constructInstance(row, schemaClass))
                   .collect(Collectors.toList());
    }
}
