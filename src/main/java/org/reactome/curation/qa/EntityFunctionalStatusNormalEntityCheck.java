package org.reactome.curation.qa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.reactome.server.graph.domain.model.EntityFunctionalStatus;

public class EntityFunctionalStatusNormalEntityCheck extends QAChecker {
    
    public EntityFunctionalStatusNormalEntityCheck() {
        
    }
    
    @Override
    public String getCheckName() {
        return "EFS Normal Entity Inconsistency Check";
    }
    
    
    @Override
    public QACheckResult performQACheck(SimpleInstance instance) {
        String query = String.format("MATCH (efs:EntityFunctionalStatus {dbId: %d})\n"
                + "MATCH (efs)-[:normalEntity]->(normalEntity:PhysicalEntity)\n"
                + "MATCH (rle:ReactionLikeEvent)-[:entityFunctionalStatus]->(efs)\n"
                + "OPTIONAL MATCH (rle)-[:normalReaction]->(nr:ReactionLikeEvent)\n"
                + "OPTIONAL MATCH (nr)-[:input]->(input:PhysicalEntity)\n"
                + "OPTIONAL MATCH (nr)-[:catalystActivity]->(ca:CatalystActivity)-[:physicalEntity]->(catalyst:PhysicalEntity)\n"
                + "OPTIONAL MATCH (nr)-[:regulatedBy]->(reg:Regulation)-[:regulatedEntity]->(regulator:PhysicalEntity)\n"
                + "\n"
                + "WITH \n"
                + "  efs, normalEntity, rle, nr,\n"
                + "  collect(DISTINCT CASE WHEN input IS NOT NULL THEN {type: 'input', dbId: input.dbId, displayName: input.displayName} END) +\n"
                + "  collect(DISTINCT CASE WHEN catalyst IS NOT NULL THEN {type: 'catalyst', dbId: catalyst.dbId, displayName: catalyst.displayName} END) +\n"
                + "  collect(DISTINCT CASE WHEN regulator IS NOT NULL THEN {type: 'regulator', dbId: regulator.dbId, displayName: regulator.displayName} END) AS allParticipants\n"
                + "\n"
                + "UNWIND allParticipants AS participant\n"
                + "WITH * WHERE participant.dbId IS NOT NULL\n"
                + "\n"
                + "RETURN \n"
                + "  efs.dbId AS efsDbId,\n"
                + "  efs.displayName AS efsDisplayName,\n"
                + "  normalEntity.dbId AS normalEntityDbId,\n"
                + "  normalEntity.displayName AS normalEntityDisplayName,\n"
                + "  rle.dbId AS rleDbId,\n"
                + "  rle.displayName AS rleDisplayName,\n"
                + "  nr.dbId AS normalReactionDbId,\n"
                + "  nr.displayName AS normalReactionDisplayName,\n"
                + "  participant.type AS participantType,\n"
                + "  participant.dbId AS participantDbId,\n"
                + "  participant.displayName AS participantDisplayName", 
                instance.getDbId());
        Collection<Map<String, Object>> results = getNeoj4Client().query(query).fetch().all();
        if (results.isEmpty()) {
            return getEmptyResult();
        }
        // Get the normal entity dbId first for easy check
        Long normalEntityDbId =  null; // There should be only one normal entity per EntityFunctionalStatus
        for (Map<String, Object> row : results) {
            normalEntityDbId = (Long) row.get("normalEntityDbId");
            break;
        }
        // Check if the normal entity is used in the reaction-like event    
        Long normalReactionDbId = null;
        String normalReactionDisplayName = null;
        Long rleDbId = null; // This will be used to track the reaction-like event
        String rleDisplayName = null; // This will be used to track the reaction-like event display name
        Set<Long> normalReactionParticipantDbIds = new HashSet<>();
        List<String[]> rows = new ArrayList<>();
        for (Map<String, Object> row : results) {
            rleDbId = (Long) row.get("normalReactionDbId");
            if (rleDbId == null) 
                continue; // No reaction-like event
            if (normalReactionDbId == null) {
                normalReactionDbId = rleDbId;
                normalReactionDisplayName = (String) row.get("normalReactionDisplayName");
                rleDbId = (Long) row.get("rleDbId");
                rleDisplayName = (String) row.get("rleDisplayName");
            }
            else if (!normalReactionDbId.equals(rleDbId)) {
                createReportRow(normalEntityDbId, normalReactionDbId, normalReactionDisplayName, rleDbId,
                        rleDisplayName, normalReactionParticipantDbIds, rows);
                // Reset for the new reaction-like event
                normalReactionDbId = rleDbId;
                normalReactionDisplayName = (String) row.get("normalReactionDisplayName");
                rleDbId = (Long) row.get("rleDbId");
                rleDisplayName = (String) row.get("rleDisplayName");
                normalReactionParticipantDbIds.clear(); // Reset the set for a new reaction-like event
            } 
            Long participantDbId = (Long) row.get("participantDbId");
            if (participantDbId == null) 
                continue; // No participant
            normalReactionParticipantDbIds.add(participantDbId);
        }
        // Check the last reaction-like event
        createReportRow(normalEntityDbId, normalReactionDbId, normalReactionDisplayName, rleDbId,
                rleDisplayName, normalReactionParticipantDbIds, rows);
        if (rows == null || rows.isEmpty()) {
            return getEmptyResult(); // Nothing to report
        }
        String[] colNames = { "Disease Reaction", "Normal Reaction" };
        return createResult(colNames, rows);
    }

    private void createReportRow(Long normalEntityDbId,
                                 Long normalReactionDbId,
                                 String normalReactionDisplayName,
                                 Long rleDbId,
                                 String rleDisplayName,
                                 Set<Long> normalReactionParticipantDbIds,
                                 List<String[]> rows) {
        if (!normalReactionParticipantDbIds.contains(normalEntityDbId)) {
            // Need to report this since the normal entity is not in the normal reaction's participants
            // Create two SimpleInstance objects for reporting
            SimpleInstance diseaseReaction = new SimpleInstance();
            diseaseReaction.setDbId(rleDbId);
            diseaseReaction.setDisplayName(rleDisplayName);
            SimpleInstance normalReaction = new SimpleInstance();
            normalReaction.setDbId(normalReactionDbId); 
            normalReaction.setDisplayName(normalReactionDisplayName);
            String[] currentRow = new String[] {
                    diseaseReaction.toString(),
                    normalReaction.toString()
            };
            rows.add(currentRow);
        }
    }

    @Override
    public Collection<Class<?>> getTargetClasses() {
        return Collections.singleton(EntityFunctionalStatus.class);
    }

}
