package org.reactome.curation.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single input/output/catalyst participant of a reaction, as returned by
 * CurationRepository.findReactionStructuresByDbIds() -- dbId plus stoichiometry, read
 * directly off the graph relationship (see org.reactome.server.graph.domain.relationship.Has),
 * without hydrating the full participant object.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReactionParticipant {
    private Long dbId;
    private int stoichiometry;
}
