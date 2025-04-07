package org.reactome.curation.qa;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A utility class for ReactionlikeEvent compartment checker.
 * 
 * The following rules will be applied to check if there is any compartment
 * conflict among reaction participants and the reaction: 1. If more than three
 * compartments have been used in the reaction or its participants, the reaction
 * will be flagged. 2. If there are two compartments used in the reaction
 * participants: 1). If these two compartments are not adjacent (it is not
 * possible one compartment can contain another) the reaction should be flagged
 * 2). Reaction compartment should be checked: a. If the reaction has one
 * compartment, and this one compartment cannot contain these two compartment,
 * the reaction should be flagged b. If the reaction has two compartments, each
 * of them should be a container of one of two entity compartment (include the
 * identity). Otherwise, it should be flagged as false. 3. If there is only one
 * compartment used in the reaction participants, the reaction compartment
 * should be the same as this entity compartment. Otherwise, the reaction should
 * be flagged.
 * 
 * This class is ported from
 * https://github.com/reactome/CuratorTool/blob/master/src/org/gk/qualityCheck/ReactionCompartmentCheck.java.
 * 
 * @author wgm
 *
 */
@SuppressWarnings("unchecked")
public class ReactionCompartmentCheckHelper extends CompartmentCheckHelper {
    private final Logger logger = LoggerFactory.getLogger(ReactionCompartmentCheckHelper.class);

    // The first case is not used!
//    private static final String EXTRA_REACTION_COMPARTMENTS = "One participant compartment but more than one reaction compartment";
    private static final String COMPARTMENT_CONTAINMENT = "Reaction's compartment doesn't contain participants' compartments";
    private static final String COMPARTMENTS_NOT_ADJACENT = "Participants' two compartments are not adjacent";
    private static final String TOO_MANY_REACTION_COMPARTMENTS = "More than 2 compartments in reaction";
    private static final String NO_REACTION_COMPARTMENT = "No reaction compartment";
    private static final String TOO_MANY_PARTICIPANT_COMPARTMENTS = "More than 2 compartments in participants";
    // A list of allowed Event Compartment to EntityCompartment: DB_ID,DB_ID.
    // This list can be applied to single Reaction compartment and single
    // Entity compartment
    private List<String> allowedRxtEntityCompartments;

    public ReactionCompartmentCheckHelper() {

    }

    /**
     * Modified directly from
     * https://github.com/reactome/CuratorTool/blob/master/src/org/gk/qualityCheck/ReactionCompartmentCheck.java.
     * 
     * @return
     * @throws IOException
     */
    private List<String> getAllowedRxtEntityCompartments() {
        if (allowedRxtEntityCompartments == null) {
            // Need to load
            allowedRxtEntityCompartments = new ArrayList<>();
            try {
                logger.info("Loading AllowedEntityEventCompartments.txt...");
                InputStream input = getClass().getClassLoader()
                        .getResourceAsStream("AllowedEntityEventCompartments.txt");
                InputStreamReader ris = new InputStreamReader(input);
                BufferedReader bufferedReader = new BufferedReader(ris);
                String line = null;
                int index = 0;
                while ((line = bufferedReader.readLine()) != null) {
                    // 70101,13325 #cytosol - glycogen granule
                    if (line.startsWith("//"))
                        continue; // Comment
                    index = line.indexOf("#");
                    String sub = line.substring(0, index).trim();
                    allowedRxtEntityCompartments.add(sub);
                }
                bufferedReader.close();
                ris.close();
                input.close();
                logger.info("Done loading.");
            } catch (IOException e) {
                logger.error("getAllowedRxtEntityCompartments(): " + e.getMessage(), e);
            }
        }
        return allowedRxtEntityCompartments;
    }

