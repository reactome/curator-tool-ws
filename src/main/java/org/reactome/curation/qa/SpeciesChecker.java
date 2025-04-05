package org.reactome.curation.qa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

@SuppressWarnings("unchecked")
public class SpeciesChecker extends QAChecker {

    private static final Logger logger = LoggerFactory
            .getLogger(org.reactome.curation.repository.CurationRepository.class);

    private Neo4jClient neo4jClient;

    public SpeciesChecker(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @Override
    public String getCheckName() {
        return "Species Check";
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
        
        return checkSpecies(instance.getDbId(), relationships, instance.getSchemaClassName());
    }

    // The breaking characters are required for the query to run correctly
    public QACheckResult checkSpecies(Long dbId, String followAttributes, String schemaClass) {
        String query = String.format("MATCH (container:%s {dbId: %d})\n"
                + "OPTIONAL MATCH (container)-[:species|relatedSpecies]->(s:Species)\n" + "WITH container, s AS containerSpecies\n"
                + "OPTIONAL MATCH (container)-[r:%s*]->(pe:PhysicalEntity)\n"
                + "WITH container, containerSpecies, r, COLLECT(pe) AS containeds\n" 
                + "UNWIND containeds AS pe\n"
                + "UNWIND r AS role\n" 
                + "OPTIONAL MATCH (pe)-[:species|relatedSpecies]->(species:Species)\n"
                + "WITH container, containerSpecies, COLLECT(DISTINCT species) AS cSpecies, role, pe\n"
                + "UNWIND cSpecies as containedSpecies\n"
                + "RETURN containerSpecies.dbId, containerSpecies.displayName, containedSpecies.dbId, containedSpecies.displayName,"
                + " TYPE(role) AS relationshipType, pe.displayName, pe.dbId", 
                schemaClass, dbId, followAttributes);

        Collection<Map<String, Object>> all = neo4jClient.query(query).fetch().all();

        if (all.isEmpty())
            return getEmptyResult();

        // Create a collection of the complex's assigned species (can be multiple)
        Map<Long, SimpleInstance> containerId2Species = new HashMap<>();
        Map<String, SimpleInstance> idkey2contained = new HashMap<>();
        // This is really for the quick check of container species
        Set<Long> containedSpeciesIds = new HashSet<>();

        // Collecting the complex's and component's species name and dbId
        for (Map<String, Object> map : all) {
            Long containerSpeciesDbId = Long.parseLong(map.get("containerSpecies.dbId").toString());
            if (!containerId2Species.containsKey(containerSpeciesDbId)) {
                // Create a simple instance as a data structure for the container species
                SimpleInstance containerSpecies = new SimpleInstance();
                containerSpecies.setDbId(containerSpeciesDbId);
                containerSpecies.setDisplayName(map.get("containerSpecies.displayName").toString());
                containerId2Species.put(containerSpecies.getDbId(), containerSpecies);
            }
            
            String containedDbId = map.get("pe.dbId").toString();
            String role = map.get("relationshipType").toString();
            String key = containedDbId + ":" + role;
            SimpleInstance contained = idkey2contained.get(key);
            if (contained == null) {
                String containedDisplayName = map.get("pe.displayName").toString();
                contained = new SimpleInstance();
                contained.setDisplayName(containedDisplayName);
                contained.setDbId(Long.parseLong(containedDbId));
                contained.setAttribute("role", role);
                idkey2contained.put(key, contained);
            }

            String containedSpeciesDisplayName = map.get("containedSpecies.displayName").toString();
            String containedSpeciesDbId = map.get("containedSpecies.dbId").toString();
                        // Create a simple instance to model the component and add to map
            SimpleInstance containedSpecies = new SimpleInstance();
            containedSpecies.setDbId(Long.parseLong(containedSpeciesDbId));
            containedSpecies.setDisplayName(containedSpeciesDisplayName);
            containedSpeciesIds.add(containedSpecies.getDbId());
            List<SimpleInstance> speciesList = (List<SimpleInstance>) contained.getAttribute(ReactomeJavaConstants.species);
            if (speciesList == null) {
                speciesList = new ArrayList<>();
                contained.setAttribute(ReactomeJavaConstants.species, speciesList);
            }
            boolean isFound = false;
            for (SimpleInstance inst : speciesList) {
                if (inst.getDbId().equals(containedSpecies.getDbId())) {
                    isFound = true;
                    break;
                }
            }
            if (!isFound)
                speciesList.add(containedSpecies);
        }

        // Building the table for the front-end
        String[] colNames = { "ReferredBy", "Reference", "Species", "Issue" };
        List<String[]> rows = new ArrayList<>();
        // Make sure contained's species has been listed in the contained
        for (Long speciesId : containerId2Species.keySet()) {
            if (!containedSpeciesIds.contains(speciesId)) {
                String[] row = {
                        "N/A",
                        "N/A",
                        containerId2Species.get(speciesId) + "",
                        "Extra species in checked instance"
                     };
                rows.add(row);
            }
        }
        // Make sure contained's species has been listed in the container's species
        for (String key : idkey2contained.keySet()) {
            SimpleInstance contained = idkey2contained.get(key);
            List<SimpleInstance> containedSpeciesList = (List<SimpleInstance>) contained.getAttribute(ReactomeJavaConstants.species);
            if (containedSpeciesList == null || containedSpeciesList.size() == 0)
                continue;
            for (SimpleInstance containedSpecies : containedSpeciesList) {
                if (containerId2Species.containsKey(containedSpecies.getDbId()))
                    continue;
                // Report this if this species is not in the container's list
                String[] row = {
                        contained.getAttribute("role").toString(),
                        contained + "",
                        containedSpecies + "",
                        "Reference species not listed"
                };
                rows.add(row);
            }
        }
        return new QACheckResult(getCheckName(), colNames, rows);
    }
}