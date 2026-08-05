package org.reactome.curation.service;

import org.junit.jupiter.api.Test;
import org.reactome.server.graph.config.GraphCoreNeo4jConfig;
import org.reactome.server.graph.domain.model.Event;
import org.reactome.server.graph.service.AdvancedDatabaseObjectService;
import org.reactome.server.graph.service.DatabaseObjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms batchLoadEvents()'s INCOMING call (requesting "inferredTo") actually populates
 * Event.inferredFrom's raw field directly from the batch - not just appearing to work because
 * LazyFetchAspect transparently lazy-fetches it later. Reads the field via reflection (bypassing
 * the AOP-wrapped getter) right after batchLoadEvents() returns, so a regression back to
 * requesting "inferredFrom" (not a real relationship type) would show up as an empty field here
 * even though the exported docx would still look correct (masked by the AOP fallback).
 */
@SpringBootTest(
        classes = GraphCoreNeo4jConfig.class,
        properties = "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration"
)
class InferredFromBatchTest {
    private static final Logger logger = LoggerFactory.getLogger(InferredFromBatchTest.class);

    @Autowired
    private AdvancedDatabaseObjectService advancedDatabaseObjectService;
    @Autowired
    private DatabaseObjectService databaseObjectService;
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void batchPopulatesInferredFromDirectly() throws Exception {
        org.reactome.server.graph.utils.ReactomeGraphCore.setContext(applicationContext);

        EventDocxExportService exportService = new EventDocxExportService();
        setField(exportService, "advancedDatabaseObjectService", advancedDatabaseObjectService);
        setField(exportService, "databaseObjectService", databaseObjectService);

        Method batchLoadEvents = EventDocxExportService.class.getDeclaredMethod("batchLoadEvents", Set.class);
        batchLoadEvents.setAccessible(true);
        Map<Long, Event> result = (Map<Long, Event>) batchLoadEvents.invoke(exportService, Set.of(9615721L));

        Event event = result.get(9615721L);
        logger.info("Batch-loaded event: {}", event.getDisplayName());

        Field inferredFromField = org.reactome.server.graph.domain.model.Event.class.getDeclaredField("inferredFrom");
        inferredFromField.setAccessible(true);
        Set<Event> rawInferredFrom = (Set<Event>) inferredFromField.get(event);
        logger.info("Raw inferredFrom field (bypassing AOP getter): {}", rawInferredFrom);

        assertFalse(rawInferredFrom == null || rawInferredFrom.isEmpty(),
                "batchLoadEvents() should have populated inferredFrom directly via the INCOMING inferredTo request");
        assertEquals(1, rawInferredFrom.size());
        assertEquals(9615712L, rawInferredFrom.iterator().next().getDbId());
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
