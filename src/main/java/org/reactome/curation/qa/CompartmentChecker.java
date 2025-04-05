package org.reactome.curation.qa;


import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.reactome.server.graph.domain.model.Complex;
import org.reactome.server.graph.domain.model.EntitySet;
import org.reactome.server.graph.domain.model.ReactionLikeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;

/**
 * The compartment check is quite complicated. The found issue is summarized in a single text. However, the detailed information
 * may need to find in the generic table. For the time, the table is used to report all compartment assignment for the curators 
 * to determine the cause. This may be enhanced in the future.
 * Different classes may have their own logic to determine the issue. Therefore, each class has its own check.
 *
 */
public class CompartmentChecker extends QAChecker {
    private static final Logger logger = LoggerFactory.getLogger(CompartmentChecker.class);

    private Neo4jClient neo4jClient;

    public CompartmentChecker(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @Override
    public String getCheckName() {
        return "Compartment Check";
    }
    
    @Override
    public Collection<Class<?>> getTargetClasses() {
        Class<?>[] classes = {
                ReactionLikeEvent.class,
                Complex.class,
                EntitySet.class
        };
        return Stream.of(classes).collect(Collectors.toSet());
    }

    @Override
    public QACheckResult performQACheck(SimpleInstance instance) {
        if (!shouldCheck(instance))
            return null;
        
        String relationships = getContainerRelationships(instance);
        // Just in case
        if (relationships == null) {
            logger.error("Cannot find any relationship: " + instance);
            throw new IllegalArgumentException("Cannot find any relationship: " + instance);
        }

        return checkCompartment(instance.getDbId(), relationships, instance.getSchemaClassName());
    }

    private QACheckResult checkCompartment(Long dbId, String followAttributes, String schemaClass) {
        String query = String.format("MATCH (container:%s {dbId: %d})\n"
                + "OPTIONAL MATCH (container)-[:compartment]->(compartment:Compartment)\n"
                + "WITH container, compartment AS containerLocation\n"
                + "OPTIONAL MATCH (container)-[:includedLocation]->(ilc:Compartment)\n"
                + "WITH container, containerLocation, ilc AS includedLocations\n"
                + "OPTIONAL MATCH (container)-[r:%s*]->(contained:PhysicalEntity)\n"
                + "WITH container, containerLocation, includedLocations, COLLECT(contained) AS containeds, r\n"
                + "UNWIND containeds AS pe\n" 
                + "UNWIND r AS role\n"
                + "OPTIONAL MATCH (pe)-[:compartment]->(containedCompartments:Compartment)\n"
                + "WITH container, containerLocation, includedLocations, COLLECT(DISTINCT containedCompartments) AS cCompartment, role, pe\n"
                + "UNWIND cCompartment as containedLocation\n"
                + "RETURN containerLocation.dbId, containerLocation.displayName, containedLocation.dbId,"
                + " containedLocation.displayName, TYPE(role) AS relationshipType, pe.displayName, pe.dbId",
                schemaClass, dbId, followAttributes);

        Collection<Map<String, Object>> all = neo4jClient.query(query).fetch().all();
        if (all.isEmpty())
            return getEmptyResult();

        Map<Long, SimpleInstance> containerId2Comp = new HashMap<>();
        Map<Long, SimpleInstance> includedId2Comp = new HashMap<>();
        Map<String, SimpleInstance> containedIdKey2Comp = new HashMap<>();

        for (Map<String, Object> map : all) {
            // Handle container compartments
            String containerCompDbIdText = map.get("containerLocation.dbId").toString();
            Long containerCompDbId = Long.parseLong(containerCompDbIdText);
            if (!containerId2Comp.containsKey(containerCompDbId)) {
                // Create a simple instance as a data structure for the container, add to list
                SimpleInstance containerCompt = new SimpleInstance();
                containerCompt.setDbId(containerCompDbId);
                containerCompt.setDisplayName(map.get("containerLocation.displayName").toString());
                containerId2Comp.put(containerCompt.getDbId(), containerCompt);
            }
            if (map.get("includedLocations.dbId") != null) {
                String includedLocationsDbId = map.get("includedLocations.dbId").toString();
                Long dbId1 = Long.parseLong(includedLocationsDbId);
                if (!containerId2Comp.containsKey(dbId1)) {
                    // Create an entity as a data structure for the container, add to list
                    SimpleInstance containerCompt = new SimpleInstance();
                    containerCompt.setDbId(dbId1);
                    containerCompt.setDisplayName(map.get("includedLocation.displayName").toString());
                    containerId2Comp.put(containerCompt.getDbId(), containerCompt);
                }
            }
            // Handle compartments in the referred child structure
            String participantDbId = map.get("pe.dbId").toString();
            String role = map.get("relationshipType").toString();
            String key = participantDbId + ":" + role;
            SimpleInstance pe = containedIdKey2Comp.get(key);
            if (pe == null) {
                String participantDisplayName = map.get("pe.displayName").toString();
                pe = new SimpleInstance();
                pe.setDisplayName(participantDisplayName);
                pe.setDbId(Long.parseLong(participantDbId));
                pe.setAttribute("role", role);
                containedIdKey2Comp.put(key, pe);
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
//        Collection<String> nonAdjacent = new ArrayList<>();
//        for (String containerDbId : complexCompartments) {
//            String surroundByQuery = String.format("MATCH (compartment:Compartment {dbId: %d})\n"
//                    + "OPTIONAL MATCH (compartment)-[:surroundedBy]->(s:Compartment)\n"
//                    + "WITH compartment, s AS allowedCompartments\n"
//                    + "return allowedCompartments.dbId, compartment.dbId", Long.parseLong(containerDbId));
//
//            Collection<Map<String, Object>> allSurroundedBy = neo4jClient.query(surroundByQuery).fetch().all();
//            List<String> neighbors = new ArrayList<>();
//            for (Map<String, Object> map : allSurroundedBy) {
//                if (!((map.get("complexLocation.dbId")) == null)) {
//                    String surroundByContainerDbId = map.get("complexLocation.dbId").toString();
//                    neighbors.add(surroundByContainerDbId);
//                }
//            }
//
//            for (String complexId : complexCompartments) {
//                if (!neighbors.contains(complexId)) {
//                    nonAdjacent.add(complexId);
//                }
//            }
//        }

        // Check for mismatches
        String[] colNames = { "Role", "Participant", "Compartment", "Issue" };
        List<String[]> rows = new ArrayList<>();
        int i = 0;
        for (String key : containedIdKey2Comp.keySet()) {
            if (containerId2Comp.containsKey((containedIdKey2Comp.get(key).getDbId()))) {
                continue;
            }
            // Return information about the physical entities that have an assigned species
            // not listed in the parent
            else {
                String role = key.split(":")[1];
                // TODO: cast this object into strings
                String componentCompartment = containedIdKey2Comp.get(key).getAttribute("compartment").toString();
                String participant = containedIdKey2Comp.get(key).getDisplayName();
                String[] row = { role, participant, componentCompartment };
                rows.add(row);
            }
        }
        return createResult(colNames, rows);
    }

}