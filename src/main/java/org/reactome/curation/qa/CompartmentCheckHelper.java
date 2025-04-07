package org.reactome.curation.qa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.springframework.data.neo4j.core.Neo4jClient;

public abstract class CompartmentCheckHelper {
    private static final String COMPARTMENTS_MISMATCH = "Compartment mismatch";
    private Neo4jClient neo4jClient;
    
    public void setNeo4jClient(Neo4jClient client) {
        this.neo4jClient = client;
    }
    
    public Neo4jClient getNeo4jClient() {
        return this.neo4jClient;
    }

    public abstract QACheckResult checkCompartment(Map<Long, SimpleInstance> containerId2Comp,
                                                   Map<Long, SimpleInstance> includedId2Comp,
                                                   Map<String, SimpleInstance> idRole2Contained,
                                                   Map<Long, SimpleInstance> containedId2Comp);
    
    protected List<Long[]> getNonAdjacency(Set<Long> idset) {
        Map<Long, Set<Long>> compId2AdjacentIds = fetchSurroundedBy(idset);
        List<Long> idlist = new ArrayList<>(idset);
        // Perform a pairwise analysis
        List<Long[]> nonAdjacent = new ArrayList<>();
        for (int i = 0; i < idlist.size() - 1; i++) {
            Long id1 = idlist.get(i);
            Set<Long> id1neighbor = compId2AdjacentIds.get(id1);
            if (id1neighbor == null)
                id1neighbor = Collections.EMPTY_SET; // For easy coding
            // We expect to see surroundedBy is a reciprocal relationship: A->B and B->A
            // should be defined both.
            for (int j = i + 1; j < idlist.size(); j++) {
                Long id2 = idlist.get(j);
                if (id1neighbor.contains(id2))
                    continue;
                nonAdjacent.add(new Long[]{id1, id2});
            }
        }
        return nonAdjacent;
    }
    
    private Map<Long, Set<Long>> fetchSurroundedBy(Set<Long> idset) {
        String query = String.format("MATCH (compartment:Compartment)\n"
                + "WHERE compartment.dbId IN %s\n"
                + "OPTIONAL MATCH (compartment)-[:surroundedBy]->(s:Compartment)\n"
                + "WITH compartment, s AS surroundCompartment\n"
                + "RETURN compartment.dbId AS compartmentId, surroundCompartment.dbId AS surroundedById", 
                idset);
        Map<Long, Set<Long>> id2neighbors = new HashMap<>();
        Collection<Map<String, Object>> results = neo4jClient.query(query).fetch().all();
        for (Map<String, Object> map : results) {
            Long compartmentId = (Long) map.get("compartmentId");
            Long surroundedById = (Long) map.get("surroundedById");
            // Since surroundedBy is not reciprocal, we need to register both dbIds.
            Set<Long> set = id2neighbors.get(compartmentId);
            if (set == null) {
                set = new HashSet<>();
                id2neighbors.put(compartmentId, set);
            }
            set.add(surroundedById);
            if (idset.contains(surroundedById)) {
                set = id2neighbors.get(surroundedById);
                if (set == null) {
                    set = new HashSet<>();
                    id2neighbors.put(surroundedById, set);
                }
                set.add(compartmentId);
            }
        }
        return id2neighbors;
    }

    protected void fillResultForCompartmentList(Map<Long, SimpleInstance> id2inst, QACheckResult result) {
        String[] colNames = { "compartment" };
        List<String[]> rows = new ArrayList<>();
        for (SimpleInstance comp : id2inst.values()) {
            rows.add(new String[] { comp + "" });
        }
        result.setColumns(colNames);
        result.setRows(rows);
    }

    protected void checkMismatch(Map<Long, SimpleInstance> containerId2Comp,
                                 Map<String, SimpleInstance> idRole2Contained,
                                 Map<Long, SimpleInstance> containedId2Comp,
                                 QACheckResult result) {
        String[] colNames = { "ReferredBy", "Reference", "Compartment", "Issue" };
        List<String[]> rows = new ArrayList<>();
        // Check if contained compartment is in contained
        for (Long containerCompId : containerId2Comp.keySet()) {
            if (!containedId2Comp.containsKey(containerCompId)) {
                String[] row = { "N/A", "N/A", containerId2Comp.get(containerCompId) + "",
                        "Extra compartment in checked instance", };
                rows.add(row);
            }
        }
        // Check contained
        for (String idRole : idRole2Contained.keySet()) {
            SimpleInstance inst = idRole2Contained.get(idRole);
            @SuppressWarnings("unchecked")
            List<SimpleInstance> instComps = (List<SimpleInstance>) inst
                    .getAttribute(ReactomeJavaConstants.compartment);
            if (instComps == null || instComps.size() == 0)
                continue;
            // Check if instComp has been listed
            for (SimpleInstance instComp : instComps) {
                if (!containerId2Comp.containsKey(instComp.getDbId())) {
                    String[] row = { inst.getAttribute("role") + "", inst + "", instComp + "",
                            "Reference compartment not listed" };
                    rows.add(row);
                }
            }
        }
        if (rows.size() > 0) {
            result.setIssue(COMPARTMENTS_MISMATCH);
            result.setColumns(colNames);
            result.setRows(rows);
        }
    }

}
