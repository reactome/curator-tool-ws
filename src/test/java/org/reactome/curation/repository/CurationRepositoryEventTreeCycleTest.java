package org.reactome.curation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.reactome.curation.model.EventTreeCycle;
import org.reactome.curation.model.SimpleInstance;
import org.springframework.data.neo4j.core.Neo4jClient;

/**
 * Unit tests for CurationRepository.populateChildren() - the recursion behind getEventTree(),
 * which builds the whole pathway hierarchy in one pass.
 *
 * A circular hasEvent relationship used to make that recursion run until the stack overflowed, so
 * getEventTree() failed for every user and the curator tool had no event tree at all - including no
 * way to find and remove the relationship that broke it. One was committed on 2026-09-10 (an event
 * added under a pathway it already contained), which is what prompted these tests.
 *
 * The relationship that closes a cycle is dropped so the rest of the hierarchy still loads, and
 * *reported* rather than only logged: the curator tool no longer refuses the edits that could
 * create a cycle, so the event view is where a curator finds out, and it can only tell them if
 * these are returned. Hence the assertions on the reported path as well as on the tree.
 *
 * No database: parentDbId2DbId2SimpleInstance stands in for whatever getAllEvents() would have
 * returned. neo4jClient is only mocked deeply enough for the constructor (its startup
 * index-creation and max-dbId lookup); neo4jTemplate is passed as null since the constructor only
 * stores it. Same setup as CurationRepositoryFindInvalidReferenceTest.
 */
public class CurationRepositoryEventTreeCycleTest {

    private CurationRepository repository;

