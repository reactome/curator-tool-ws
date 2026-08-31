package org.reactome.curation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.DiagramGKBReader;
import org.gk.persistence.MySQLAdaptor;
import org.gk.render.RenderablePathway;
import org.reactome.curation.repository.CurationRepository;
import org.reactome.curation.service.PathwayDiagramService;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.PathwayDiagram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.neo4j.core.Neo4jClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One-time backfill of PathwayDiagram.renderedInstance for all existing diagrams.
 *
 * For a diagram that has been edited through this tool, content comes from its saved Cytoscape editor
 * JSON (diagram_cytoscape_dir/&lt;pathwayDiagramDbId&gt;.json - the curator-tool's own editing format,
 * not the pathway-browser layout JSON in diagram_graph_dir), via
 * PathwayDiagramService.computeRenderedInstanceDbIds(Long, JsonNode) - the exact same source and
 * classification logic the live diagram save path (CurationController.saveCyNetwork) uses.
 *
 * For a diagram that has never been edited through this tool (no Cytoscape JSON file), content is
 * instead pulled from the relational database's storedATXML attribute (the legacy diagram XML format),
 * parsed via org.gk.persistence.DiagramGKBReader into the same RenderablePathway shape and run through
 * PathwayDiagramService.computeRenderedInstanceDbIds(RenderablePathway) - see
 * org.gk.slicing.GraphDBSliceToRelTool#extractPathwayDiagrams() in the slicing-tool project for the
 * precedent this mirrors.
 *
 * Must NOT be run while the interactive dev/prod server is already running: booting this runner starts
 * the full CuratorToolWsApplication context, including the H2-backed user store, which only allows one
 * process to hold the H2 file at a time.
 *
 * Usage: PathwayDiagramRenderedInstanceBackfillRunner [--dry-run] [--batch-size=N]
 *                                                      [--mysql-database=NAME --mysql-user=USER --mysql-password=PASSWORD]
 *                                                      [--mysql-host=HOST] [--mysql-port=PORT]
 *   --dry-run              Log per-diagram classification counts; write nothing to Neo4j.
 *   --batch-size=N         Diagrams per progress-logging batch (default 500).
 *   --mysql-database=NAME  Relational database to fall back to for diagrams with no Cytoscape JSON file.
 *                          If omitted (along with --mysql-user/--mysql-password), such diagrams are just
 *                          skipped, same as before this fallback was added.
 *   --mysql-user=USER      Relational database user.
 *   --mysql-password=PWD   Relational database password.
 *   --mysql-host=HOST      Relational database host (default localhost).
 *   --mysql-port=PORT      Relational database port (default 3306).
 */
public class PathwayDiagramRenderedInstanceBackfillRunner {

    private static final Logger logger = LoggerFactory.getLogger(PathwayDiagramRenderedInstanceBackfillRunner.class);
    private static final int DEFAULT_BATCH_SIZE = 500;
    private static final int LOG_EVERY_N_BATCHES = 10;
    private static final String DEFAULT_MYSQL_HOST = "localhost";
    private static final int DEFAULT_MYSQL_PORT = 3306;

