package org.reactome.curation.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The dbId-level structure of a single reaction (inputs, outputs, catalysts, regulators),
 * returned by CurationRepository.findReactionStructuresByDbIds() using a targeted Cypher query
 * that reads only the specific relationships needed -- not the full relationship-graph load
 * that AdvancedDatabaseObjectRepository.findById()/findInstances() perform, which can hang for
 * heavily cross-referenced small molecules (e.g. ATP, ADP).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReactionStructure {
    private Long reactionDbId;
    private List<ReactionParticipant> inputs;
    private List<ReactionParticipant> outputs;
    private List<Long> catalysts;
    private List<ReactionRegulator> regulators;
}
