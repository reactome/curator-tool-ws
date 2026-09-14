package org.reactome.curation.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One circular hasEvent relationship that getEventTree() had to drop in order to build the
 * hierarchy at all.
 *
 * A cycle in hasEvent has no meaning as a hierarchy and cannot be rendered as a tree, so the
 * relationship that closes it is left out of the event tree (see
 * CurationRepository.populateChildren). Reporting it rather than only logging it is the point:
 * the curator tool no longer refuses the edits that could create a cycle - establishing what
 * already contains an event took a request per edit - so the event tree is where a curator finds
 * out, and it can only tell them if the response says what was dropped.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventTreeCycle {

    /**
     * The events that form the cycle, starting with the event that ends up inside itself and
     * running down to the event whose hasEvent points back at it. The dropped relationship is the
     * one from the last entry back to the first, so a path of [Metabolism, Glycolysis] means
     * "Glycolysis has Metabolism in its hasEvent, and Metabolism already contains Glycolysis".
     * A single-entry path is an event listed in its own hasEvent.
     */
    private List<DbIdDisplayName> path;
}
