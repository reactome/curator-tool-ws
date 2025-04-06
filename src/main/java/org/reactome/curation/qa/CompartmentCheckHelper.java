package org.reactome.curation.qa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.springframework.data.neo4j.core.Neo4jClient;

public abstract class CompartmentCheckHelper {
    private static final String COMPARTMENTS_MISMATCH = "Compartment mismatch";
    protected Neo4jClient neo4jClient;
    
    public void setNeo4jClient(Neo4jClient client) {
        this.neo4jClient = client;
    }

    public abstract QACheckResult checkCompartment(Map<Long, SimpleInstance> containerId2Comp,
                                                   Map<Long, SimpleInstance> includedId2Comp,
                                                   Map<String, SimpleInstance> idRole2Contained,
                                                   Map<Long, SimpleInstance> containedId2Comp);

    protected void fillResultForCOmpartmentList(Map<Long, SimpleInstance> id2inst, QACheckResult result) {
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
