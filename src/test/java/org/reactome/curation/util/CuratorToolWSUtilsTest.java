package org.reactome.curation.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.reactome.server.graph.domain.model.Complex;
import org.reactome.server.graph.domain.model.PhysicalEntity;
import org.reactome.server.graph.domain.model.Polymer;
import org.reactome.server.graph.domain.model.Reaction;
import org.reactome.server.graph.domain.model.SimpleEntity;
import org.reactome.server.graph.domain.relationship.HasComponent;
import org.reactome.server.graph.domain.relationship.Input;
import org.reactome.server.graph.domain.relationship.RepeatedUnit;
import org.reactome.server.graph.service.helper.StoichiometryObject;

/**
 * ReactionLikeEvent.input/output, Complex.hasComponent, and Polymer.repeatedUnit are all backed
 * by a Set of relationship entities, whose iteration order is not guaranteed to match the
 * curator-defined order recorded in each relationship's "order" property (see Has.getOrder()).
 * These tests build the Set out of curator order on purpose, to prove CuratorToolWSUtils sorts
 * by "order" rather than trusting Set iteration order.
 */
public class CuratorToolWSUtilsTest {

    private static Input newInput(PhysicalEntity entity, int order) {
        Input input = new Input();
        input.setElement(entity);
        input.setOrder(order);
        return input;
    }

    private static HasComponent newHasComponent(PhysicalEntity entity, int order) {
        HasComponent hasComponent = new HasComponent();
        hasComponent.setElement(entity);
        hasComponent.setOrder(order);
        return hasComponent;
    }

    private static RepeatedUnit newRepeatedUnit(PhysicalEntity entity, int order) {
        RepeatedUnit repeatedUnit = new RepeatedUnit();
        repeatedUnit.setElement(entity);
        repeatedUnit.setOrder(order);
        return repeatedUnit;
    }

    private static SimpleEntity newEntity(long dbId, String displayName) {
        SimpleEntity entity = new SimpleEntity();
        entity.setDbId(dbId);
        entity.setDisplayName(displayName);
        return entity;
    }

    @Test
    void getOrderedPhysicalEntitiesShouldSortByCuratorOrderNotSetIterationOrder() {
        SimpleEntity first = newEntity(1L, "ATP");
        SimpleEntity second = newEntity(2L, "ADP");
        SimpleEntity third = newEntity(3L, "Pi");

        // Insertion order deliberately does NOT match the "order" property below, simulating
        // Spring Data Neo4j loading the relationship Set in an arbitrary order.
        Set<Input> inputs = Set.of(newInput(third, 2), newInput(first, 0), newInput(second, 1));

        List<PhysicalEntity> ordered = CuratorToolWSUtils.getOrderedPhysicalEntities(inputs);

        assertThat(ordered).extracting(PhysicalEntity::getDisplayName)
                .containsExactly("ATP", "ADP", "Pi");
    }

    @Test
    void getOrderedPhysicalEntitiesShouldExpandStoichiometry() {
        SimpleEntity entity = newEntity(1L, "ATP");
        Input input = newInput(entity, 0);
        input.setStoichiometry(3);

        List<PhysicalEntity> ordered = CuratorToolWSUtils.getOrderedPhysicalEntities(Set.of(input));

        assertThat(ordered).hasSize(3);
        assertThat(ordered).allMatch(pe -> pe.getDisplayName().equals("ATP"));
    }

    @Test
    void getOrderedPhysicalEntitiesShouldReturnNullForNullInput() {
        assertThat(CuratorToolWSUtils.getOrderedPhysicalEntities(null)).isNull();
    }

