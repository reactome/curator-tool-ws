package org.reactome.curation.service;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.reactome.server.graph.domain.model.Event;
import org.reactome.server.graph.domain.model.PhysicalEntity;
import org.reactome.server.graph.domain.model.ReactionLikeEvent;
import org.reactome.server.graph.service.AdvancedDatabaseObjectService;
import org.reactome.server.graph.service.DatabaseObjectService;
import org.reactome.server.graph.service.SchemaService;
import org.reactome.server.tools.diagram.data.graph.Graph;
import org.reactome.server.tools.diagram.data.layout.Diagram;
import org.reactome.server.tools.diagram.exporter.raster.RasterExporter;
import org.reactome.server.tools.diagram.exporter.raster.api.RasterArgs;
import org.reactome.server.tools.diagram.exporter.raster.profiles.ColorProfiles;
import org.reactome.server.tools.reaction.exporter.ReactionExporter;
import org.reactome.server.tools.reaction.exporter.compartment.ReactomeCompartmentFactory;
import org.reactome.server.tools.reaction.exporter.graph.ReactionGraphFactory;
import org.reactome.server.tools.reaction.exporter.layout.LayoutFactory;
import org.reactome.server.tools.reaction.exporter.layout.model.Layout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Renders a reaction image using the reaction-exporter and diagram-exporter (RasterExporter) libraries.
 */
@Service
public class ReactionImageRendererService {

    private static final Logger logger = LoggerFactory.getLogger(ReactionImageRendererService.class);
    private static final int RASTER_QUALITY_MEDIUM = 10;
    private static final int RASTER_MARGIN_MEDIUM = 2;

    @Autowired
    private AdvancedDatabaseObjectService advancedDatabaseObjectService;
    @Autowired
    private DatabaseObjectService databaseObjectService;

    public byte[] renderReactionImage(Event event) {
        if (!(event instanceof ReactionLikeEvent)) {
            return null;
        }
        return renderReactionImage((ReactionLikeEvent) event);
    }

    public byte[] renderReactionImage(ReactionLikeEvent rle) {
        if (rle == null) {
            return null;
        }
        try {
            LayoutFactory layoutFactory = new LayoutFactory(advancedDatabaseObjectService, databaseObjectService);
            ReactionGraphFactory graphFactory = new ReactionGraphFactory(advancedDatabaseObjectService);
            ReactionExporter reactionExporter = new ReactionExporter(layoutFactory, graphFactory);
            ReactomeCompartmentFactory.setAdvancedDatabaseObjectService(advancedDatabaseObjectService);

            Layout layout = reactionExporter.getReactionLayout(rle);
            Diagram diagram = reactionExporter.getReactionDiagram(layout);
            Graph graph = reactionExporter.getReactionGraph(rle, layout);

            RasterArgs args = new RasterArgs("png");
            args.setProfiles(new ColorProfiles("Modern", "Standard", null));
            args.setQuality(RASTER_QUALITY_MEDIUM);
            args.setMargin(RASTER_MARGIN_MEDIUM);
            args.setWriteTitle(false);

            RasterExporter rasterExporter = new RasterExporter();
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                rasterExporter.export(diagram, graph, args, null, baos);
                return baos.toByteArray();
            }
        } catch (Exception e) {
            logger.warn("Failed to render reaction image for {}: {}", rle.getStId(), e.getMessage());
            return null;
        }
    }
}
