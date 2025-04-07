package org.reactome.curation.qa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;

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
 *
 * The following constraints are not validated until curators are ready to
 * address them:
 * <ul>
 * <li>There should not be more than two compartment values in all
 * subunits.</li>
 * </ul>
 */
@SuppressWarnings("unchecked")
public class ComplexCompartmentCheckHelper extends CompartmentCheckHelper {
    // A list of pre-defined issues.
    private final static String MISSING_COMPLEX_COMPARTMENT = "Complex compartment not a subunit compartment";
    private final static String TOO_MANY_COMPLEX_COMPARTMENTS = "More than one complex compartment";
    private final static String INCLUDED_NOT_IN_CONTAINED = "Extra included location";
    private final static String CONTAINED_NOT_IN_INCLUDED = "Missing included location";
    // Covered by mismatch now
//    private final static String TOO_MANY_SUBUNIT_COMPARTMENTS = "More than two subunit compartments";
    private static final String COMPARTMENTS_NOT_ADJACENT = "Noncontiguous compartments";

    public ComplexCompartmentCheckHelper() {
    }

    /**
     * Ported from the Java desktop version:
     * https://github.com/reactome/CuratorTool/blob/61e792e5180b0f38259a37f2052c06c9c925325b/
     * src/org/gk/qualityCheck/ComplexCompartmentCheck.java#L102.
     */
    @Override
    public QACheckResult checkCompartment(Map<Long, SimpleInstance> containerId2Comp,
                                          Map<Long, SimpleInstance> includedId2Comp,
                                          Map<String, SimpleInstance> idRole2Contained,
                                          Map<Long, SimpleInstance> containedId2Comp) {
        QACheckResult result = new QACheckResult();
        if (containerId2Comp.size() == 0) {
            result.setIssue(MISSING_COMPLEX_COMPARTMENT);
            return result;
        }
        if (containerId2Comp.size() > 1) {
            result.setIssue(TOO_MANY_COMPLEX_COMPARTMENTS);
            fillResultForCompartmentList(containerId2Comp, result);
            return result;
        }
        // Keep this for the future discussion.
//      This condition is not yet reported per the class javadoc.
//      if (containedCompartments.size() > 2)
//          return new Issue(Issue.Type.EXTRA_SUBUNIT_COMPARTMENTS);
        // There should be only one compartment assigned to the checked complex
        // Two cases related to included location: empty or not
        if (includedId2Comp == null || includedId2Comp.size() == 0) {
            // All the cases in the original desktop version can be handled by this mismatch
            // check
            checkMismatch(containerId2Comp, idRole2Contained, containedId2Comp, result);
            if (!result.isPassed())
                return result;
        } else {
            // Check if contained compartment is in the included location if it is not
            // covered by container component
            checkIncludedLocation(containerId2Comp, includedId2Comp, idRole2Contained, containedId2Comp, result);
            if (!result.isPassed())
                return result;
        }

        // Check adjacency
        if (getNeo4jClient() == null)
            return result; // Nothing we can do if this is not set since we need to query the database.
        
        checkAdjacency(containerId2Comp, includedId2Comp, result);
        return result;
    }

    private void checkAdjacency(Map<Long, SimpleInstance> containerId2Comp,
                                Map<Long, SimpleInstance> includedId2Comp,
                                QACheckResult result) {
        // The adjacency check is applied to container compartment and included
        Set<Long> idset = new HashSet<>(containerId2Comp.keySet());
        idset.addAll(includedId2Comp.keySet());
        List<Long[]> nonAdjacent = getNonAdjacency(idset);
        if (nonAdjacent.size() > 0) {
            result.setIssue(COMPARTMENTS_NOT_ADJACENT);
            // Need to reset columns
            result.setColumns(new String[]{"attribute_1", "compartment_1", "attribute_2", "compartment_2"});
            List<String[]> rows = new ArrayList<>();
            result.setRows(rows);
            for (Long[] ids : nonAdjacent) {
                Long id1 = ids[0];
                SimpleInstance comp1 = containerId2Comp.containsKey(id1) 
                        ? containerId2Comp.get(id1) 
                        : includedId2Comp.get(id1);
                String att1 = containerId2Comp.containsKey(id1) 
                        ? ReactomeJavaConstants.compartment 
                        : ReactomeJavaConstants.includedLocation;
                Long id2 = ids[1];
                SimpleInstance comp2 = containerId2Comp.containsKey(id2) 
                        ? containerId2Comp.get(id2) 
                        : includedId2Comp.get(id2);
                String att2 = containerId2Comp.containsKey(id2) 
                        ? ReactomeJavaConstants.compartment 
                        : ReactomeJavaConstants.includedLocation;
                String[] row = {att1, comp1.toString(), att2, comp2.toString()};
                rows.add(row);
            }
        }
    }