    @Test
    void getAllFieldsShouldPreserveCuratorOrderForReactionInput() {
        SimpleEntity first = newEntity(1L, "ATP");
        SimpleEntity second = newEntity(2L, "ADP");

        Reaction reaction = new Reaction(100L);
        reaction.setDisplayName("Test reaction");
        // Deliberately out of curator order (order 0/1) in the Set itself, mirroring how
        // DatabaseObjectUtils.getAllFields()'s fetchInput() would otherwise re-sort by
        // displayName instead of by "order".
        reaction.setInputs(Set.of(newInput(second, 0), newInput(first, 1)));

        Map<String, Object> field2value = CuratorToolWSUtils.getAllFields(reaction, false);

        @SuppressWarnings("unchecked")
        List<StoichiometryObject> orderedInput = (List<StoichiometryObject>) field2value.get("input");
        assertThat(orderedInput).extracting(so -> so.<PhysicalEntity>getObject().getDisplayName())
                .containsExactly("ADP", "ATP");
    }

    @Test
    void getOrderedPhysicalEntitiesShouldSortComplexHasComponentByOrderNotDisplayName() {
        // Alphabetically "Alpha" < "Zeta", but curator order says Zeta comes first (order 0).
        SimpleEntity zeta = newEntity(1L, "Zeta");
        SimpleEntity alpha = newEntity(2L, "Alpha");

        Set<HasComponent> components = Set.of(newHasComponent(alpha, 1), newHasComponent(zeta, 0));

        List<PhysicalEntity> ordered = CuratorToolWSUtils.getOrderedPhysicalEntities(components);

        assertThat(ordered).extracting(PhysicalEntity::getDisplayName)
                .containsExactly("Zeta", "Alpha");
    }

    @Test
    void getOrderedPhysicalEntitiesShouldSortPolymerRepeatedUnitByOrderNotDisplayName() {
        SimpleEntity zeta = newEntity(1L, "Zeta");
        SimpleEntity alpha = newEntity(2L, "Alpha");

        Set<RepeatedUnit> repeatedUnits = Set.of(newRepeatedUnit(alpha, 1), newRepeatedUnit(zeta, 0));

        List<PhysicalEntity> ordered = CuratorToolWSUtils.getOrderedPhysicalEntities(repeatedUnits);

        assertThat(ordered).extracting(PhysicalEntity::getDisplayName)
                .containsExactly("Zeta", "Alpha");
    }

    @Test
    void getAllFieldsShouldPreserveCuratorOrderForComplexHasComponent() {
        SimpleEntity zeta = newEntity(1L, "Zeta");
        SimpleEntity alpha = newEntity(2L, "Alpha");

        Complex complex = new Complex(200L);
        complex.setDisplayName("Test complex");
        // Curator order (Zeta, then Alpha) is the reverse of displayName order - if getAllFields()
        // fell back to fetchHasComponent()'s displayName sort, this would come back as Alpha, Zeta.
        complex.setHasComponentNested(List.of(newHasComponent(alpha, 1), newHasComponent(zeta, 0)));

        Map<String, Object> field2value = CuratorToolWSUtils.getAllFields(complex, false);

        @SuppressWarnings("unchecked")
        List<StoichiometryObject> orderedComponents = (List<StoichiometryObject>) field2value.get("hasComponent");
        assertThat(orderedComponents).extracting(so -> so.<PhysicalEntity>getObject().getDisplayName())
                .containsExactly("Zeta", "Alpha");
    }

    @Test
    void getAllFieldsShouldPreserveCuratorOrderForPolymerRepeatedUnit() {
        SimpleEntity zeta = newEntity(1L, "Zeta");
        SimpleEntity alpha = newEntity(2L, "Alpha");

        Polymer polymer = new Polymer(300L);
        polymer.setDisplayName("Test polymer");
        polymer.setRepeatedUnits(new TreeSet<>(List.of(newRepeatedUnit(alpha, 1), newRepeatedUnit(zeta, 0))));

        Map<String, Object> field2value = CuratorToolWSUtils.getAllFields(polymer, false);

        @SuppressWarnings("unchecked")
        List<StoichiometryObject> orderedRepeatedUnits = (List<StoichiometryObject>) field2value.get("repeatedUnit");
        assertThat(orderedRepeatedUnits).extracting(so -> so.<PhysicalEntity>getObject().getDisplayName())
                .containsExactly("Zeta", "Alpha");
    }
}
