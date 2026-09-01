package org.reactome.curation.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An EntityWithAccessionedSequence's full set of modified residues, returned by
 * CurationRepository.findModifiedResiduesByDbIds() using a targeted Cypher query that reads
 * only the hasModifiedResidue/psiMod relationships needed -- not the full relationship-graph
 * load that AdvancedDatabaseObjectRepository.findById()/findInstances() perform.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EwasModifiedResidues {
    private Long ewasDbId;
    private List<ModifiedResidueEntry> residues;
}
