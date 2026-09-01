package org.reactome.curation.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single hasModifiedResidue entry of an EntityWithAccessionedSequence (EWAS) -- the
 * ModifiedResidue's own dbId plus its psiMod's label, the only piece of a modified residue
 * actually drawn on the pathway diagram (as a small "node feature" mark on the EWAS node).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModifiedResidueEntry {
    private Long dbId;
    private String label;
}
