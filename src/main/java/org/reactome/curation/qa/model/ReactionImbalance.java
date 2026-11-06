package org.reactome.curation.qa.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An imbalanced Reaction together with the proteins that are out of balance.
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReactionImbalance {
    private Long dbId;
    private String displayName;
    // True if the imbalance looks like a protein cleavage (the same protein with different end coordinates)
    private boolean cleavage;
    private String lastAuthor;
    private List<ReferenceImbalance> imbalances;
}
