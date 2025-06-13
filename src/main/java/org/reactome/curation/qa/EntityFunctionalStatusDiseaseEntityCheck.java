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

public class EntityFunctionalStatusDiseaseEntityCheck extends QAChecker {

    public EntityFunctionalStatusDiseaseEntityCheck() {

    }

    @Override
    public String getCheckName() {
        return "EFS Disease Entity Inconsistency Check";
    }


    @Override
    protected QACheckResult _performQACheck(SimpleInstance instance) {
        String query = String.format("MATCH (efs:EntityFunctionalStatus {dbId: %d})\n"
                + "MATCH (efs)-[:diseaseEntity]->(diseaseEntity:PhysicalEntity)\n"
                + "MATCH (rle:ReactionLikeEvent)-[:entityFunctionalStatus]->(efs)\n"
                + "OPTIONAL MATCH (rle)-[:input]->(input:PhysicalEntity)\n"
                + "OPTIONAL MATCH (rle)-[:catalystActivity]->(ca:CatalystActivity)-[:physicalEntity]->(catalyst:PhysicalEntity)\n"
                + "OPTIONAL MATCH (rle)-[:regulatedBy]->(reg:Regulation)-[:regulatedEntity]->(regulator:PhysicalEntity)\n"
                + "WITH \n"
                + "  efs, diseaseEntity, rle,\n"
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
                + "  diseaseEntity.dbId AS diseaseEntityDbId,\n"
                + "  diseaseEntity.displayName AS diseaseEntityDisplayName,\n"
                + "  rle.dbId AS rleDbId,\n"
                + "  rle.displayName AS rleDisplayName,\n"
                + "  participant.type AS participantType,\n"
                + "  participant.dbId AS participantDbId,\n"
                + "  participant.displayName AS participantDisplayName", 
                instance.getDbId());
        Collection<Map<String, Object>> results = getNeoj4Client().query(query).fetch().all();
        if (results.isEmpty()) {
            return getEmptyResult();
        }
        // Get the disease entity dbId first for easy check
        Long diseaseEntityDbId =  null; // There should be only one disease entity per EntityFunctionalStatus
        for (Map<String, Object> row : results) {
            diseaseEntityDbId = (Long) row.get("diseaseEntityDbId");
            break;
        }
        Long rleDbId = null; // This will be used to track the reaction-like event
        String rleDisplayName = null; // This will be used to track the reaction-like event display name
        Set<Long> participantDbIds = new HashSet<>();
        List<String[]> rows = new ArrayList<>();
        for (Map<String, Object> row : results) {
            Long currentRleDbId = (Long) row.get("rleDbId");
            if (currentRleDbId == null) 
                continue; // No reaction-like event
            if (rleDbId == null) {
                rleDbId = currentRleDbId; // Initialize the first reaction-like event
                rleDisplayName = (String) row.get("rleDisplayName");
            } 
            else if (!currentRleDbId.equals(rleDbId)) {
                if (!participantDbIds.contains(diseaseEntityDbId)) {
                    this.generateReportRow(diseaseEntityDbId, participantDbIds, rleDbId, rleDisplayName, rows);
                }
                // Reset for the new reaction-like event
                rleDbId = currentRleDbId;
                rleDisplayName = (String) row.get("rleDisplayName");
                participantDbIds.clear(); // Reset the set for a new reaction-like event
            } 
            Long participantDbId = (Long) row.get("participantDbId");
            if (participantDbId == null) 
                continue; // No participant
            participantDbIds.add(participantDbId);
        }
        // Check the last reaction-like event
        this.generateReportRow(diseaseEntityDbId, participantDbIds, rleDbId, rleDisplayName, rows);
        if (rows == null || rows.isEmpty()) {
            return getEmptyResult(); // Nothing to report
        }
        String[] colNames = { "Disease Reaction" };
        return createResult(colNames, rows);
    }

    private void generateReportRow(Long diseaseEntityDbId,
                                   Set<Long> participantDbIds,
                                   Long rleDbId, 
                                   String rleDisplayName, 
                                   List<String[]> rows) {
        if (participantDbIds.contains(diseaseEntityDbId)) {
            return; // No issue, disease entity is in the participants
        }   
        if (rows == null) {
            rows = new ArrayList<>();
        }
        SimpleInstance diseaseReaction = new SimpleInstance();
        diseaseReaction.setDbId(rleDbId);
        diseaseReaction.setDisplayName(rleDisplayName);
        rows.add(new String[] { diseaseReaction.toString()});
    }

    @Override
    public Collection<Class<?>> getTargetClasses() {
        return Collections.singleton(EntityFunctionalStatus.class);
    }

}
