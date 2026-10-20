package org.reactome.curation.qa.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One protein (ReferenceGeneProduct) whose copy number differs between a reaction's inputs and outputs.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReferenceImbalance {
    private Long refDbId;
    private String identifier;
    private String displayName;
    private int inputCount;
    private int outputCount;
}
