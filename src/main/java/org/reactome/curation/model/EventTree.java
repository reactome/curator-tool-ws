package org.reactome.curation.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The response of getEventTree(): the pathway hierarchy, plus whatever circular hasEvent
 * relationships had to be dropped to build it.
 *
 * The endpoint used to return the top-level events as a bare array. The cycles travel with the
 * tree rather than through an endpoint of their own so that a curator is always told about a cycle
 * present in the hierarchy they were actually given - a second request could be answered from a
 * cache rebuilt in between, or add a round trip on every event view load just to report nothing.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventTree {

    /** The top-level events, each with its hasEvent populated recursively. */
    private List<SimpleInstance> events;

    /**
     * The circular hasEvent relationships left out of {@link #events}, deduplicated: a cycle deep
     * in the hierarchy is reached once per route down to it, but it is one thing for the curator
     * to fix. Empty when the hierarchy is sound, which is the normal case.
     */
    private List<EventTreeCycle> cycles;
}
