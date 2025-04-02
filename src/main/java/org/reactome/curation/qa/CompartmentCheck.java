package org.reactome.curation.qa;

/*
0 * Created on Mar 31, 2008
 *
 */

import java.util.*;
import java.util.stream.Collectors;

import javassist.Loader;
import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.reactome.curation.qa.model.QACheckAttributes;
import org.reactome.server.graph.domain.model.Compartment;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.EntitySet;
import org.reactome.server.graph.domain.model.SimpleEntity;
import org.reactome.server.graph.domain.relationship.HasCompartment;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import static java.util.Arrays.stream;

/**
 * This Complex Compartment QA check detects Complex compartment
 * inconsistency according to the following criteria:
 * <ul>
 * <li>There should be exactly one container compartment value in Complex,
 *     even though the attribute is defined as multi-valued.</li>
 * <li>If there is a non-empty Complex includedLocation value set,
 *     then the the included locations should equal the set of
 *     contained subunits compartments without the complex compartment.
 * </li>
 * <li>Otherwise, the Complex container compartment should be the
 *     same as each contained subunit compartment.</li>
 * <li>The complex compartment and includedLocations should be
 *     adjacent in a celluar region, as determined by the
 *     surroundedBy compartment slot.</li>
 * </ul>
 * <p>
 * The following constraints are not validated until curators are ready to
 * address them:
 * <ul>
 * <li>There should not be more than two compartment values in all subunits.</li>
 * </ul>
 *
 * @author gwu
 */
@Repository
public class CompartmentCheck implements QAChecker {

    private final static String MISSING_COMPLEX_COMPARTMENT = "Complex compartment not a subunit compartment";
    private final static String TOO_MANY_COMPLEX_COMPARTMENTS = "More than one complex compartment";
    private final static String TOO_MANY_SUBUNIT_COMPARTMENTS = "More than two subunit compartments";
    private static final String COMPARTMENTS_NOT_ADJACENT = "Noncontiguous compartments";

    private Neo4jClient neo4jClient;

    public CompartmentCheck(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }
    
    @Override
    public QACheckResult performQACheck(SimpleInstance instance) {
        return checkInstanceType(instance);
    }

    public QACheckResult checkInstanceType(SimpleInstance instance) {
        ArrayList<String> followAttributes = new ArrayList<>();
        String clsName = instance.getSchemaClassName();
        switch (clsName) {
            case ReactomeJavaConstants.Complex: {
                followAttributes.add(ReactomeJavaConstants.hasComponent);
                break;
            }
            case ReactomeJavaConstants.EntitySet: {
                followAttributes.add(ReactomeJavaConstants.hasMember);
                followAttributes.add(ReactomeJavaConstants.hasCandidate);
                break;
            }
            case ReactomeJavaConstants.Pathway: {
                followAttributes.add(ReactomeJavaConstants.hasEvent);
                break;
            }
            case ReactomeJavaConstants.Reaction: {
                followAttributes.add(ReactomeJavaConstants.input);
                followAttributes.add(ReactomeJavaConstants.output);
                followAttributes.add(ReactomeJavaConstants.catalystActivity);
                followAttributes.add(ReactomeJavaConstants.physicalEntity);
                followAttributes.add(ReactomeJavaConstants.regulatedBy);
                followAttributes.add(ReactomeJavaConstants.regulator);
                followAttributes.add(ReactomeJavaConstants.regulatedEntity);
                break;
            }
        }
        StringBuilder realtionships = new StringBuilder();
        for (int i = 0; i < followAttributes.size(); i++) {
            realtionships.append(followAttributes.get(i));
            if (i != (followAttributes.size() - 1)) {
                realtionships.append("|");
            }
        }

        return compartmentCheck(instance.getDbId(), realtionships.toString(), clsName);
    }


