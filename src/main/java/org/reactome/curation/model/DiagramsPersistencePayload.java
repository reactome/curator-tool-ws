package org.reactome.curation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Simple DTO for persisting a pathway diagram with account context.
 * Used for storing user-specific diagram customizations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(Include.NON_NULL)
public class DiagramsPersistencePayload implements Serializable {

    private JsonNode node;
    private Long pathwayDiagramId;

    /**
     * Constructor for creating a new instance during insert
     */
    public DiagramsPersistencePayload(String account, Long pathwayDiagramId, JsonNode node) {
        this.pathwayDiagramId = pathwayDiagramId;
        this.node = node;
    }
}

