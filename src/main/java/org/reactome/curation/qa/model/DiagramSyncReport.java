package org.reactome.curation.qa.model;

import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The result of checking the PathwayDiagrams of an Event (and its sub-pathways) against the database.
 * Only out of sync Events are listed.
 */
@Data
@NoArgsConstructor
public class DiagramSyncReport {
    private Long eventDbId;
    private String eventDisplayName;
    private int checkedDiagrams;
    // Diagrams that could not be checked because neither a Cytoscape JSON file nor a
    // layout JSON file could be found for them.
    private List<Long> uncheckedDiagramDbIds;
    private List<DiagramSyncIssue> issues;
}
