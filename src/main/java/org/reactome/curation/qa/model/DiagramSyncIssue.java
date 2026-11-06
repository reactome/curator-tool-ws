package org.reactome.curation.qa.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An Event drawn in a PathwayDiagram that is out of sync with its annotation in the database.
 * Only one issue is reported per Event per diagram, the same as the desktop ReactionSyncELVCheck.
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DiagramSyncIssue {
    private Long diagramDbId;
    private Long pathwayDbId;
    private String pathwayDisplayName;
    // The offending Event: a ReactionlikeEvent, or a Pathway node for flow line issues
    private Long eventDbId;
    private String eventDisplayName;
    private String eventSchemaClass;
    // e.g. "input out of sync" or "precedingEvent out of sync: missing 12345"
    private String issue;
    // Set for precedingEvent issues only: the Event at the tail of the flow line that should be
    // listed in eventDbId's precedingEvent slot.
    private Long missingPrecedingEventDbId;
    private String created;
    private String modified;
}
