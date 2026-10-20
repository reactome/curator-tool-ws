package org.reactome.curation.qa.model;

import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The result of checking all Reactions contained by an Event for input/output imbalance.
 * Only imbalanced reactions are listed.
 */
@Data
@NoArgsConstructor
public class ReactionImbalanceReport {
    private Long eventDbId;
    private String eventDisplayName;
    private int checkedReactions;
    private List<ReactionImbalance> reactions;
}
