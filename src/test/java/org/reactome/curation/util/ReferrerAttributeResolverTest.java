package org.reactome.curation.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.reactome.server.graph.domain.model.Complex;
import org.reactome.server.graph.domain.model.Pathway;
import org.reactome.server.graph.domain.model.Reaction;
import org.reactome.server.graph.domain.model.ReferenceGeneProduct;
import org.springframework.data.neo4j.core.schema.Relationship.Direction;

/**
 * Referrer resolution has to go from a Neo4j relationship (type + direction) back to the
 * curator-facing attribute name. It cannot just look the type up as a field name: the edge
 * {@code (source)-[:inferredTo]->(inferred)} is what makes {@code inferred.inferredFrom =
 * source}, but "inferredFrom" is not a relationship type anywhere in the graph, so a
 * field-name lookup finds nothing and the reaction holding an inferredFrom value never
 * appears in its source reaction's referrer list.
 */
public class ReferrerAttributeResolverTest {

    /**
     * The reported case: Reaction 9969523 has inferredFrom = Reaction 9969454, stored as
     * (9969454)-[:inferredTo]->(9969523). Asking for 9969454's referrers finds that edge
     * pointing AWAY from 9969454, so from the referrer's (9969523's) end it is INCOMING.
     */
    @Test
    void inferredToViewedFromTheInferredEndIsInferredFrom() {
        assertThat(ReferrerAttributeResolver.resolveAttributeNames(Reaction.class, "Reaction",
                "inferredTo", Direction.INCOMING))
                .containsExactly("inferredFrom");
    }

    /**
     * The same edge from the other end: 9969454.orthologousEvent = 9969523. Both ends have
     * to resolve, otherwise one of the two instances silently loses the reference.
     */
    @Test
    void inferredToViewedFromTheSourceEndIsOrthologousEvent() {
        assertThat(ReferrerAttributeResolver.resolveAttributeNames(Reaction.class, "Reaction",
                "inferredTo", Direction.OUTGOING))
                .containsExactly("orthologousEvent");
    }

    /**
     * PhysicalEntity.inferredFrom is the same INCOMING view of "inferredTo" but is
     * additionally @ReactomeTransient, so it is absent from the relationship map used for
     * WRITING an instance back. It is still what a curator sees holding the reference.
     */
    @Test
    void transientInferredFromOnAnEntityAlsoResolves() {
        assertThat(ReferrerAttributeResolver.resolveAttributeNames(Complex.class, "Complex",
                "inferredTo", Direction.INCOMING))
                .containsExactly("inferredFrom");
        // On an entity the source end IS named after the relationship type
        assertThat(ReferrerAttributeResolver.resolveAttributeNames(Complex.class, "Complex",
                "inferredTo", Direction.OUTGOING))
                .containsExactly("inferredTo");
    }

    /**
     * Attributes whose name already equals the relationship type keep resolving to
     * themselves, in the declared direction only.
     */
    @Test
    void attributeNamedAfterItsRelationshipTypeStillResolves() {
        assertThat(ReferrerAttributeResolver.resolveAttributeNames(Pathway.class, "Pathway",
                "hasEvent", Direction.OUTGOING))
                .containsExactly("hasEvent");
        assertThat(ReferrerAttributeResolver.resolveAttributeNames(Reaction.class, "Reaction",
                "input", Direction.OUTGOING))
                .containsExactly("input");
    }

    /**
     * graph-core's convenience reverse views are NOT attributes of the instance declaring
     * them, so they must never be reported as an attribute holding a reference - otherwise
     * a Complex's referrer list would list its own components (via "componentOf"), and a
     * PhysicalEntity's would list its own ReferenceEntity (via ReferenceEntity's
     * "physicalEntity" view of "referenceEntity").
     */
    @Test
    void graphOnlyReverseViewsAreNotReportedAsAttributes() {
        assertThat(ReferrerAttributeResolver.resolveAttributeNames(Complex.class, "Complex",
                "hasComponent", Direction.INCOMING))
                .isEmpty();
        assertThat(ReferrerAttributeResolver.resolveAttributeNames(Reaction.class, "Reaction",
                "hasEvent", Direction.INCOMING))
                .isEmpty();
        assertThat(ReferrerAttributeResolver.resolveAttributeNames(Pathway.class, "Pathway",
                "normalPathway", Direction.INCOMING))
                .isEmpty();
        assertThat(ReferrerAttributeResolver.resolveAttributeNames(ReferenceGeneProduct.class,
                "ReferenceGeneProduct", "referenceEntity", Direction.INCOMING))
                .isEmpty();
    }

    @Test
    void unknownRelationshipTypeResolvesToNothing() {
        assertThat(ReferrerAttributeResolver.resolveAttributeNames(Reaction.class, "Reaction",
                "notARelationship", Direction.OUTGOING))
                .isEmpty();
    }

    @Test
    void curationSchemaIsLoadedForBothSpellingsOfReactionLikeEvent() {
        assertThat(ReferrerAttributeResolver.isCurationAttribute("Reaction", "inferredFrom")).isTrue();
        assertThat(ReferrerAttributeResolver.isCurationAttribute("Reaction", "componentOf")).isFalse();
        // The curation schema spells this class the MySQL data model's way
        assertThat(ReferrerAttributeResolver.isCurationAttribute("ReactionLikeEvent", "inferredFrom")).isTrue();
    }
}