    @Override
    public QACheckResult checkCompartment(Map<Long, SimpleInstance> containerId2Comp,
                                          Map<Long, SimpleInstance> includedId2Comp,
                                          Map<String, SimpleInstance> idRole2Contained,
                                          Map<Long, SimpleInstance> containedId2Comp) {
        QACheckResult result = new QACheckResult();
        if (containerId2Comp.size() == 0) {
            result.setIssue(NO_REACTION_COMPARTMENT); // compartment is a mandatory attribute. This error should be
                                                      // caughter earlier on.
                                                      // Adding here just for sanity check.
            return result;
        }
        if (containerId2Comp.size() > 2) {
            result.setIssue(TOO_MANY_REACTION_COMPARTMENTS);
            fillResultForCompartmentList(containerId2Comp, result);
            return result;
        }
        if (containedId2Comp.size() > 2) {
            result.setIssue(TOO_MANY_PARTICIPANT_COMPARTMENTS);
            fillResultForParticipants(idRole2Contained, result);
            return result;
        }
        if (containedId2Comp.size() == 2) {
            // Check adjacency
            List<Long[]> nonAdjacency = getNonAdjacency(containedId2Comp.keySet());
            if (nonAdjacency.size() > 0) {
                fillNonAdjancencyReport(containedId2Comp, idRole2Contained, nonAdjacency, result);
                return result;
            }
            checkCompartmentContainer(containerId2Comp, idRole2Contained, containedId2Comp, result);
            if (!result.isPassed())
                return result;
        } else if (containedId2Comp.size() == 1) {
            // Reaction can have only one compartment at most
            if (containerId2Comp.size() != 1) {
                result.setIssue(TOO_MANY_REACTION_COMPARTMENTS);
                fillResultForCompartmentList(containerId2Comp, result);
                return result;
            }
            Long partCompId = containedId2Comp.keySet().iterator().next();
            Long rxtCompId = containerId2Comp.keySet().iterator().next();
            Set<Long> containers = getContainerIds(partCompId);
            if (!allowContainerRelationship(rxtCompId, partCompId, containers)) {
                result.setIssue(COMPARTMENT_CONTAINMENT);
                fillCompartmentContainmentReport(containerId2Comp, idRole2Contained, containedId2Comp, result);
                return result;
            }
        }
        return result;
    }

    private boolean allowContainerRelationship(Long rxtCompId, Long partCompId, Set<Long> partCompContainerIds) {
        if (partCompContainerIds.contains(rxtCompId))
            return true;
        List<String> allowedCases = getAllowedRxtEntityCompartments();
        String key = rxtCompId + "," + partCompId;
        if (allowedCases.contains(key))
            return true;
        return false;
    }

    private void checkCompartmentContainer(Map<Long, SimpleInstance> containerId2Comp,
                                           Map<String, SimpleInstance> idRole2Contained,
                                           Map<Long, SimpleInstance> containedId2Comp,
                                           QACheckResult result) {
        // Check compartment container/contained relationship (Note: This is not about
        // the checked reaction. It is about the referred compartments.)
        // Make sure container's compartments (here reaction's compartments) are
        // contained or equal to
        // reaction's participants' compartments.
        Iterator<Long> it = containedId2Comp.keySet().iterator();
        Long partCompId1 = it.next();
        Long partCompId2 = it.next();
        if (containerId2Comp.size() == 1) {
            Set<Long> containerIds1 = getContainerIds(partCompId1);
            Set<Long> containerIds2 = getContainerIds(partCompId2);
            // Ensure that the two entity compartments are contained by
            // the single Reaction compartment.
            Long rxtCompId = containerId2Comp.keySet().iterator().next();
            // Both participant compartments should contain the reaction compartment
            // (including equal)
            if (!allowContainerRelationship(rxtCompId, partCompId1, containerIds1)
                    || !allowContainerRelationship(rxtCompId, partCompId2, containerIds2)) {// Means a reaction's
                                                                                            // compartment may contain
                                                                                            // participant's
                                                                                            // compartments
                result.setIssue(COMPARTMENT_CONTAINMENT);
                fillCompartmentContainmentReport(containerId2Comp, idRole2Contained, containedId2Comp, result);
                return;
            }
        } else if (containerId2Comp.size() == 2) {
            Iterator<Long> iterator = containerId2Comp.keySet().iterator();
            Long rxtComptId1 = iterator.next();
            Long rxtComptId2 = iterator.next();
            if (!checkTwoReactionAndTwoEntityCompartments(partCompId1, partCompId2, rxtComptId1, rxtComptId2)) {
                // Ensure that the two reaction compartment are containers
                // for each of the two entitycompartment, respectively.
                result.setIssue(COMPARTMENT_CONTAINMENT);
                fillCompartmentContainmentReport(containerId2Comp, idRole2Contained, containedId2Comp, result);
                return;
            }
        }
    }

    private boolean checkTwoReactionAndTwoEntityCompartments(Long entityCompartment1,
                                                             Long entityCompartment2,
                                                             Long rxtCompartment1,
                                                             Long rxtCompartment2) {
        Set<Long> containers1 = getContainerIds(entityCompartment1);
        Set<Long> containers2 = getContainerIds(entityCompartment2);
        // Compare rxtCompartment1 to entityCompartment1,
        // and rxtCompartment2 to entityComparmment2
        if (allowContainerRelationship(rxtCompartment1, entityCompartment1, containers1)
                && allowContainerRelationship(rxtCompartment2, entityCompartment2, containers2))
            return true;
        // Compare rxtCompartment1 to entityCompartment2,
        // and rxtCompartment2 to entityCompartment1
        if (allowContainerRelationship(rxtCompartment1, entityCompartment2, containers2)
                && allowContainerRelationship(rxtCompartment2, entityCompartment1, containers1))
            return true;
        return false;
    }