    public static void main(String[] args) {
        boolean dryRun = false;
        int batchSize = DEFAULT_BATCH_SIZE;
        String mysqlHost = DEFAULT_MYSQL_HOST;
        int mysqlPort = DEFAULT_MYSQL_PORT;
        String mysqlDatabase = null;
        String mysqlUser = null;
        String mysqlPassword = null;
        for (String arg : args) {
            if (arg.equals("--dry-run"))
                dryRun = true;
            else if (arg.startsWith("--batch-size="))
                batchSize = Integer.parseInt(arg.substring("--batch-size=".length()));
            else if (arg.startsWith("--mysql-host="))
                mysqlHost = arg.substring("--mysql-host=".length());
            else if (arg.startsWith("--mysql-port="))
                mysqlPort = Integer.parseInt(arg.substring("--mysql-port=".length()));
            else if (arg.startsWith("--mysql-database="))
                mysqlDatabase = arg.substring("--mysql-database=".length());
            else if (arg.startsWith("--mysql-user="))
                mysqlUser = arg.substring("--mysql-user=".length());
            else if (arg.startsWith("--mysql-password="))
                mysqlPassword = arg.substring("--mysql-password=".length());
        }

        int exitCode = 0;
        MySQLAdaptor mysqlAdaptor = null;
        try {
            if (mysqlDatabase != null && mysqlUser != null && mysqlPassword != null) {
                mysqlAdaptor = new MySQLAdaptor(mysqlHost, mysqlDatabase, mysqlUser, mysqlPassword, mysqlPort);
                logger.info("Connected to relational database {}@{}:{} for the no-Cytoscape-file fallback.",
                        mysqlDatabase, mysqlHost, mysqlPort);
            }
            else {
                logger.info("No --mysql-database/--mysql-user/--mysql-password given: diagrams with no " +
                        "Cytoscape JSON file will be skipped rather than backfilled from the relational database.");
            }
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(CuratorToolWsApplication.class)
                    .web(WebApplicationType.NONE)
                    .run(args)) {
                Neo4jClient neo4jClient = context.getBean(Neo4jClient.class);
                CurationRepository curationRepository = context.getBean(CurationRepository.class);
                PathwayDiagramService diagramService = context.getBean(PathwayDiagramService.class);
                run(neo4jClient, curationRepository, diagramService, mysqlAdaptor, dryRun, batchSize);
            }
        }
        catch (Exception e) {
            System.err.println("Backfill failed: " + e.getMessage());
            e.printStackTrace(System.err);
            exitCode = 1;
        }
        if (exitCode != 0)
            System.exit(exitCode);
    }

    private static void run(Neo4jClient neo4jClient,
                             CurationRepository curationRepository,
                             PathwayDiagramService diagramService,
                             MySQLAdaptor mysqlAdaptor,
                             boolean dryRun,
                             int batchSize) {
        long start = System.currentTimeMillis();
        List<Long> allDbIds = neo4jClient.query("MATCH (pd:PathwayDiagram) RETURN pd.dbId AS dbId")
                .fetchAs(Long.class)
                .mappedBy((typeSystem, record) -> record.get("dbId").asLong())
                .all()
                .stream()
                .collect(Collectors.toList());

        logger.info("Found {} PathwayDiagram instances to process (dryRun={}, batchSize={}, mysqlFallback={}).",
                allDbIds.size(), dryRun, batchSize, mysqlAdaptor != null);

        DiagramGKBReader diagramReader = new DiagramGKBReader();
        int processedFromCytoscape = 0;
        int processedFromMySQL = 0;
        int skipped = 0;
        int batchCount = 0;
        for (int i = 0; i < allDbIds.size(); i += batchSize) {
            List<Long> batch = allDbIds.subList(i, Math.min(i + batchSize, allDbIds.size()));
            for (Long dbId : batch) {
                try {
                    Set<Long> renderedInstanceDbIds;
                    boolean fromMySQL = false;
                    if (diagramService.hasCyNetwork(dbId)) {
                        JsonNode cytoscapeJSON = diagramService.loadCyNetwork(dbId);
                        renderedInstanceDbIds = diagramService.computeRenderedInstanceDbIds(dbId, cytoscapeJSON);
                    }
                    else if (mysqlAdaptor != null) {
                        renderedInstanceDbIds = computeFromMySQL(dbId, mysqlAdaptor, diagramReader, diagramService);
                        fromMySQL = true;
                        if (renderedInstanceDbIds == null) {
                            logger.warn("Skipping PathwayDiagram dbId={}: no Cytoscape JSON file and no " +
                                    "storedATXML found in the relational database.", dbId);
                            skipped++;
                            continue;
                        }
                    }
                    else {
                        logger.warn("Skipping PathwayDiagram dbId={}: no Cytoscape JSON file found.", dbId);
                        skipped++;
                        continue;
                    }

                    if (dryRun) {
                        logger.info("[dry-run] PathwayDiagram dbId={}: {} rendered instance(s) (source={}).",
                                dbId, renderedInstanceDbIds.size(), fromMySQL ? "MySQL" : "Cytoscape");
                    }
                    else {
                        DatabaseObject pathwayDiagram = new PathwayDiagram();
                        pathwayDiagram.setDbId(dbId);
                        curationRepository.replaceRenderedInstance(pathwayDiagram, new ArrayList<>(renderedInstanceDbIds));
                    }
                    if (fromMySQL)
                        processedFromMySQL++;
                    else
                        processedFromCytoscape++;
                }
                catch (Exception e) {
                    logger.warn("Skipping PathwayDiagram dbId={}: {}", dbId, e.getMessage());
                    skipped++;
                }
            }
            batchCount++;
            if (batchCount % LOG_EVERY_N_BATCHES == 0) {
                logger.info("Progress: {} from Cytoscape, {} from MySQL, {} skipped, {} of {} total, elapsed {}ms.",
                        processedFromCytoscape, processedFromMySQL, skipped,
                        Math.min((long) batchCount * batchSize, allDbIds.size()),
                        allDbIds.size(), System.currentTimeMillis() - start);
            }
        }

        logger.info("Backfill complete: {} from Cytoscape, {} from MySQL, {} skipped, {} total, elapsed {}ms.",
                processedFromCytoscape, processedFromMySQL, skipped, allDbIds.size(), System.currentTimeMillis() - start);
    }

    /**
     * Falls back to the relational database's legacy storedATXML diagram format for a PathwayDiagram
     * that has never been edited through this tool. Returns null (caller should skip and log) if the
     * instance can't be found or has no storedATXML content.
     */
    private static Set<Long> computeFromMySQL(Long dbId,
                                               MySQLAdaptor mysqlAdaptor,
                                               DiagramGKBReader diagramReader,
                                               PathwayDiagramService diagramService) throws Exception {
        GKInstance sourceInst = mysqlAdaptor.fetchInstance(dbId);
        if (sourceInst == null)
            return null;
        Object xml = sourceInst.getAttributeValue(ReactomeJavaConstants.storedATXML);
        if (xml == null || xml.toString().isEmpty())
            return null;
        RenderablePathway diagram = diagramReader.openDiagram(xml.toString());
        return diagramService.computeRenderedInstanceDbIds(diagram);
    }
}
