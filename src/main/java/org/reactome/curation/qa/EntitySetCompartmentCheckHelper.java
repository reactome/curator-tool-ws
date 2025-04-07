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
public class EntitySetCompartmentCheckHelper extends CompartmentCheckHelper {
    private static final String TOO_MANY_MEMBER_COMPARTMENTS = "More than two compartments in members";
    
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
            fillResultForCompartmentList(containerId2Comp, result);
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
    
}
