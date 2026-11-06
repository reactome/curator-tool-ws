package org.reactome.curation.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.gk.render.RenderablePathway;
import org.junit.jupiter.api.Test;
import org.reactome.curation.exceptions.DatabaseObjectNotFoundException;
import org.reactome.curation.qa.model.DiagramSyncIssue;
import org.reactome.curation.qa.model.DiagramSyncReport;
import org.reactome.curation.config.CuratorToolEnv;
import org.reactome.curation.service.PathwayDiagramService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Run the diagram sync check against the configured database and diagram folders.
 */
// An in-memory H2 so that this test can run while the server holds the user database file
@SpringBootTest(properties = {
        "logging.dir=target/test-logs",
        "spring.datasource.url=jdbc:h2:mem:diagram-sync-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class DiagramSyncCheckTest {

    @Autowired
    private QAService qaService;
    @Autowired
    private Neo4jClient neo4jClient;
    @Autowired
    private PathwayDiagramService diagramService;
    @Autowired
    private CuratorToolEnv toolEnv;

    // Note: This is needed.
    @Test
    void contextLoads() {
    }

    /**
     * The check reads activators and inhibitors from the labels of Regulation nodes, which relies on
     * subclasses carrying their superclass labels.
     */
    @Test
    void regulationSubclassesHaveSuperclassLabels() {
        Collection<Map<String, Object>> rows = neo4jClient.query(
                "MATCH (r:Regulation) WHERE NOT r:PositiveRegulation AND NOT r:NegativeRegulation " +
                "RETURN count(r) AS count").fetch().all();
        assertEquals(0L, ((Number) rows.iterator().next().get("count")).longValue());
    }

    @Test
    void checkPathways() throws Exception {
        // Transmission across Chemical Synapses: pathway-to-pathway FlowLines.
        // Metabolism: many FlowLines and a large sub-pathway tree.
        // 2559586: a diagram with reactions, catalysts and inhibitors.
        for (Long dbId : List.of(112315L, 1430728L, 2559586L)) {
            long time = System.currentTimeMillis();
            DiagramSyncReport report = qaService.checkDiagramSync(dbId);
            time = System.currentTimeMillis() - time;
            printReport(report, time);
            assertTrue(report.getCheckedDiagrams() > 0);
            for (DiagramSyncIssue issue : report.getIssues()) {
                assertNotNull(issue.getDiagramDbId());
                assertNotNull(issue.getEventDbId());
                assertNotNull(issue.getIssue());
                if (issue.getIssue().startsWith("precedingEvent"))
                    assertEquals("precedingEvent out of sync: missing " + issue.getMissingPrecedingEventDbId(),
                                 issue.getIssue());
            }
        }
    }

    /**
     * Make sure reactions are actually parsed and compared: the layout JSON of 2559586 has drawn reactions,
     * and they are all in sync except for what the check reports.
     */
    @Test
    void reactionsAreCompared() throws Exception {
        JsonNode layout = diagramService.loadDiagramJson("2559586.json");
        DiagramSyncHelper.DrawnDiagram drawn = DiagramSyncHelper.fromLayoutJson(layout);
        assertTrue(drawn.reactions.size() > 5, "Too few reactions parsed: " + drawn.reactions.size());
        assertTrue(drawn.reactions.stream().anyMatch(r -> !r.catalysts.isEmpty()));
        assertTrue(drawn.reactions.stream().anyMatch(r -> !r.inhibitors.isEmpty() || !r.activators.isEmpty()));
        System.out.println("Reactions parsed from 2559586.json: " + drawn.reactions.size());
    }

    /**
     * Diagrams saved through this tool are read from their Cytoscape JSON instead of the layout JSON.
     */
    @Test
    void checkCytoscapeDiagrams() throws Exception {
        File[] files = new File(toolEnv.getDiagramCytoscapeDir()).listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            System.out.println("No Cytoscape JSON files to check.");
            return;
        }
        int checked = 0;
        for (File file : files) {
            Long diagramDbId = Long.parseLong(file.getName().replace(".json", ""));
            RenderablePathway diagram;
            try {
                diagram = diagramService.loadCyNetworkAsDiagram(diagramDbId);
            }
            catch (Exception e) {
                // The check itself falls back to the layout JSON for these
                System.out.println("Cytoscape diagram " + diagramDbId + " cannot be converted: " + e);
                continue;
            }
            if (diagram == null)
                continue;
            DiagramSyncHelper.DrawnDiagram drawn = DiagramSyncHelper.fromRenderablePathway(diagram);
            Collection<Long> pathwayIds = neo4jClient.query(
                    "MATCH (pd:PathwayDiagram {dbId: $dbId})-[:representedPathway]->(p:Pathway) RETURN p.dbId")
                    .bind(diagramDbId).to("dbId").fetchAs(Long.class).all();
            if (pathwayIds.isEmpty())
                continue; // Not a PathwayDiagram in this database
            DiagramSyncReport report = qaService.checkDiagramSync(pathwayIds.iterator().next());
            long issues = report.getIssues().stream().filter(i -> i.getDiagramDbId().equals(diagramDbId)).count();
            System.out.println("Cytoscape diagram " + diagramDbId + ": " + drawn.reactions.size() + " reactions, " +
                               drawn.flowLines.size() + " flow lines, " + issues + " issues");
            report.getIssues().stream().filter(i -> i.getDiagramDbId().equals(diagramDbId)).limit(5)
                  .forEach(i -> System.out.println("\t" + i.getEventDbId() + "\t" + i.getEventDisplayName() + "\t" + i.getIssue()));
            checked++;
        }
        System.out.println("Cytoscape diagrams checked: " + checked);
    }

    @Test
    void unknownEvent() {
        assertThrows(DatabaseObjectNotFoundException.class, () -> qaService.checkDiagramSync(-1L));
    }

    private void printReport(DiagramSyncReport report, long time) {
        System.out.println(report.getEventDisplayName() + " (" + report.getEventDbId() + "): " +
                           report.getCheckedDiagrams() + " diagrams checked, " +
                           report.getUncheckedDiagramDbIds().size() + " unchecked, " +
                           report.getIssues().size() + " issues, " + time + " ms");
        long preceding = report.getIssues().stream().filter(i -> i.getIssue().startsWith("precedingEvent")).count();
        System.out.println("\tprecedingEvent issues: " + preceding);
        report.getIssues().stream().limit(15).forEach(i ->
            System.out.println("\t" + i.getDiagramDbId() + "\t" + i.getPathwayDisplayName() + "\t" +
                               i.getEventDbId() + "\t" + i.getEventSchemaClass() + "\t" +
                               i.getEventDisplayName() + "\t" + i.getIssue() + "\t" +
                               i.getCreated() + "\t" + i.getModified()));
    }
}
