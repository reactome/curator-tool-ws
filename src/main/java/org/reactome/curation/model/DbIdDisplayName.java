package org.reactome.curation.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight dbId/displayName pair, returned by CurationRepository.findDisplayNamesByDbIds()
 * for callers (e.g. the pathway diagram content validator) that only need a label, not a full
 * Instance with attributes.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DbIdDisplayName {
    private Long dbId;
    private String displayName;
}
