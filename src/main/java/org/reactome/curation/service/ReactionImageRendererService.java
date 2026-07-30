package org.reactome.curation.service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.reactome.server.graph.domain.model.AbstractModifiedResidue;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.EntityWithAccessionedSequence;
import org.reactome.server.graph.domain.model.Event;
import org.reactome.server.graph.domain.model.PhysicalEntity;
import org.reactome.server.graph.domain.model.ReactionLikeEvent;
import org.reactome.server.graph.domain.model.TranslationalModification;
import org.reactome.server.graph.exception.CustomQueryException;
import org.reactome.server.graph.service.AdvancedDatabaseObjectService;
import org.reactome.server.graph.service.DatabaseObjectService;
import org.reactome.server.graph.service.helper.RelationshipDirection;
import org.reactome.server.tools.diagram.data.graph.Graph;
import org.reactome.server.tools.diagram.data.layout.Diagram;
import org.reactome.server.tools.diagram.exporter.raster.RasterExporter;
import org.reactome.server.tools.diagram.exporter.raster.api.RasterArgs;
import org.reactome.server.tools.diagram.exporter.raster.profiles.ColorProfiles;
import org.reactome.server.tools.reaction.exporter.compartment.ReactomeCompartmentFactory;
import org.reactome.server.tools.reaction.exporter.diagram.ReactionDiagramFactory;
import org.reactome.server.tools.reaction.exporter.graph.ReactionGraphFactory;
import org.reactome.server.tools.reaction.exporter.layout.algorithm.box.BoxAlgorithm;
import org.reactome.server.tools.reaction.exporter.layout.model.EntityGlyph;
import org.reactome.server.tools.reaction.exporter.layout.model.Layout;
import org.reactome.server.tools.reaction.exporter.layout.model.Role;
import org.reactome.server.tools.reaction.exporter.layout.result.LayoutParticipants;
import org.reactome.server.tools.reaction.exporter.layout.result.LayoutResult;
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

    /**
     * Copied verbatim from reaction-exporter's LayoutFactory (private there, so it can't be reused
     * directly) - see buildLayout() below for why this class re-implements the participant-loading
     * half of getReactionLikeEventLayout() instead of calling it.
     */
    //language=Cypher
    private static final String LAYOUT_QUERY = "" +
            "MATCH (rle:ReactionLikeEvent{stId:$stId}) " +
            "OPTIONAL MATCH (rle)-[:normalReaction]->(nr:ReactionLikeEvent) " +
            "WHERE (rle:FailedReaction)" +

            "OPTIONAL MATCH (rle)-[i:input]->(pe:PhysicalEntity) " +
            "WHERE NOT (rle:FailedReaction) OR NOT exists((rle)-[:entityFunctionalStatus]->()-[:diseaseEntity|normalEntity]->(pe)) " +
            "OPTIONAL MATCH (pe)-[:hasComponent|hasMember|hasCandidate|proteinMarker|RNAMarker*]->(d:Drug) " +
            "WITH rle, nr, collect(DISTINCT CASE WHEN pe IS NULL THEN null ELSE {physicalEntity: pe.stId, role:{n: i.stoichiometry, type: 'input'}, drug: (pe:Drug) OR NOT d IS NULL} END) AS ps " +

            "OPTIONAL MATCH (nr)-[i:input]->(pe:PhysicalEntity) " +
            "WHERE NOT exists((rle)-[:input]->(pe)) " +
            "OPTIONAL MATCH (pe)-[:hasComponent|hasMember|hasCandidate|proteinMarker|RNAMarker*]->(d:Drug) " +
            "WITH rle, nr, ps, collect(DISTINCT CASE WHEN pe IS NULL THEN null ELSE {physicalEntity: pe.stId, role:{n: i.stoichiometry, type: 'input'}, drug: (pe:Drug) OR NOT d IS NULL, crossed:true} END) AS newPS " +
            "WITH rle, nr, ps + newPS AS ps " +

            "OPTIONAL MATCH (rle)-[o:output]->(pe:PhysicalEntity) " +
            "OPTIONAL MATCH (pe)-[:hasComponent|hasMember|hasCandidate|proteinMarker|RNAMarker*]->(d:Drug) " +
            "WITH rle, nr, ps, collect(DISTINCT CASE WHEN pe IS NULL THEN null ELSE {physicalEntity: pe.stId, role:{n: o.stoichiometry, type: 'output'}, drug: (pe:Drug) OR NOT d IS NULL} END) AS newPS " +
            "WITH rle, nr, ps + newPS AS ps " +

            "OPTIONAL MATCH (nr)-[o:output]->(pe:PhysicalEntity) " +
            "WHERE NOT exists((rle)-[:output]->(pe)) " +
            "OPTIONAL MATCH (pe)-[:hasComponent|hasMember|hasCandidate|proteinMarker|RNAMarker*]->(d:Drug) " +
            "WITH rle, nr, ps, collect(DISTINCT CASE WHEN pe IS NULL THEN null ELSE {physicalEntity: pe.stId, role:{n: o.stoichiometry, type: 'output'}, drug: (pe:Drug) OR NOT d IS NULL, crossed:true} END) AS newPS " +
            "WITH rle, nr, ps + newPS AS ps " +

            "OPTIONAL MATCH (rle)-[:catalystActivity|physicalEntity*]->(pe:PhysicalEntity) " +
            "WHERE NOT (rle:FailedReaction) OR NOT exists((rle)-[:entityFunctionalStatus]->()-[:diseaseEntity|normalEntity]->(pe)) " +
            "OPTIONAL MATCH (pe)-[:hasComponent|hasMember|hasCandidate|proteinMarker|RNAMarker*]->(d:Drug) " +
            "WITH rle, nr, ps, collect(DISTINCT CASE WHEN pe IS NULL THEN null ELSE {physicalEntity: pe.stId, role:{n: 1, type: 'catalyst'}, drug: (pe:Drug) OR NOT d IS NULL} END) AS newPS " +
            "WITH rle, nr, ps + newPS AS ps " +


            "OPTIONAL MATCH (nr)-[:catalystActivity|physicalEntity*]->(pe:PhysicalEntity) " +
            "WHERE NOT exists((rle)-[:catalystActivity|physicalEntity*]->(pe)) AND NOT exists((rle)-[:entityFunctionalStatus|diseaseEntity*]->(pe)) " +
            "OPTIONAL MATCH (pe)-[:hasComponent|hasMember|hasCandidate|proteinMarker|RNAMarker*]->(d:Drug) " +
            "WITH rle, nr, ps, collect(DISTINCT CASE WHEN pe IS NULL THEN null ELSE {physicalEntity: pe.stId, role:{n: 1, type: 'catalyst'}, drug: (pe:Drug) OR NOT d IS NULL, crossed:true} END) AS newPS " +
            "WITH rle, nr, ps + newPS AS ps " +

            "OPTIONAL MATCH (rle)-[:regulatedBy]->(:NegativeRegulation)-[:regulator]->(pe:PhysicalEntity) " +
            "WHERE NOT (rle:FailedReaction) OR NOT exists((rle)-[:entityFunctionalStatus]->()-[:diseaseEntity|normalEntity]->(pe)) " +
            "OPTIONAL MATCH (pe)-[:hasComponent|hasMember|hasCandidate|proteinMarker|RNAMarker*]->(d:Drug) " +
            "WITH rle, nr, ps, collect(DISTINCT CASE WHEN pe IS NULL THEN null ELSE {physicalEntity: pe.stId, role:{n: 1, type: 'negative'}, drug: (pe:Drug) OR NOT d IS NULL} END) AS newPS " +
            "WITH rle, nr, ps + newPS AS ps " +

            "OPTIONAL MATCH (nr)-[:regulatedBy]->(:NegativeRegulation)-[:regulator]->(pe:PhysicalEntity) " +
            "WHERE NOT exists((rle)-[:regulatedBy]->(:NegativeRegulation)-[:regulator]->(pe)) AND NOT exists((rle)-[:entityFunctionalStatus|normalEntity*]->(pe)) " +
            "OPTIONAL MATCH (pe)-[:hasComponent|hasMember|hasCandidate|proteinMarker|RNAMarker*]->(d:Drug) " +
            "WITH rle, nr, ps, collect(DISTINCT CASE WHEN pe IS NULL THEN null ELSE {physicalEntity: pe.stId, role:{n: 1, type: 'negative'}, drug: (pe:Drug) OR NOT d IS NULL, crossed:true} END) AS newPS " +
            "WITH rle, nr, ps + newPS AS ps " +

            "OPTIONAL MATCH (rle)-[:regulatedBy]->(:PositiveRegulation)-[:regulator]->(pe:PhysicalEntity) " +
            "WHERE NOT (rle:FailedReaction) OR NOT exists((rle)-[:entityFunctionalStatus]->()-[:diseaseEntity|normalEntity]->(pe)) " +
            "OPTIONAL MATCH (pe)-[:hasComponent|hasMember|hasCandidate|proteinMarker|RNAMarker*]->(d:Drug) " +
            "WITH rle, nr, ps, collect(DISTINCT CASE WHEN pe IS NULL THEN null ELSE {physicalEntity: pe.stId, role:{n: 1, type: 'positive'}, drug: (pe:Drug) OR NOT d IS NULL} END) AS newPS " +
            "WITH rle, nr, ps + newPS AS ps " +

            "OPTIONAL MATCH (nr)-[:regulatedBy]->(:PositiveRegulation)-[:regulator]->(pe:PhysicalEntity) " +
            "WHERE NOT exists((rle)-[:regulatedBy]->(:PositiveRegulation)-[:regulator]->(pe)) AND NOT exists((rle)-[:entityFunctionalStatus|normalEntity*]->(pe)) " +
            "OPTIONAL MATCH (pe)-[:hasComponent|hasMember|hasCandidate|proteinMarker|RNAMarker*]->(d:Drug) " +
            "WITH rle, nr, ps, collect(DISTINCT CASE WHEN pe IS NULL THEN null ELSE {physicalEntity: pe.stId, role:{n: 1, type: 'positive'}, drug: (pe:Drug) OR NOT d IS NULL, crossed:true} END) AS newPS " +
            "WITH rle, nr, ps + newPS AS ps " +

            "OPTIONAL MATCH (nr)-[:input]->(:PhysicalEntity)<-[:normalEntity]-(efs:EntityFunctionalStatus)<-[:entityFunctionalStatus]-(rle) " +
            "WHERE (rle:FailedReaction) AND NOT exists((rle)-[:input]->(:PhysicalEntity)<-[:diseaseEntity|normalEntity]-(efs)) " +
            "OPTIONAL MATCH (efs)-[:entityFunctionalStatus|diseaseEntity*]->(pe:PhysicalEntity) " +
            "OPTIONAL MATCH (pe)-[:hasComponent|hasMember|hasCandidate|proteinMarker|RNAMarker*]->(d:Drug) " +
            "WITH rle, nr, ps, collect(DISTINCT CASE WHEN pe IS NULL THEN null ELSE {physicalEntity: pe.stId, role:{n: 1, type: 'input'}, drug: (pe:Drug) OR NOT d IS NULL, dashed:true} END) AS newPS " +
            "WITH rle, nr, ps + newPS AS ps " +

            "OPTIONAL MATCH (rle)-[:input]->(:PhysicalEntity)<-[:diseaseEntity|normalEntity]-(efs:EntityFunctionalStatus)<-[:entityFunctionalStatus]-(rle) " +
            "WHERE (rle:FailedReaction) " +
            "OPTIONAL MATCH (efs)-[:entityFunctionalStatus|diseaseEntity*]->(pe:PhysicalEntity) " +
            "OPTIONAL MATCH (pe)-[:hasComponent|hasMember|hasCandidate|proteinMarker|RNAMarker*]->(d:Drug) " +
            "WITH rle, nr, ps, collect(DISTINCT CASE WHEN pe IS NULL THEN null ELSE {physicalEntity: pe.stId, role:{n: 1, type: 'input'}, drug: (pe:Drug) OR NOT d IS NULL, dashed:true} END) AS newPS " +
            "WITH rle, nr, ps + newPS AS ps " +

            "OPTIONAL MATCH (nr)-[:catalystActivity|physicalEntity*]->(:PhysicalEntity)<-[:normalEntity]-(efs:EntityFunctionalStatus)<-[:entityFunctionalStatus]-(rle) " +
            "WHERE (rle:FailedReaction) AND NOT exists((rle)-[:catalystActivity|physicalEntity*]->(:PhysicalEntity)<-[:diseaseEntity|normalEntity]-(efs)) " +
            "OPTIONAL MATCH (efs)-[:entityFunctionalStatus|diseaseEntity*]->(pe:PhysicalEntity) " +
            "OPTIONAL MATCH (pe)-[:hasComponent|hasMember|hasCandidate|proteinMarker|RNAMarker*]->(d:Drug) " +
            "WITH rle, nr, ps, collect(DISTINCT CASE WHEN pe IS NULL THEN null ELSE {physicalEntity: pe.stId, role:{n: 1, type: 'catalyst'}, drug: (pe:Drug) OR NOT d IS NULL, dashed:true} END) AS newPS " +
            "WITH rle, nr, ps + newPS AS ps " +

            "OPTIONAL MATCH (rle)-[:catalystActivity|physicalEntity*]->(:PhysicalEntity)<-[:diseaseEntity|normalEntity]-(efs:EntityFunctionalStatus)<-[:entityFunctionalStatus]-(rle) " +
            "WHERE (rle:FailedReaction) " +
            "OPTIONAL MATCH (efs)-[:entityFunctionalStatus|diseaseEntity*]->(pe:PhysicalEntity) " +
            "OPTIONAL MATCH (pe)-[:hasComponent|hasMember|hasCandidate|proteinMarker|RNAMarker*]->(d:Drug) " +
            "WITH rle, nr, ps, collect(DISTINCT CASE WHEN pe IS NULL THEN null ELSE {physicalEntity: pe.stId, role:{n: 1, type: 'catalyst'}, drug: (pe:Drug) OR NOT d IS NULL, dashed:true} END) AS newPS " +
            "WITH rle, nr, ps + newPS AS ps " +

            "OPTIONAL MATCH (nr)-[:regulatedBy]->(:NegativeRegulation)-[:regulator]->(:PhysicalEntity)<-[:normalEntity]-(efs:EntityFunctionalStatus)<-[:entityFunctionalStatus]-(rle) " +
            "WHERE (rle:FailedReaction) AND NOT exists((rle)-[:regulatedBy]->(:NegativeRegulation)-[:regulator]->(:PhysicalEntity)<-[:diseaseEntity|normalEntity]-(efs)) " +
            "OPTIONAL MATCH (efs)-[:entityFunctionalStatus|diseaseEntity*]->(pe:PhysicalEntity) " +
            "OPTIONAL MATCH (pe)-[:hasComponent|hasMember|hasCandidate|proteinMarker|RNAMarker*]->(d:Drug) " +
            "WITH rle, nr, ps, collect(DISTINCT CASE WHEN pe IS NULL THEN null ELSE {physicalEntity: pe.stId, role:{n: 1, type: 'negative'}, drug: (pe:Drug) OR NOT d IS NULL, dashed:true} END) AS newPS " +
            "WITH rle, nr, ps + newPS AS ps " +

            "OPTIONAL MATCH (rle)-[:regulatedBy]->(:NegativeRegulation)-[:regulator]->(:PhysicalEntity)<-[:diseaseEntity|normalEntity]-(efs:EntityFunctionalStatus)<-[:entityFunctionalStatus]-(rle) " +
            "WHERE (rle:FailedReaction) " +
            "OPTIONAL MATCH (efs)-[:entityFunctionalStatus|diseaseEntity*]->(pe:PhysicalEntity) " +
            "OPTIONAL MATCH (pe)-[:hasComponent|hasMember|hasCandidate|proteinMarker|RNAMarker*]->(d:Drug) " +
            "WITH rle, nr, ps, collect(DISTINCT CASE WHEN pe IS NULL THEN null ELSE {physicalEntity: pe.stId, role:{n: 1, type: 'negative'}, drug: (pe:Drug) OR NOT d IS NULL, dashed:true} END) AS newPS " +
            "WITH rle, nr, ps + newPS AS ps " +

            "OPTIONAL MATCH (nr)-[:regulatedBy]->(:PositiveRegulation)-[:regulator]->(:PhysicalEntity)<-[:normalEntity]-(efs:EntityFunctionalStatus)<-[:entityFunctionalStatus]-(rle) " +
            "WHERE (rle:FailedReaction) AND NOT exists((rle)-[:regulatedBy]->(:PositiveRegulation)-[:regulator]->(:PhysicalEntity)<-[:diseaseEntity|normalEntity]-(efs)) " +
            "OPTIONAL MATCH (efs)-[:entityFunctionalStatus|diseaseEntity*]->(pe:PhysicalEntity) " +
            "OPTIONAL MATCH (pe)-[:hasComponent|hasMember|hasCandidate|proteinMarker|RNAMarker*]->(d:Drug) " +
            "WITH rle, ps, collect(DISTINCT CASE WHEN pe IS NULL THEN null ELSE {physicalEntity: pe.stId, role:{n: 1, type: 'positive'}, drug: (pe:Drug) OR NOT d IS NULL, dashed:true} END) AS newPS " +
            "WITH rle, ps + newPS AS ps " +

            "OPTIONAL MATCH (rle)-[:regulatedBy]->(:PositiveRegulation)-[:regulator]->(:PhysicalEntity)<-[:diseaseEntity|normalEntity]-(efs:EntityFunctionalStatus)<-[:entityFunctionalStatus]-(rle) " +
            "WHERE (rle:FailedReaction) " +
            "OPTIONAL MATCH (efs)-[:entityFunctionalStatus|diseaseEntity*]->(pe:PhysicalEntity) " +
            "OPTIONAL MATCH (pe)-[:hasComponent|hasMember|hasCandidate|proteinMarker|RNAMarker*]->(d:Drug) " +
            "WITH rle, ps, collect(DISTINCT CASE WHEN pe IS NULL THEN null ELSE {physicalEntity: pe.stId, role:{n: 1, type: 'positive'}, drug: (pe:Drug) OR NOT d IS NULL, dashed:true} END) AS newPS " +
            "WITH rle, ps + newPS AS ps " +

            "OPTIONAL MATCH path=(p:Pathway{hasDiagram:true})-[:hasEvent*]->(rle) " +
            "WHERE single(x IN nodes(path) WHERE (x:Pathway) AND x.hasDiagram) " +
            "RETURN p.stId AS pathway, rle.stId AS reactionLikeEvent, ps AS participants " +
            "LIMIT 1";

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
            ReactionGraphFactory graphFactory = new ReactionGraphFactory(advancedDatabaseObjectService);
            ReactomeCompartmentFactory.setAdvancedDatabaseObjectService(advancedDatabaseObjectService);

            Layout layout = buildLayout(rle);
            Diagram diagram = ReactionDiagramFactory.get(layout);
            Graph graph = graphFactory.getGraph(rle, layout);

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
        } catch (UnsupportedOperationException e) {
            // Known, permanent limitation, not a bug to chase: diagram-exporter's RenderableFactory
            // has a fixed switch over renderable classes (Protein, Chemical, Complex, ...) with no
            // case for newer participant types like Cell (used by CellDevelopmentStep, added to
            // Reactome's schema for cell/development-biology modeling after this library's diagram
            // rendering was written) - it throws unconditionally rather than degrading. Not
            // fixable without patching that third-party jar, so this logs at info (an expected
            // skip) instead of warn; the caller already handles a null image fine (no image is
            // added to the docx for this reaction).
            logger.info("Skipping reaction image for {} - unsupported by diagram-exporter: {}", rle.getStId(), e.getMessage());
            return null;
        } catch (Exception e) {
            // Pass e itself (not just e.getMessage()) so SLF4J logs the stack trace - exceptions
            // like NPEs often have a null message, which otherwise leaves nothing to diagnose from.
            logger.warn("Failed to render reaction image for {}", rle.getStId(), e);
            return null;
        }
    }

    /**
     * Renders multiple reaction images concurrently, keyed by dbId. Each render is otherwise
     * independent (own layout/diagram/graph/raster pipeline), so this is a straightforward
     * embarrassingly-parallel workload - the only hazard is reaction-exporter's compartment/GO
     * tree cache: GoTreeFactory.getLazyLoadedReactomeTree() lazily populates a static field with
     * an unsynchronized check-then-act ("if (tree == null) tree = build...") the first time any
     * reaction is rendered. Hitting that from multiple threads before it's been populated once
     * would race (duplicate/partial builds mutating shared GoTerm objects concurrently). Render
     * the first reaction alone, sequentially, to populate it deterministically; every call after
     * that only reads the cached tree, which is safe to do concurrently.
     */
    public Map<Long, byte[]> renderReactionImagesInParallel(List<ReactionLikeEvent> reactions) {
        Map<Long, byte[]> result = new ConcurrentHashMap<>();
        if (reactions == null || reactions.isEmpty()) {
            return result;
        }

        ReactionLikeEvent first = reactions.get(0);
        byte[] firstImage = renderReactionImage(first);
        if (firstImage != null && first.getDbId() != null) {
            result.put(first.getDbId(), firstImage);
        }

        List<ReactionLikeEvent> rest = reactions.subList(1, reactions.size());
        if (rest.isEmpty()) {
            return result;
        }

        int threads = Math.max(1, Math.min(rest.size(), Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (ReactionLikeEvent rle : rest) {
                futures.add(executor.submit(() -> {
                    byte[] image = renderReactionImage(rle);
                    if (image != null && rle.getDbId() != null) {
                        result.put(rle.getDbId(), image);
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    logger.warn("Failed to render a reaction image in parallel: {}", e.getMessage());
                }
            }
        } finally {
            executor.shutdown();
        }
        return result;
    }

    /**
     * Re-implementation of LayoutFactory.getReactionLikeEventLayout() (reaction-exporter is a
     * third-party jar; its QUERY constant and loadPEFully() method are both private, so it can't
     * be subclassed or patched - this copies the same query and produces the same Layout, just
     * with batched participant loading instead of one full findById() (plus one more findById()
     * per modified residue) per participant. See ReactionImageRendererService's git history /
     * conversation for the measured N+1 cost this replaces.
     */
    private Layout buildLayout(ReactionLikeEvent rle) throws CustomQueryException {
        Map<String, Object> params = new HashMap<>();
        params.put("stId", rle.getStId());
        LayoutResult layoutResult = advancedDatabaseObjectService.getCustomQueryResult(LayoutResult.class, LAYOUT_QUERY, params);

        Layout layout = new Layout();
        layout.setPathway(layoutResult.getPathwayStId());
        layout.setReactionLikeEvent(databaseObjectService.findByIdNoRelations(layoutResult.getReactionStId()));

        List<LayoutParticipants> participantsInfo = layoutResult.getParticipants();
        Set<String> peStIds = participantsInfo.stream()
                .map(LayoutParticipants::getPhysicalEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, PhysicalEntity> peByStId = loadPhysicalEntitiesBatched(peStIds);

        Collection<EntityGlyph> participants = new ArrayList<>();
        for (LayoutParticipants lp : participantsInfo) {
            if (lp.getPhysicalEntity() == null) continue;
            EntityGlyph glyph = new EntityGlyph();
            glyph.setDrug(lp.isDrug());
            glyph.setDashed(lp.isDashed());
            glyph.setPhysicalEntity(peByStId.get(lp.getPhysicalEntity()));
            glyph.setRole(new Role(lp.getRole().getType(), lp.getRole().getStoichiometry()));
            participants.add(glyph);
        }
        layout.setParticipants(participants);
        new BoxAlgorithm(layout).compute();
        return layout;
    }

    /**
     * Loads all participant PhysicalEntities in ~3 round trips total instead of one (or more, for
     * entities with modified residues) per participant:
     *   1. Shallow load of every stId, guaranteeing one entry per participant even if it has none
     *      of the relationships enriched below (findByStIds's MATCH is required, not optional, so
     *      an entity with neither compartment nor hasModifiedResidue nor referenceEntity would
     *      otherwise be silently missing from the result).
     *   2. One batched query for compartment + hasModifiedResidue + referenceEntity across all
     *      participant stIds at once (needed for compartment coloring, modification markers, and
     *      the small-molecule "trivial" flag - see EntityGlyph.setPhysicalEntity in reaction-exporter).
     *   3. One batched query for psiMod across all modified residues collected in step 2, to
     *      resolve each TranslationalModification's display label the same way the original
     *      per-residue loadPEFully() loop did.
     */
    private Map<String, PhysicalEntity> loadPhysicalEntitiesBatched(Set<String> peStIds) {
        Map<String, PhysicalEntity> result = new HashMap<>();
        if (peStIds.isEmpty()) return result;

        for (Object obj : databaseObjectService.findByIdsNoRelations(peStIds)) {
            PhysicalEntity pe = (PhysicalEntity) obj;
            result.put(pe.getStId(), pe);
        }

        Collection<DatabaseObject> enriched = advancedDatabaseObjectService.findByStIds(
                peStIds, RelationshipDirection.OUTGOING, "compartment", "hasModifiedResidue", "referenceEntity");
        Map<String, PhysicalEntity> enrichedByStId = new HashMap<>();
        for (DatabaseObject obj : enriched) {
            PhysicalEntity pe = (PhysicalEntity) obj;
            enrichedByStId.put(pe.getStId(), pe);
            result.put(pe.getStId(), pe);
        }

        Set<Long> modDbIds = new HashSet<>();
        for (PhysicalEntity pe : enrichedByStId.values()) {
            if (pe instanceof EntityWithAccessionedSequence) {
                List<AbstractModifiedResidue> mods = ((EntityWithAccessionedSequence) pe).getHasModifiedResidue();
                if (mods != null) {
                    for (AbstractModifiedResidue r : mods) modDbIds.add(r.getDbId());
                }
            }
        }
        if (!modDbIds.isEmpty()) {
            Map<Long, TranslationalModification> enrichedModsByDbId = new HashMap<>();
            Collection<DatabaseObject> modsWithPsiMod = advancedDatabaseObjectService.findByDbIds(
                    modDbIds, RelationshipDirection.OUTGOING, "psiMod");
            for (DatabaseObject obj : modsWithPsiMod) {
                if (obj instanceof TranslationalModification) {
                    TranslationalModification tMod = (TranslationalModification) obj;
                    if (tMod.getPsiMod() != null && tMod.getPsiMod().getLabel() != null) {
                        tMod.setLabel(tMod.getPsiMod().getLabel());
                    }
                    enrichedModsByDbId.put(tMod.getDbId(), tMod);
                }
            }
            for (PhysicalEntity pe : enrichedByStId.values()) {
                if (!(pe instanceof EntityWithAccessionedSequence)) continue;
                EntityWithAccessionedSequence ewas = (EntityWithAccessionedSequence) pe;
                List<AbstractModifiedResidue> mods = ewas.getHasModifiedResidue();
                if (mods == null) continue;
                List<AbstractModifiedResidue> replaced = new ArrayList<>();
                for (AbstractModifiedResidue r : mods) {
                    TranslationalModification enrichedMod = enrichedModsByDbId.get(r.getDbId());
                    replaced.add(enrichedMod != null ? enrichedMod : r);
                }
                ewas.setHasModifiedResidue(replaced);
            }
        }

        return result;
    }
}
