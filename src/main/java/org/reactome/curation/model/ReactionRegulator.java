package org.reactome.curation.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single regulator (the actual regulating PhysicalEntity, already resolved through the
 * Regulation helper node) of a reaction, as returned by
 * CurationRepository.findReactionStructuresByDbIds(). The raw Neo4j labels of the Regulation
 * node are included (e.g. PositiveRegulation/NegativeRegulation/Requirement) so callers can
 * classify activator vs. inhibitor themselves, the same way PathwayDiagramValidator already
 * does from schemaClassName.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReactionRegulator {
    private Long dbId;
    private List<String> labels;
}