    private void fillCompartmentContainmentReport(Map<Long, SimpleInstance> containerId2Comp,
                                                  Map<String, SimpleInstance> idRole2Contained,
                                                  Map<Long, SimpleInstance> containedId2Comp,
                                                  QACheckResult result) {
        String[] colNames = { "ReferredBy", "Reference", "Compartment" };
        List<String[]> rows = new ArrayList<>();
        result.setColumns(colNames);
        result.setRows(rows);
        for (Long containerCompId : containerId2Comp.keySet()) {
            String[] row = { "N/A", "N/A", containerId2Comp.get(containerCompId) + "" };
            rows.add(row);
        }
        for (String idRole : idRole2Contained.keySet()) {
            SimpleInstance inst = idRole2Contained.get(idRole);
            List<SimpleInstance> instComps = (List<SimpleInstance>) inst
                    .getAttribute(ReactomeJavaConstants.compartment);
            if (instComps == null || instComps.size() == 0)
                continue;
            for (SimpleInstance instComp : instComps) {
                String[] row = { inst.getAttribute("role") + "", inst + "", instComp + "" };
                rows.add(row);

            }
        }
    }

    private Set<Long> getContainerIds(Long dbId) {
        Set<Long> containerIds = new HashSet<>();
        containerIds.add(dbId); // Add itself
        if (this.getNeo4jClient() == null)
            return containerIds;
        String query = String.format("match (compartment:Compartment {dbId: %d})-" + "[r:componentOf|instanceOf*]->"
                + "(container:GO_CellularComponent) " // Return GO_CeuularComponents just in case
                + "return distinct container.dbId", dbId);
        Collection<Map<String, Object>> results = getNeo4jClient().query(query).fetch().all();
        for (Map<String, Object> map : results) {
            Long containerId = (Long) map.get("container.dbId");
            containerIds.add(containerId);
        }
        return containerIds;
    }

    private void fillNonAdjancencyReport(Map<Long, SimpleInstance> containedId2Comp,
                                         Map<String, SimpleInstance> idRole2Contained,
                                         List<Long[]> nonAdjacency,
                                         QACheckResult result) {
        result.setIssue(COMPARTMENTS_NOT_ADJACENT);
        // Need to reset columns
        result.setColumns(new String[] { "attribute_1", "participant_1", "compartment_1", "attribute_2",
                "participant_2", "compartment_2" });
        List<String[]> rows = new ArrayList<>();
        result.setRows(rows);
        for (Long[] ids : nonAdjacency) {
            // Scan all participants for referring to the two compartments based on their
            // ids
            List<SimpleInstance> partcipants1 = new ArrayList<>();
            List<SimpleInstance> partcipants2 = new ArrayList<>();
            for (String idRole : idRole2Contained.keySet()) {
                SimpleInstance participant = idRole2Contained.get(idRole);
                List<SimpleInstance> compartments = (List<SimpleInstance>) participant
                        .getAttribute(ReactomeJavaConstants.compartment);
                if (QAChecker.contains(compartments, ids[0]))
                    partcipants1.add(participant);
                if (QAChecker.contains(compartments, ids[1]))
                    partcipants2.add(participant); // An participant may have more than one compartment according to the
                                                   // data model. These two compartments
                                                   // may not be adjacent.
            }
            // Add these to rows
            for (SimpleInstance part1 : partcipants1) {
                String role1 = (String) part1.getAttribute("role");
                SimpleInstance comp1 = containedId2Comp.get(ids[0]);
                for (SimpleInstance part2 : partcipants2) {
                    String role2 = (String) part2.getAttribute("role");
                    SimpleInstance comp2 = containedId2Comp.get(ids[1]);
                    String[] row = { role1 == null ? "" : role1, part1.toString(), comp1.toString(),
                                     role2 == null ? "" : role2, part2.toString(), comp2.toString() };
                    rows.add(row);
                }
            }
        }
    }

    private void fillResultForParticipants(Map<String, SimpleInstance> idRole2Contained, QACheckResult result) {
        String[] cols = new String[] { "ReferredBy", "Referrence", "Compartment" };
        List<String[]> rows = new ArrayList<>();
        result.setColumns(cols);
        result.setRows(rows);
        for (String idRole : idRole2Contained.keySet()) {
            SimpleInstance inst = idRole2Contained.get(idRole);
            String role = (String) inst.getAttribute("role");
            List<SimpleInstance> compartments = (List<SimpleInstance>) inst
                    .getAttribute(ReactomeJavaConstants.compartment);
            for (SimpleInstance comp : compartments) {
                String[] row = new String[] { role == null ? "" : role, // Just in case
                        inst.toString(), comp.toString() };
                rows.add(row);
            }
        }
    }

}
