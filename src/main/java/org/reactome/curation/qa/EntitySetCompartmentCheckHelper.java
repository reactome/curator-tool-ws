package org.reactome.curation.qa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;

/**
 * A utility class for EntitySet compartment checker.
 * The check logic here is copied directly from the Java desktop version's EntitySetCompartmentChecker.
 * https://github.com/reactome/CuratorTool/blob/master/src/org/gk/qualityCheck/EntitySetCompartmentCheck.java
 */
public class EntitySetCompartmentCheckHelper implements CompartmentCheckHelper {
    private static final String TOO_MANY_MEMBER_COMPARTMENTS = "More than two compartments in members";
    private static final String COMPARTMENTS_MISMATCH = "Compartment mismatch";
    
    public EntitySetCompartmentCheckHelper() {
    }
    
    @Override 
    public QACheckResult checkCompartment(Map<Long, SimpleInstance> containerId2Comp,
                                          Map<Long, SimpleInstance> includedId2Comp,
                                          Map<String, SimpleInstance> idRole2Contained,
                                          Map<Long, SimpleInstance> containedId2Comp) {
        QACheckResult result = new QACheckResult();
        // Check if there are more than two member compartments.
        if (containerId2Comp.size() > 2) {
            result.setIssue(TOO_MANY_MEMBER_COMPARTMENTS);
            String[] colNames = {"compartment"};
            List<String[]> rows = new ArrayList<>();
            for (SimpleInstance comp : containerId2Comp.values()) {
                rows.add(new String[]{comp + ""});
            }
            result.setColumns(colNames);
            result.setRows(rows);
            return result;
        }
        // Components and container should have the same number of compartments used.
        checkMismatch(containerId2Comp,
                      idRole2Contained,
                      containedId2Comp,
                      result);
        // Note: it is OK if neither the container nor the members have been assigned
        // a compartment. The mandatory checking should handle this case.
        return result;
    }
    
    private void checkMismatch(Map<Long, SimpleInstance> containerId2Comp,
                               Map<String, SimpleInstance> idRole2Contained,
                               Map<Long, SimpleInstance> containedId2Comp,
                               QACheckResult result) {
        String[] colNames = {"ReferredBy", "Reference", "Compartment", "Issue"};
        List<String[]> rows = new ArrayList<>();
        // Check if contained compartment is in contained
        for (Long containerCompId : containerId2Comp.keySet()) {
            if (!containedId2Comp.containsKey(containerCompId)) {
                String[] row = {
                        "N/A",
                        "N/A",
                        containerId2Comp.get(containerCompId) + "",
                        "Extra compartment in checked instance",
                };
                rows.add(row);
            }
        }
        // Check contained
        for (String idRole : idRole2Contained.keySet()) {
            SimpleInstance inst = idRole2Contained.get(idRole);
            @SuppressWarnings("unchecked")
            List<SimpleInstance> instComps = (List<SimpleInstance>) inst.getAttribute(ReactomeJavaConstants.compartment);
            if (instComps == null || instComps.size() == 0)
                continue;
            // Check if instComp has been listed
            for (SimpleInstance instComp : instComps) {
                if (!containerId2Comp.containsKey(instComp.getDbId())) {
                    String[] row = {
                            inst.getAttribute("role") + "",
                            inst + "",
                            instComp + "",
                            "Reference compartment not listed"
                    };
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
