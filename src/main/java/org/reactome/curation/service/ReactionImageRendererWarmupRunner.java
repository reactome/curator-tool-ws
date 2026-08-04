package org.reactome.curation.service;

import org.reactome.server.graph.domain.model.ReactionLikeEvent;
import org.reactome.server.graph.service.DatabaseObjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

/**
 * Renders one reaction image on a background thread right after the app finishes starting, so
 * the JIT has a chance to compile the reaction-rendering pipeline (layout/diagram/graph/raster)
 * before any real request needs it, and so reaction-exporter's compartment/GO tree cache (see
 * ReactionImageRendererService.renderReactionImagesInParallel()'s Javadoc) is populated ahead of
 * time instead of on the first user-facing render.
 *
 * Measured impact: the first reaction-image render (or first exportEventDocx call, which renders
 * many reactions in parallel) in a freshly-started JVM took ~17s for a pathway with 129
 * reactions; every subsequent call took ~2.7-2.9s. Without this warm-up, whichever real request
 * happens to be first pays that one-time cost.
 *
 * Picks an arbitrary real ReactionLikeEvent from the database rather than a hardcoded dbId, so
 * this works across environments (dev/server graphs don't necessarily share the same dbIds).
 * Runs on its own daemon thread so it never delays the app becoming ready to serve requests, and
 * any failure here is logged and otherwise ignored - this is a pure optimization, not something
 * request handling depends on.
 */
@Component
public class ReactionImageRendererWarmupRunner implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger logger = LoggerFactory.getLogger(ReactionImageRendererWarmupRunner.class);
    private static final String FIND_ANY_REACTION_QUERY = "MATCH (r:ReactionLikeEvent) RETURN r.dbId AS dbId LIMIT 1";

    @Autowired(required = false)
    private ReactionImageRendererService reactionImageRendererService;
    @Autowired(required = false)
    private DatabaseObjectService databaseObjectService;
    @Autowired(required = false)
    private Neo4jClient neo4jClient;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (reactionImageRendererService == null || databaseObjectService == null || neo4jClient == null) {
            return;
        }
        Thread warmupThread = new Thread(this::warmUp, "reaction-image-renderer-warmup");
        warmupThread.setDaemon(true);
        warmupThread.start();
    }

    private void warmUp() {
        long start = System.currentTimeMillis();
        try {
            Long dbId = neo4jClient.query(FIND_ANY_REACTION_QUERY)
                    .fetchAs(Long.class)
                    .mappedBy((typeSystem, record) -> record.get("dbId").asLong())
                    .one()
                    .orElse(null);
            if (dbId == null) {
                logger.info("Reaction image renderer warm-up skipped: no ReactionLikeEvent found in the database.");
                return;
            }
            ReactionLikeEvent rle = databaseObjectService.findById(dbId);
            byte[] image = reactionImageRendererService.renderReactionImage(rle);
            logger.info("Reaction image renderer warm-up completed for dbId={} in {}ms ({} bytes)",
                    dbId, System.currentTimeMillis() - start, image == null ? 0 : image.length);
        } catch (Exception e) {
            logger.warn("Reaction image renderer warm-up failed (non-fatal, first real render will just be slower): {}", e.getMessage());
        }
    }
}