    public QACheckResult compartmentCheck(Long dbId, String followAttributes, String schemaClass) {
        String query = String.format(
                "MATCH (complex:%s {dbId: %d})\n" +
                        "OPTIONAL MATCH (complex)-[:compartment]->(compartment:Compartment)\n" +
                        "WITH complex, compartment AS complexLocation\n" +
                        "OPTIONAL MATCH (complex)-[:includedLocation]->(ilc:Compartment)\n" +
                        "WITH complex, complexLocation, ilc AS includedLocations\n" +
                        "OPTIONAL MATCH (complex)-[r:%s*]->(pe:PhysicalEntity)\n" +
                        "WITH complex, complexLocation,includedLocations, COLLECT(pe) AS components, r\n" +
                        "UNWIND components AS pe\n" +
                        "UNWIND r AS role\n" +
                        "OPTIONAL MATCH (pe)-[:compartment]->(componentCompartments:Compartment)\n" +
                        "WITH complex, complexLocation, includedLocations, COLLECT(DISTINCT componentCompartments) AS cCompartment, role, pe\n" +
                        "UNWIND cCompartment as componentLocations\n" +
                        "RETURN complexLocation.dbId, complexLocation.displayName, componentLocations.dbId," +
                        " componentLocations.displayName, TYPE(role) AS relationshipType, pe.displayName, pe.dbId",
                schemaClass, dbId, followAttributes);

        Collection<Map<String, Object>> all = neo4jClient.query(query).fetch().all();

        ArrayList<QACheckAttributes> componentDbIds = new ArrayList<>();

        if (all.isEmpty()) {
            QACheckAttributes testAttributes = new QACheckAttributes(MISSING_COMPLEX_COMPARTMENT, dbId.toString());
            componentDbIds.add(testAttributes);
        } else {
            Map<Long, SimpleInstance>  containerCompartments = new HashMap<>();
            Map<String, SimpleInstance>  componentCompartments = new HashMap<>();

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
                if(comp == null){
                    Object[] compartments = {comp, componentCompt};
                    pe.setAttribute("compartment", compartments);
                }
                else {
                    pe.setAttribute("compartment", componentCompt);
                }
            }

            // Check adjacency
            Collection<String> nonAdjacent = new ArrayList<>();
            for (String containerDbId : complexCompartments) {
                String surroundByQuery = String.format(
                        "MATCH (compartment:Compartment {dbId: %d})\n" +
                                "OPTIONAL MATCH (compartment)-[:surroundedBy]->(s:Compartment)\n" +
                                "WITH compartment, s AS allowedCompartments\n" +
                                "return allowedCompartments.dbId, compartment.dbId",
                        Long.parseLong(containerDbId));

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

            // Check the number of Compartments
            if (containerCompartments.size() > 1) {
                //TODO: maybe print out all of the compartments that have been added
                QACheckAttributes testAttributes = new QACheckAttributes(TOO_MANY_COMPLEX_COMPARTMENTS, dbId.toString());
                componentDbIds.add(testAttributes);
            }

            // Check for mismatches
            String[] colNames = {"Role", "Participant", "Compartment", "Issue"};
            String[][] rows = new String[all.size()][colNames.length];
            int i=0;
            for (String key : componentCompartments.keySet()) {
                if (containerCompartments.containsKey((componentCompartments.get(key).getDbId()))){
                    continue;
                }
                // Return information about the physical entities that have an assigned species not listed in the parent
                else {
                    String role = key.split(":")[1];
                    //TODO: cast this object into strings
                    String componentCompartment = componentCompartments.get(key).getAttribute("compartment").toString();
                    String participant = componentCompartments.get(key).getDisplayName();
                    String[] row = {role, participant, componentCompartment};

                    QACheckAttributes testAttributes = new QACheckAttributes("Mismatched Compartment", dbId.toString());
                    componentDbIds.add(testAttributes);
                    rows[i] = row;
                    i++;
                }
            }
            return new QACheckResult("Compartment Check",
                    (componentDbIds.isEmpty()), colNames, rows);
        }
        return null;
    }

}