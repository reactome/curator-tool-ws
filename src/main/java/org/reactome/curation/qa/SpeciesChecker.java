package org.reactome.curation.qa;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.reactome.curation.qa.model.QACheckAttributes;
import org.reactome.server.graph.domain.model.Compartment;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.SimpleEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.stream.Collectors;

@Repository
public class SpeciesChecker implements QAChecker {

    private static final Logger logger = LoggerFactory.getLogger(org.reactome.curation.repository.CurationRepository.class);

    private Neo4jClient neo4jClient;

    public SpeciesChecker(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }
    
    @Override
    public QACheckResult performQACheck(SimpleInstance instance) {
        return checkInstanceType(instance);
    }

    public QACheckResult checkInstanceType(SimpleInstance instance) {
        ArrayList<String> followAttributes = new ArrayList<>();
        String schemaClassName = instance.getSchemaClassName();
        switch (schemaClassName){
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
                break;
            }
        }
        StringBuilder realtionships = new StringBuilder();
        for(int i=0; i<followAttributes.size(); i++) {
            realtionships.append(followAttributes.get(i));
            if(i != (followAttributes.size() -1)){
                realtionships.append("|");
            }
        }

        return speciesCheck(instance.getDbId(), realtionships.toString(), schemaClassName);
    }

    // The breaking characters are required for the query to run correctly
    public QACheckResult speciesCheck(Long dbId, String followAttributes, String schemaClass) {
        String query = String.format(
                "MATCH (complex:%s {dbId: %d})\n" +
                        "OPTIONAL MATCH (complex)-[:species]->(s:Species)\n" +
                        "WITH complex, s AS complexSpecies\n" +
                        "OPTIONAL MATCH (complex)-[r:%s*]->(pe:PhysicalEntity)\n" +
                        "WITH complex, complexSpecies, r, COLLECT(pe) AS components\n" +
                        "UNWIND components AS pe\n" +
                        "UNWIND r AS role\n" +
                        "OPTIONAL MATCH (pe)-[:species|relatedSpecies]->(species:Species)\n" +
                        "WITH complex, complexSpecies, COLLECT(DISTINCT species) AS cSpecies, role, pe\n" +
                        "UNWIND cSpecies as componentSpecies\n" +
                        "RETURN complexSpecies.dbId, complexSpecies.displayName, componentSpecies.dbId, componentSpecies.displayName," +
                        " TYPE(role) AS relationshipType, pe.displayName, pe.dbId",
                schemaClass, dbId, followAttributes);
        
        System.out.println("Query:\n" + query);

        Collection<Map<String, Object>> all = neo4jClient.query(query).fetch().all();

        // The collection of species dbIds that are incorrect and need to be returned
        // TODO: may also want to return the physical entity from the 'hasComponent' slot that has the incorrect species
        ArrayList<QACheckAttributes> speciesDbIds = new ArrayList<>();

        // TODO: add a check in case the complex does not have a species assigned, null exception
        if(all.isEmpty()){
            QACheckAttributes testAttributes = new QACheckAttributes("No Species assigned", dbId.toString());
            speciesDbIds.add(testAttributes);
        }

        else {
            // Create a collection of the complex's assigned species (can be multiple)
            Map<Long, SimpleInstance> containerCompartments = new HashMap<>();
            Map<String, SimpleInstance> componentCompartments = new HashMap<>();

            // Collecting the complex's and component's species name and dbId
            for (Map<String, Object> map : all) {
                String complexSpeciesDbId = map.get("complexSpecies.dbId").toString();
                Long containerDbId = Long.parseLong(complexSpeciesDbId);
                if (!containerCompartments.containsKey(containerDbId)) {
                    // Create a simple instance as a data structure for the container, add to list
                    SimpleInstance containerCompt = new SimpleInstance();
                    containerCompt.setDbId(containerDbId);
                    containerCompt.setDisplayName(map.get("complexSpecies.displayName").toString());
                    containerCompartments.put(containerCompt.getDbId(), containerCompt);
                }
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

                String componentSpeciesDisplayName = map.get("componentSpecies.displayName").toString();
                String componentSpeciesDbId = map.get("componentSpecies.dbId").toString();

                // Create a simple instance to model the component and add to map
                SimpleInstance componentCompt = new SimpleInstance();
                componentCompt.setDbId(Long.parseLong(componentSpeciesDbId));
                componentCompt.setDisplayName(componentSpeciesDisplayName);
                Object comp = pe.getAttribute("species");
                if(comp == null){
                    Object[] compartments = {comp, componentCompt};
                    pe.setAttribute("species", compartments);
                }
                else {
                    pe.setAttribute("species", componentCompt);
                }
            }

            // Building the table for the front-end
            String[] colNames = {"Role", "Participant", "Species"};
            String[][] rows = new String[all.size()][colNames.length];
            int i=0;
            // TODO: The container species should also be checked for extra or less species that the components do not have
            for(String key : componentCompartments.keySet()) {
                // If the complex species list contains the species of the component continue
                // TODO: need to determine if the species dbId is a sufficient check ie same species under different names
                if (containerCompartments.containsKey((componentCompartments.get(key).getDbId()))){
                    continue;
                }
                // Return information about the physical entities that have an assigned species not listed in the parent
                else {
                    //QACheckAttributes testAttributes = new QACheckAttributes("Mismatched Species", dbId.toString());
                    String role = key.split(":")[1];
                    //TODO: cast this object into strings
                    String componentCompartment = componentCompartments.get(key).getAttribute("species").toString();
                    String participant = componentCompartments.get(key).getDisplayName();
                    String[] row = {role, participant, componentCompartment};

                    QACheckAttributes testAttributes = new QACheckAttributes("Mismatched Compartment", dbId.toString());
                    speciesDbIds.add(testAttributes);

                    rows[i] = row;
                    i++;
                }
            }

            return new QACheckResult("Species Check",
                    (speciesDbIds.isEmpty()), colNames, rows);
        }
        return null;
    }
}