    @BeforeEach
    void setUp() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).fetchAs(Long.class).one()).thenReturn(Optional.of(1L));
        repository = new CurationRepository(neo4jClient, null, new CypherQueryUtilities());
    }

    private SimpleInstance event(long dbId, String displayName) {
        SimpleInstance inst = new SimpleInstance();
        inst.setDbId(dbId);
        inst.setDisplayName(displayName);
        inst.setSchemaClassName("Pathway");
        // populateChildren() sorts children on it, so every event needs one.
        inst.setAttribute("order", 1);
        return inst;
    }

    /** The child events of each event, keyed by parent dbId, as getAllEvents() returns them. */
    private Map<Long, Map<Long, SimpleInstance>> hierarchy(Object... parentThenChildren) {
        Map<Long, Map<Long, SimpleInstance>> hierarchy = new HashMap<>();
        for (int i = 0; i < parentThenChildren.length; i += 2) {
            Long parentDbId = (Long) parentThenChildren[i];
            Map<Long, SimpleInstance> children = new HashMap<>();
            for (SimpleInstance child : (List<SimpleInstance>) parentThenChildren[i + 1])
                children.put(child.getDbId(), child);
            hierarchy.put(parentDbId, children);
        }
        return hierarchy;
    }

    @SuppressWarnings("unchecked")
    private List<SimpleInstance> childrenOf(SimpleInstance inst) {
        return (List<SimpleInstance>) inst.getAttribute("hasEvent");
    }

    /** The reported cycle as "Name [dbId] > Name [dbId]", the way the event view renders it. */
    private String pathOf(EventTreeCycle cycle) {
        return cycle.getPath().stream()
                .map(event -> event.getDisplayName() + " [" + event.getDbId() + "]")
                .collect(Collectors.joining(" > "));
    }

    @Test
    @Timeout(10)
    void shouldDropTheRelationshipThatClosesACycleRatherThanRecursingForever() {
        // Metabolism [10] contains Glycolysis [20], and Glycolysis has been given Metabolism back.
        SimpleInstance metabolism = event(10L, "Metabolism");
        SimpleInstance glycolysis = event(20L, "Glycolysis");
        Map<Long, Map<Long, SimpleInstance>> hierarchy = hierarchy(
                10L, List.of(glycolysis),
                20L, List.of(metabolism));

        List<EventTreeCycle> cycles = repository.populateChildren(metabolism, hierarchy);

        // The tree still holds everything except the relationship that closed the cycle, so the
        // curator can navigate to Glycolysis and remove it.
        assertThat(childrenOf(metabolism)).extracting(SimpleInstance::getDbId).containsExactly(20L);
        assertThat(childrenOf(childrenOf(metabolism).get(0))).isEmpty();
        // Reported as the containment the curator has to break: Glycolysis is what holds
        // Metabolism, and Metabolism is what already contains Glycolysis.
        assertThat(cycles).hasSize(1);
        assertThat(pathOf(cycles.get(0))).isEqualTo("Metabolism [10] > Glycolysis [20]");
    }

    @Test
    @Timeout(10)
    void shouldDropAnEventListedInsideItself() {
        SimpleInstance metabolism = event(10L, "Metabolism");
        Map<Long, Map<Long, SimpleInstance>> hierarchy = hierarchy(10L, List.of(metabolism));

        List<EventTreeCycle> cycles = repository.populateChildren(metabolism, hierarchy);

        assertThat(childrenOf(metabolism)).isEmpty();
        assertThat(cycles).hasSize(1);
        assertThat(pathOf(cycles.get(0))).isEqualTo("Metabolism [10]");
    }

    @Test
    @Timeout(10)
    void shouldReportTheCycleWithoutTheBranchThatLedDownToIt() {
        // The recursion reaches the cycle through Disease [1], but Disease is not part of it and
        // naming it would only make the message harder to act on.
        SimpleInstance disease = event(1L, "Disease");
        SimpleInstance signaling = event(10L, "Signaling");
        SimpleInstance mapk = event(20L, "MAPK cascade");
        Map<Long, Map<Long, SimpleInstance>> hierarchy = hierarchy(
                1L, List.of(signaling),
                10L, List.of(mapk),
                20L, List.of(signaling));

        List<EventTreeCycle> cycles = repository.populateChildren(disease, hierarchy);

        assertThat(cycles).hasSize(1);
        assertThat(pathOf(cycles.get(0)))
                .isEqualTo("Signaling [10] > MAPK cascade [20]");
    }

    @Test
    @Timeout(10)
    void shouldReportOneCyclePerRelationshipHoweverManyRoutesReachIt() {
        // Both top-level branches lead down to the same cyclic relationship. It is one thing for
        // the curator to fix, so it must not be reported twice.
        SimpleInstance root = event(1L, "TopLevelPathway");
        SimpleInstance branchA = event(10L, "Branch A");
        SimpleInstance branchB = event(20L, "Branch B");
        SimpleInstance signaling = event(30L, "Signaling");
        SimpleInstance mapk = event(40L, "MAPK cascade");
        Map<Long, Map<Long, SimpleInstance>> hierarchy = hierarchy(
                1L, List.of(branchA, branchB),
                10L, List.of(signaling),
                20L, List.of(signaling),
                30L, List.of(mapk),
                40L, List.of(signaling));

        List<EventTreeCycle> cycles = repository.populateChildren(root, hierarchy);

        assertThat(cycles).hasSize(1);
        assertThat(pathOf(cycles.get(0))).isEqualTo("Signaling [30] > MAPK cascade [40]");
    }

    @Test
    @Timeout(10)
    void shouldStillPopulateAnEventThatSitsUnderSeveralParents() {
        // Cell Cycle Checkpoints is listed in two branches; that is not a cycle, and both
        // occurrences must be populated - which is why the guard tracks the current path rather
        // than every event it has seen.
        SimpleInstance root = event(1L, "TopLevelPathway");
        SimpleInstance mitotic = event(10L, "Mitotic Cell Cycle");
        SimpleInstance meiotic = event(20L, "Meiotic Cell Cycle");
        SimpleInstance checkpoints = event(30L, "Cell Cycle Checkpoints");
        SimpleInstance g2Checkpoint = event(40L, "G2/M Checkpoints");
        Map<Long, Map<Long, SimpleInstance>> hierarchy = hierarchy(
                1L, List.of(mitotic, meiotic),
                10L, List.of(checkpoints),
                20L, List.of(checkpoints),
                30L, List.of(g2Checkpoint));

        List<EventTreeCycle> cycles = repository.populateChildren(root, hierarchy);

        for (SimpleInstance branch : childrenOf(root)) {
            assertThat(childrenOf(branch)).extracting(SimpleInstance::getDbId).containsExactly(30L);
            assertThat(childrenOf(childrenOf(branch).get(0)))
                    .extracting(SimpleInstance::getDbId).containsExactly(40L);
        }
        // A DAG is not a cycle, so there is nothing to tell the curator about.
        assertThat(cycles).isEmpty();
    }
}
