package org.reactome.curation.qa;

/*
0 * Created on Mar 31, 2008
 *
 */
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;

/**
 * This Complex Compartment QA check detects Complex compartment inconsistency
 * according to the following criteria:
 * <ul>
 * <li>There should be exactly one container compartment value in Complex, even
 * though the attribute is defined as multi-valued.</li>
 * <li>If there is a non-empty Complex includedLocation value set, then the the
 * included locations should equal the set of contained subunits compartments
 * without the complex compartment.</li>
 * <li>Otherwise, the Complex container compartment should be the same as each
 * contained subunit compartment.</li>
 * <li>The complex compartment and includedLocations should be adjacent in a
 * celluar region, as determined by the surroundedBy compartment slot.</li>
 * </ul>
 * <p>
 * The following constraints are not validated until curators are ready to
 * address them:
 * <ul>
 * <li>There should not be more than two compartment values in all
 * subunits.</li>
 * </ul>
 *
 */
public class CompartmentChecker extends QAChecker {
    private static final Logger logger = LoggerFactory.getLogger(CompartmentChecker.class);

    private final static String MISSING_COMPLEX_COMPARTMENT = "Complex compartment not a subunit compartment";
    private final static String TOO_MANY_COMPLEX_COMPARTMENTS = "More than one complex compartment";
    private final static String TOO_MANY_SUBUNIT_COMPARTMENTS = "More than two subunit compartments";
    private static final String COMPARTMENTS_NOT_ADJACENT = "Noncontiguous compartments";

    private Neo4jClient neo4jClient;

