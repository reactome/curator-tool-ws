package org.reactome.curation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.neo4j.cypherdsl.core.Cypher;
import org.neo4j.cypherdsl.core.Node;
import org.neo4j.cypherdsl.core.Relationship;
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReading;
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingUpdate;

/**
 * CurationRepository.store()'s Step 6 (see its "Working on relationships" comment) rebuilds an
 * object's relationships by chaining one Cypher MATCH per relationship target
 * (CurationRepository.handleValueObj()), then issuing ONE combined CREATE for all of them in a
 * SINGLE query. This test doesn't touch a database - it reproduces the exact same Cypher-DSL
 * builder calls store() makes and inspects the rendered Cypher text, to confirm the query SHAPE
 * that causes the "one bad reference wipes every relationship" failure mode: multiple plain
 * (non-OPTIONAL) MATCH clauses are effectively ANDed together, so if even one relationship
 * target's dbId+label doesn't match any node (e.g. a stale/mistyped dbId, or a reference that's
 * actually stored as a different class than expected, such as a Complex reference that turns out
 * to be an InstanceEdit), the WHOLE query returns zero rows and NONE of the CREATEs fire - not
 * just the one for the bad reference.
 */
public class CurationRepositoryRelationshipRecreationTest {

    @Test
    void relationshipRecreationShouldChainPlainMatchesNotOptionalMatches() {
        // Mirrors store()'s objNode + the loop of handleValueObj() calls for two relationship
        // targets - one "good" (hasComponent), one that in a real bug scenario would actually be
        // stored under a different label than assumed (modified -> InstanceEdit).
        Node objNode = Cypher.node("Complex").named("n0").withProperties("dbId", Cypher.literalOf(100L));
        OngoingReading stat = Cypher.match(objNode);

        Node componentNode = Cypher.node("PhysicalEntity").named("n1").withProperties("dbId", Cypher.literalOf(200L));
        stat = stat.match(componentNode);

        // In the bug scenario this dbId actually belongs to an InstanceEdit node, not a Complex -
        // but the MATCH pattern is still built from the (wrong) expected label.
        Node modifiedNode = Cypher.node("Complex").named("n2").withProperties("dbId", Cypher.literalOf(300L));
        stat = stat.match(modifiedNode);

        List<Relationship> relationships = new ArrayList<>();
        relationships.add(objNode.relationshipTo(componentNode, "hasComponent"));
        relationships.add(objNode.relationshipTo(modifiedNode, "modified"));

        OngoingUpdate update = null;
        for (Relationship relationship : relationships) {
            update = (update == null) ? stat.create(relationship) : update.create(relationship);
        }
        String cypher = update.build().getCypher();
        // Renders as one single-line statement, e.g.:
        // MATCH (n0:`Complex` {dbId: 100}) MATCH (n1:`PhysicalEntity` {dbId: 200})
        // MATCH (n2:`Complex` {dbId: 300}) CREATE (n0)-[:`hasComponent`]->(n1) CREATE (n0)-[:`modified`]->(n2)

        // The failure mode depends entirely on these two facts about the generated query:
        // 1. Every relationship target is matched with a plain MATCH, never OPTIONAL MATCH -
        //    so Neo4j requires ALL of them to find a node for any row to survive.
        assertThat(cypher).doesNotContain("OPTIONAL MATCH");
        assertThat(countOccurrences(cypher, "MATCH (")).isEqualTo(3); // objNode + componentNode + modifiedNode

        // 2. All of the relationship CREATEs are combined into the SAME statement as those
        //    MATCHes, rather than being issued as independent queries - so there's no isolation
        //    between "the good reference" and "the bad one": one query, one all-or-nothing result.
        assertThat(countOccurrences(cypher, "CREATE (")).isEqualTo(2);
        assertThat(cypher).contains("hasComponent");
        assertThat(cypher).contains("modified");
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) != -1) {
            count++;
            index += token.length();
        }
        return count;
    }
}