    private void checkIncludedLocation(Map<Long, SimpleInstance> containerId2Comp,
                                       Map<Long, SimpleInstance> includedId2Comp,
                                       Map<String, SimpleInstance> idRole2Contained,
                                       Map<Long, SimpleInstance> containedId2Comp,
                                       QACheckResult result) {
        // Make a copy
        Map<Long, SimpleInstance> containedId2CompCopy = new HashMap<>(containedId2Comp);
        // Keep compartments that are not list so that we can compare with included
        containedId2CompCopy.keySet().removeAll(containerId2Comp.keySet());
        if (containedId2CompCopy.keySet().equals(includedId2Comp.keySet()))
            return; // Nothing is wrong in this case

        // Generate report
        String[] cols = new String[] { "ReferredBy", "Reference", "Compartment", "Issue" };
        List<String[]> rows = new ArrayList<>();
        result.setColumns(cols);
        result.setRows(rows);

        // Clone it to avoid modifying the original copy
        Map<Long, SimpleInstance> includedId2CompCopy = new HashMap<>(includedId2Comp);
        includedId2CompCopy.keySet().removeAll(containedId2CompCopy.keySet());
        // Need to use the original copy
        containedId2CompCopy.keySet().removeAll(includedId2Comp.keySet());
        if (containedId2CompCopy.isEmpty()) {
            result.setIssue(INCLUDED_NOT_IN_CONTAINED);
            // Report included
            for (Long dbId : includedId2CompCopy.keySet()) {
                SimpleInstance inst = includedId2CompCopy.get(dbId);
                String[] row = { inst.getAttribute("role") + "", "N/A", inst + "", INCLUDED_NOT_IN_CONTAINED };
                rows.add(row);
            }
            return;
        }
        if (includedId2CompCopy.isEmpty())
            result.setIssue(CONTAINED_NOT_IN_INCLUDED);
        else
            result.setIssue("Included locations and subunit compartments mismatch");
        // No report
        for (Long dbId : includedId2CompCopy.keySet()) {
            SimpleInstance inst = includedId2CompCopy.get(dbId);
            String[] row = { "N/A", "N/A", inst + "", INCLUDED_NOT_IN_CONTAINED };
            rows.add(row);
        }
        // Extra in the subunits. Need to figure out what PE has the compartment
        // Check contained only
        for (String idRole : idRole2Contained.keySet()) {
            SimpleInstance inst = idRole2Contained.get(idRole);
            List<SimpleInstance> instComps = (List<SimpleInstance>) inst
                    .getAttribute(ReactomeJavaConstants.compartment);
            if (instComps == null || instComps.size() == 0)
                continue;
            // Check if instComp has been listed
            for (SimpleInstance instComp : instComps) {
                // The following two checks can use the copied ones. But it should be fine.
                // This case should be handled previously. List here just for completeness.
                if (containerId2Comp.containsKey(instComp.getDbId()))
                    continue;
                if (includedId2Comp.containsKey(instComp.getDbId()))
                    continue;
                String[] row = { inst.getAttribute("role") + "", inst + "", instComp + "", CONTAINED_NOT_IN_INCLUDED };
                rows.add(row);
            }
        }
    }

}