    public CompartmentChecker(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @Override
    public String getCheckName() {
        return "Compartment Check";
    }
    
    @Override
    public Collection<String> getTargetClasses() {
        String[] classes = {
                ReactomeJavaConstants.ReactionlikeEvent,
                ReactomeJavaConstants.Complex,
                ReactomeJavaConstants.Pathway,
                ReactomeJavaConstants.EntitySet
        };
        return Stream.of(classes).collect(Collectors.toSet());
    }

    @Override
    public QACheckResult performQACheck(SimpleInstance instance) {
        if (!shouldCheck(instance))
            return null;
        
        String relationships = getRelationships(instance);
        // Just in case
        if (relationships == null) {
            logger.error("Cannot find any relationship: " + instance);
            throw new IllegalArgumentException("Cannot find any relationship: " + instance);
        }

        return checkCompartment(instance.getDbId(), relationships, instance.getSchemaClassName());
    }

    public QACheckResult checkCompartment(Long dbId, String followAttributes, String schemaClass) {
        String query = String.format("MATCH (complex:%s {dbId: %d})\n"
                + "OPTIONAL MATCH (complex)-[:compartment]->(compartment:Compartment)\n"
                + "WITH complex, compartment AS complexLocation\n"
                + "OPTIONAL MATCH (complex)-[:includedLocation]->(ilc:Compartment)\n"
                + "WITH complex, complexLocation, ilc AS includedLocations\n"
                + "OPTIONAL MATCH (complex)-[r:%s*]->(pe:PhysicalEntity)\n"
                + "WITH complex, complexLocation,includedLocations, COLLECT(pe) AS components, r\n"
                + "UNWIND components AS pe\n" + "UNWIND r AS role\n"
                + "OPTIONAL MATCH (pe)-[:compartment]->(componentCompartments:Compartment)\n"
                + "WITH complex, complexLocation, includedLocations, COLLECT(DISTINCT componentCompartments) AS cCompartment, role, pe\n"
                + "UNWIND cCompartment as componentLocations\n"
                + "RETURN complexLocation.dbId, complexLocation.displayName, componentLocations.dbId,"
                + " componentLocations.displayName, TYPE(role) AS relationshipType, pe.displayName, pe.dbId",
                schemaClass, dbId, followAttributes);

        Collection<Map<String, Object>> all = neo4jClient.query(query).fetch().all();
        if (all.isEmpty())
            return getEmptyResult();

        Map<Long, SimpleInstance> containerCompartments = new HashMap<>();
        Map<String, SimpleInstance> componentCompartments = new HashMap<>();

        List<String> complexCompartments = new ArrayList<>();
        for (Map<String, Object> map : all) {
            // Handle container compartments
            String containerCompDbIdText = map.get("complexLocation.dbId").toString();
            Long containerDbId = Long.parseLong(containerCompDbIdText);
            if (!containerCompartments.containsKey(containerDbId)) {
                // Create a simple instance as a data structure for the container, add to list
                SimpleInstance containerCompt = new SimpleInstance();
                containerCompt.setDbId(containerDbId);
                containerCompt.setDisplayName(map.get("complexLocation.displayName").toString());
                containerCompartments.put(containerCompt.getDbId(), containerCompt);
            }
            if (!((map.get("includedLocations.dbId")) == null)) {
                String includedLocationsDbId = map.get("includedLocations.dbId").toString();
                Long dbId1 = Long.parseLong(includedLocationsDbId);
                if (!containerCompartments.containsKey(dbId1)) {
                    // Create an entity as a data structure for the container, add to list
                    SimpleInstance containerCompt = new SimpleInstance();
                    containerCompt.setDbId(dbId1);
                    containerCompt.setDisplayName(map.get("includedLocation.displayName").toString());
                    containerCompartments.put(containerCompt.getDbId(), containerCompt);
                }
            }
            // Handle compartments in the referred child structure
            String participantDbId = map.get("pe.dbId").toString();
            String role = map.get("relationshipType").toString();
            String key = participantDbId + ":" + role;
            SimpleInstance pe = componentCompartments.get(key);
            if (pe == null) {
                String participantDisplayName = map.get("pe.displayName").toString();
                pe = new SimpleInstance();
                pe.setDisplayName(participantDisplayName);
                pe.setDbId(Long.parseLong(participantDbId));
                pe.setAttribute("role", role);
                componentCompartments.put(key, pe);
            }
            String containerDisplayName = map.get("complexLocation.displayName").toString();
            String componentDbId = map.get("componentLocations.dbId").toString();
            // Create a simple instance to model the component and add to list
            SimpleInstance componentCompt = new SimpleInstance();
            componentCompt.setDbId(Long.parseLong(componentDbId));
            componentCompt.setDisplayName(containerDisplayName);
            Object comp = pe.getAttribute("compartment");
            if (comp == null) {
                Object[] compartments = { comp, componentCompt };
                pe.setAttribute("compartment", compartments);
            } else {
                pe.setAttribute("compartment", componentCompt);
            }
        }

        // Check adjacency
        Collection<String> nonAdjacent = new ArrayList<>();
        for (String containerDbId : complexCompartments) {
            String surroundByQuery = String.format("MATCH (compartment:Compartment {dbId: %d})\n"
                    + "OPTIONAL MATCH (compartment)-[:surroundedBy]->(s:Compartment)\n"
                    + "WITH compartment, s AS allowedCompartments\n"
                    + "return allowedCompartments.dbId, compartment.dbId", Long.parseLong(containerDbId));

            Collection<Map<String, Object>> allSurroundedBy = neo4jClient.query(surroundByQuery).fetch().all();
            List<String> neighbors = new ArrayList<>();
            for (Map<String, Object> map : allSurroundedBy) {
                if (!((map.get("complexLocation.dbId")) == null)) {
                    String surroundByContainerDbId = map.get("complexLocation.dbId").toString();
                    neighbors.add(surroundByContainerDbId);
                }
            }

            for (String complexId : complexCompartments) {
                if (!neighbors.contains(complexId)) {
                    nonAdjacent.add(complexId);
                }
            }
        }

        // Check for mismatches
        String[] colNames = { "Role", "Participant", "Compartment", "Issue" };
        List<String[]> rows = new ArrayList<>();
        int i = 0;
        for (String key : componentCompartments.keySet()) {
            if (containerCompartments.containsKey((componentCompartments.get(key).getDbId()))) {
                continue;
            }
            // Return information about the physical entities that have an assigned species
            // not listed in the parent
            else {
                String role = key.split(":")[1];
                // TODO: cast this object into strings
                String componentCompartment = componentCompartments.get(key).getAttribute("compartment").toString();
                String participant = componentCompartments.get(key).getDisplayName();
                String[] row = { role, participant, componentCompartment };
                rows.add(row);
            }
        }
        return new QACheckResult("Compartment Check", colNames, rows);
    }

}