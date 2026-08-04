package org.reactome.curation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.reactome.server.graph.config.GraphCoreNeo4jConfig;
import org.reactome.server.graph.domain.model.PhysicalEntity;
import org.reactome.server.graph.domain.model.ReactionLikeEvent;
import org.reactome.server.graph.service.AdvancedDatabaseObjectService;
import org.reactome.server.graph.service.DatabaseObjectService;
import org.reactome.server.graph.utils.ReactomeGraphCore;
import org.reactome.server.tools.reaction.exporter.compartment.ReactomeCompartmentFactory;
import org.reactome.server.tools.reaction.exporter.layout.LayoutFactory;
import org.reactome.server.tools.reaction.exporter.layout.model.EntityGlyph;
import org.reactome.server.tools.reaction.exporter.layout.model.Layout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * One-time verification that ReactionImageRendererService.buildLayout() (batched participant
 * loading) produces the same participant compartment/attachment data as reaction-exporter's
 * original LayoutFactory.getReactionLikeEventLayout() (one findById() per participant, plus one
 * more per modified residue). Uses graph-core's own minimal Neo4j configuration directly - no JPA/
 * H2 involved - so it can run even while the full curator-tool-ws app (and its H2 file lock) is
 * already running.
 */
@SpringBootTest(
        classes = GraphCoreNeo4jConfig.class,
        properties = "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration"
)
class ReactionImageRendererVerificationTest {

    private static final Logger logger = LoggerFactory.getLogger(ReactionImageRendererVerificationTest.class);

    @Autowired
    private AdvancedDatabaseObjectService advancedDatabaseObjectService;
    @Autowired
    private DatabaseObjectService databaseObjectService;
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void compareOldAndNewLayoutForReactionWithModifications() throws Exception {
        ReactomeGraphCore.setContext(applicationContext);
        compare("R-HSA-167686");
    }

    @Test
    void compareOldAndNewLayoutForSimplerReaction() throws Exception {
        ReactomeGraphCore.setContext(applicationContext);
        compare("R-HSA-8851619");
    }

    private void compare(String reactionStId) throws Exception {
        ReactomeCompartmentFactory.setAdvancedDatabaseObjectService(advancedDatabaseObjectService);
        ReactionLikeEvent rle = databaseObjectService.findById(reactionStId);

        LayoutFactory layoutFactory = new LayoutFactory(advancedDatabaseObjectService, databaseObjectService);
        Layout oldLayout = layoutFactory.getReactionLikeEventLayout(rle, LayoutFactory.Style.BOX);

        ReactionImageRendererService service = new ReactionImageRendererService();
        setField(service, "advancedDatabaseObjectService", advancedDatabaseObjectService);
        setField(service, "databaseObjectService", databaseObjectService);
        Layout newLayout = invokeBuildLayout(service, rle);

        assertEquals(oldLayout.getPathway(), newLayout.getPathway(), "pathway stId should match");

        List<EntityGlyph> oldEntities = sortedByStId(oldLayout.getEntities());
        List<EntityGlyph> newEntities = sortedByStId(newLayout.getEntities());
        assertEquals(oldEntities.size(), newEntities.size(), "participant count should match");

        for (int i = 0; i < oldEntities.size(); i++) {
            EntityGlyph oldE = oldEntities.get(i);
            EntityGlyph newE = newEntities.get(i);
            String label = reactionStId + " / " + oldE.getStId();

            assertEquals(oldE.getStId(), newE.getStId(), label);
            assertEquals(oldE.getName(), newE.getName(), label + " name");
            assertEquals(oldE.getSchemaClass(), newE.getSchemaClass(), label + " schemaClass");
            assertEquals(oldE.isTrivial(), newE.isTrivial(), label + " trivial");
            assertEquals(oldE.isDisease(), newE.isDisease(), label + " disease");

            Set<String> oldCompartments = compartmentAccessions(oldE);
            Set<String> newCompartments = compartmentAccessions(newE);
            assertEquals(oldCompartments, newCompartments, label + " compartments");

            List<String> oldAttachments = oldE.getAttachments().stream().map(a -> a.getName()).sorted().collect(Collectors.toList());
            List<String> newAttachments = newE.getAttachments().stream().map(a -> a.getName()).sorted().collect(Collectors.toList());
            assertEquals(oldAttachments, newAttachments, label + " attachment (modification) labels");

            logger.info("Matched participant {}: compartments={}, attachments={}", label, oldCompartments, oldAttachments);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> compartmentAccessions(EntityGlyph glyph) throws Exception {
        // getCompartments() is package-private on EntityGlyph; reach it via the compartment glyph instead,
        // which is public and set from the same underlying PhysicalEntity.getCompartment() list.
        if (glyph.getCompartment() == null) return Set.of();
        return Set.of(glyph.getCompartment().getAccession());
    }

    private List<EntityGlyph> sortedByStId(Iterable<EntityGlyph> entities) {
        List<EntityGlyph> list = new java.util.ArrayList<>();
        entities.forEach(list::add);
        list.sort(Comparator.comparing(EntityGlyph::getStId));
        return list;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Layout invokeBuildLayout(ReactionImageRendererService service, ReactionLikeEvent rle) throws Exception {
        var method = ReactionImageRendererService.class.getDeclaredMethod("buildLayout", ReactionLikeEvent.class);
        method.setAccessible(true);
        return (Layout) method.invoke(service, rle);
    }
}
