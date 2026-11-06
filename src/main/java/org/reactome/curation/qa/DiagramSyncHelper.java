package org.reactome.curation.qa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.gk.render.FlowLine;
import org.gk.render.Node;
import org.gk.render.Renderable;
import org.gk.render.RenderablePathway;
import org.gk.render.RenderableReaction;
import org.reactome.curation.exceptions.DatabaseObjectNotFoundException;
import org.reactome.curation.model.ReactionParticipant;
import org.reactome.curation.model.ReactionRegulator;
import org.reactome.curation.model.ReactionStructure;
import org.reactome.curation.qa.model.DiagramSyncIssue;
import org.reactome.curation.qa.model.DiagramSyncReport;
import org.reactome.curation.repository.CurationRepository;
import org.reactome.curation.service.PathwayDiagramService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Check that what is drawn in PathwayDiagrams is in sync with the database. This is a port of
 * org.gk.qualityCheck.ReactionSyncELVCheck in the CuratorTool project:
 * <ul>
 * <li>Each drawn reaction must have the same inputs and outputs (with stoichiometry), catalysts,
 * activators and inhibitors as the annotated ReactionlikeEvent.</li>
 * <li>Each FlowLine drawn between two Events must be backed by precedingEvent: the Event at the arrow end
 * must list the Event at the tail end. FlowLines linking an entity to an Event are skipped. precedingEvent
 * values without a FlowLine are not checked, since precedence between reactions is normally implied by
 * shared entities rather than drawn.</li>
 * </ul>
 * Only one issue is reported per Event per diagram. A reaction issue is kept over a FlowLine issue.
 *
 * Unlike the desktop version, diagrams are not read from storedATXML. A diagram edited through this tool is
 * read from its saved Cytoscape JSON. Otherwise the layout JSON of its normal represented Pathway (the one the
 * front-end opens) is used.
 */
public class DiagramSyncHelper {
    private static final Logger logger = LoggerFactory.getLogger(DiagramSyncHelper.class);

    // PathwayDiagrams of an Event and all its sub-pathways. hasEvent*0.. includes the Event itself.
    private static final String DIAGRAMS_QUERY = ""
            + "MATCH (e:Event {dbId: $dbId}) "
            + "OPTIONAL MATCH (e)-[:hasEvent*0..]->(:Pathway)<-[:representedPathway]-(pd:PathwayDiagram) "
            + "RETURN e.displayName AS displayName, collect(DISTINCT pd.dbId) AS diagramDbIds";

    // Represented Pathways of the diagrams, normal (non-disease) ones first
    private static final String REPRESENTED_PATHWAYS_QUERY = ""
            + "UNWIND $dbIds AS id "
            + "MATCH (pd:PathwayDiagram {dbId: id})-[:representedPathway]->(p:Pathway) "
            + "RETURN pd.dbId AS diagramDbId, p.dbId AS pathwayDbId, p.displayName AS displayName, "
            + "NOT (p)-[:disease]->() AS isNormal "
            + "ORDER BY diagramDbId, isNormal DESC, pathwayDbId";

    // Basic information for the drawn objects needed by the check. modified holds the latest InstanceEdit;
    // modifiedList and created are fallbacks (see ReactionImbalanceHelper).
    private static final String INSTANCE_INFO_QUERY = ""
            + "UNWIND $dbIds AS id "
            + "MATCH (e:DatabaseObject {dbId: id}) "
            + "OPTIONAL MATCH (e)-[:precedingEvent]->(pe:DatabaseObject) "
            + "WITH e, collect(pe.dbId) AS precedingEvents "
            + "OPTIONAL MATCH (e)<-[ml:modifiedList]-(m:InstanceEdit) "
            + "WITH e, precedingEvents, m ORDER BY ml.order DESC "
            + "WITH e, precedingEvents, collect(m.displayName)[0] AS lastInList "
            + "OPTIONAL MATCH (e)<-[:modified]-(lm:InstanceEdit) "
            + "WITH e, precedingEvents, lastInList, collect(lm.displayName)[0] AS lastModified "
            + "OPTIONAL MATCH (e)<-[:created]-(c:InstanceEdit) "
            + "WITH e, precedingEvents, lastInList, lastModified, collect(c.displayName)[0] AS created "
            + "RETURN e.dbId AS dbId, e.displayName AS displayName, e.schemaClass AS schemaClass, "
            + "'Event' IN labels(e) AS isEvent, precedingEvents, created, "
            + "coalesce(lastModified, lastInList, created) AS modified";

    private final Neo4jClient neo4jClient;
    private final CurationRepository repository;
    private final PathwayDiagramService diagramService;

    public DiagramSyncHelper(Neo4jClient neo4jClient,
                             CurationRepository repository,
                             PathwayDiagramService diagramService) {
        this.neo4jClient = neo4jClient;
        this.repository = repository;
        this.diagramService = diagramService;
    }

    /**
     * Check the diagrams of an Event and all of its sub-pathways.
     * @param eventDbId
     * @return
     */
    public DiagramSyncReport checkEvent(Long eventDbId) throws Exception {
        Map<String, Object> row = neo4jClient.query(DIAGRAMS_QUERY)
                .bind(eventDbId).to("dbId")
                .fetch()
                .one()
                .orElseThrow(() -> new DatabaseObjectNotFoundException(eventDbId));
        List<Long> diagramDbIds = new ArrayList<>();
        for (Object id : (Collection<?>) row.get("diagramDbIds"))
            diagramDbIds.add(((Number) id).longValue());
        DiagramSyncReport report = new DiagramSyncReport();
        report.setEventDbId(eventDbId);
        report.setEventDisplayName((String) row.get("displayName"));
        report.setUncheckedDiagramDbIds(new ArrayList<>());
        report.setIssues(new ArrayList<>());
        if (diagramDbIds.isEmpty())
            return report;
        Map<Long, PathwayInfo> diagramToPathway = fetchRepresentedPathways(diagramDbIds);
        int checked = 0;
        for (Long diagramDbId : diagramDbIds) {
            PathwayInfo pathway = diagramToPathway.get(diagramDbId);
            if (pathway == null)
                continue; // Nothing to be checked with, the same as the desktop version
            DrawnDiagram drawn = loadDiagram(diagramDbId, pathway.dbId);
            if (drawn == null) {
                report.getUncheckedDiagramDbIds().add(diagramDbId);
                continue;
            }
            checked++;
            List<DiagramSyncIssue> issues = checkDiagram(drawn);
            for (DiagramSyncIssue issue : issues) {
                issue.setDiagramDbId(diagramDbId);
                issue.setPathwayDbId(pathway.dbId);
                issue.setPathwayDisplayName(pathway.displayName);
            }
            report.getIssues().addAll(issues);
        }
        report.setCheckedDiagrams(checked);
        report.getIssues().sort(Comparator.comparing(DiagramSyncIssue::getPathwayDisplayName,
                                                     Comparator.nullsLast(String::compareTo))
                .thenComparing(DiagramSyncIssue::getDiagramDbId)
                .thenComparing(DiagramSyncIssue::getEventDisplayName, Comparator.nullsLast(String::compareTo)));
        return report;
    }

    private Map<Long, PathwayInfo> fetchRepresentedPathways(List<Long> diagramDbIds) {
        Collection<Map<String, Object>> rows = neo4jClient.query(REPRESENTED_PATHWAYS_QUERY)
                .bind(diagramDbIds).to("dbIds")
                .fetch()
                .all();
        Map<Long, PathwayInfo> rtn = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long diagramDbId = ((Number) row.get("diagramDbId")).longValue();
            // Rows are sorted so that a normal pathway comes first
            rtn.putIfAbsent(diagramDbId, new PathwayInfo(((Number) row.get("pathwayDbId")).longValue(),
                                                         (String) row.get("displayName")));
        }
        return rtn;
    }

    /**
     * Load a diagram from its Cytoscape JSON if it has been edited through this tool, otherwise from
     * the layout JSON of its normal Pathway.
     * @return null if neither can be found
     */
    private DrawnDiagram loadDiagram(Long diagramDbId, Long pathwayDbId) throws Exception {
        try {
            RenderablePathway renderable = diagramService.loadCyNetworkAsDiagram(diagramDbId);
            if (renderable != null)
                return fromRenderablePathway(renderable);
        }
        catch (Exception e) {
            // Don't let one diagram fail the whole report. Saving a Cytoscape diagram also regenerates the
            // layout JSON, so that is a reasonable fallback.
            logger.warn("Cannot convert the Cytoscape JSON of PathwayDiagram " + diagramDbId +
                        ". Using the layout JSON of Pathway " + pathwayDbId + " instead.", e);
        }
        JsonNode layout = diagramService.loadDiagramJson(pathwayDbId + ".json");
        if (layout != null)
            return fromLayoutJson(layout);
        logger.warn("No diagram file found for PathwayDiagram {} (Pathway {})", diagramDbId, pathwayDbId);
        return null;
    }

    /**
     * Check a single drawn diagram against the database. Diagram and pathway fields of the returned issues
     * are not set.
     */
    List<DiagramSyncIssue> checkDiagram(DrawnDiagram drawn) {
        Set<Long> neededIds = new HashSet<>();
        for (DrawnReaction reaction : drawn.reactions)
            neededIds.add(reaction.dbId);
        for (Long[] line : drawn.flowLines) {
            neededIds.add(line[0]);
            neededIds.add(line[1]);
        }
        if (neededIds.isEmpty())
            return new ArrayList<>();
        Map<Long, InstanceInfo> idToInfo = fetchInstanceInfo(neededIds);
        Map<Long, ReactionStructure> idToStructure = new HashMap<>();
        if (!drawn.reactions.isEmpty()) {
            List<Long> reactionIds = new ArrayList<>();
            for (DrawnReaction reaction : drawn.reactions)
                reactionIds.add(reaction.dbId);
            for (ReactionStructure structure : repository.findReactionStructuresByDbIds(reactionIds))
                idToStructure.put(structure.getReactionDbId(), structure);
        }
        return compare(drawn, idToStructure, idToInfo);
    }

    /**
     * The comparison itself, separated from the database queries for testing.
     */
    static List<DiagramSyncIssue> compare(DrawnDiagram drawn,
                                          Map<Long, ReactionStructure> idToStructure,
                                          Map<Long, InstanceInfo> idToInfo) {
        // Keep the insertion order so that reaction issues win over FlowLine ones
        Map<Long, DiagramSyncIssue> eventToIssue = new LinkedHashMap<>();
        for (DrawnReaction reaction : drawn.reactions) {
            if (eventToIssue.containsKey(reaction.dbId))
                continue; // Drawn more than once. Only one issue per event.
            ReactionStructure structure = idToStructure.get(reaction.dbId);
            if (structure == null) {
                logger.error("Drawn reaction cannot be found in the database: {}", reaction.dbId);
                continue;
            }
            String issue = compareReaction(reaction, structure);
            if (issue != null)
                eventToIssue.put(reaction.dbId, createIssue(reaction.dbId, issue, null, idToInfo));
        }
        for (Long[] line : drawn.flowLines) {
            InstanceInfo preceding = idToInfo.get(line[0]);
            InstanceInfo following = idToInfo.get(line[1]);
            // FlowLines may also link entities to events. Only event-to-event links correspond to precedingEvent.
            if (preceding == null || following == null || !preceding.isEvent || !following.isEvent)
                continue;
            // A FlowLine drawn from a node back to itself marks a cycle (e.g. "Neurotransmitter release cycle").
            // An Event cannot precede itself.
            if (preceding.dbId.equals(following.dbId))
                continue;
            if (following.precedingEvents.contains(preceding.dbId))
                continue;
            if (eventToIssue.containsKey(following.dbId))
                continue; // Keep the existing issue
            eventToIssue.put(following.dbId,
                             createIssue(following.dbId,
                                         "precedingEvent out of sync: missing " + preceding.dbId,
                                         preceding.dbId,
                                         idToInfo));
        }
        return new ArrayList<>(eventToIssue.values());
    }

    private static DiagramSyncIssue createIssue(Long eventDbId,
                                                String text,
                                                Long missingPrecedingEventDbId,
                                                Map<Long, InstanceInfo> idToInfo) {
        DiagramSyncIssue issue = new DiagramSyncIssue();
        issue.setEventDbId(eventDbId);
        issue.setIssue(text);
        issue.setMissingPrecedingEventDbId(missingPrecedingEventDbId);
        InstanceInfo info = idToInfo.get(eventDbId);
        if (info != null) {
            issue.setEventDisplayName(info.displayName);
            issue.setEventSchemaClass(info.schemaClass);
            issue.setCreated(info.created);
            issue.setModified(info.modified);
        }
        return issue;
    }

    /**
     * @return the issue of the first slot out of sync, or null if the drawn reaction is in sync
     */
    static String compareReaction(DrawnReaction reaction, ReactionStructure structure) {
        if (!reaction.inputs.equals(toCounts(structure.getInputs())))
            return "input out of sync";
        if (!reaction.outputs.equals(toCounts(structure.getOutputs())))
            return "output out of sync";
        Set<Long> catalysts = new HashSet<>();
        if (structure.getCatalysts() != null)
            catalysts.addAll(structure.getCatalysts());
        if (!reaction.catalysts.equals(catalysts))
            return "catalyst out of sync";
        Set<Long> activators = new HashSet<>();
        Set<Long> inhibitors = new HashSet<>();
        if (structure.getRegulators() != null) {
            for (ReactionRegulator regulator : structure.getRegulators()) {
                List<String> labels = regulator.getLabels();
                if (labels == null)
                    continue;
                if (labels.contains("PositiveRegulation"))
                    activators.add(regulator.getDbId());
                else if (labels.contains("NegativeRegulation"))
                    inhibitors.add(regulator.getDbId());
            }
        }
        if (!reaction.activators.equals(activators))
            return "activator out of sync";
        if (!reaction.inhibitors.equals(inhibitors))
            return "inhibitor out of sync";
        return null;
    }

    private static Map<Long, Integer> toCounts(List<ReactionParticipant> participants) {
        Map<Long, Integer> counts = new HashMap<>();
        if (participants == null)
            return counts;
        for (ReactionParticipant participant : participants) {
            if (participant.getDbId() == null)
                continue; // From an OPTIONAL MATCH with no value
            counts.merge(participant.getDbId(), Math.max(participant.getStoichiometry(), 1), Integer::sum);
        }
        return counts;
    }

    private Map<Long, InstanceInfo> fetchInstanceInfo(Collection<Long> dbIds) {
        Collection<Map<String, Object>> rows = neo4jClient.query(INSTANCE_INFO_QUERY)
                .bind(dbIds).to("dbIds")
                .fetch()
                .all();
        Map<Long, InstanceInfo> rtn = new HashMap<>();
        for (Map<String, Object> row : rows) {
            InstanceInfo info = new InstanceInfo();
            info.dbId = ((Number) row.get("dbId")).longValue();
            info.displayName = (String) row.get("displayName");
            info.schemaClass = (String) row.get("schemaClass");
            info.isEvent = Boolean.TRUE.equals(row.get("isEvent"));
            for (Object id : (Collection<?>) row.get("precedingEvents"))
                info.precedingEvents.add(((Number) id).longValue());
            info.created = (String) row.get("created");
            info.modified = (String) row.get("modified");
            rtn.put(info.dbId, info);
        }
        return rtn;
    }

    /**
     * Extract reactions and FlowLines from a RenderablePathway (converted from Cytoscape JSON).
     */
    @SuppressWarnings("unchecked")
    static DrawnDiagram fromRenderablePathway(RenderablePathway diagram) {
        DrawnDiagram drawn = new DrawnDiagram();
        List<Renderable> components = diagram.getComponents();
        if (components == null)
            return drawn;
        for (Renderable r : components) {
            if (r instanceof RenderableReaction && r.getReactomeId() != null) {
                RenderableReaction rxt = (RenderableReaction) r;
                DrawnReaction reaction = new DrawnReaction(rxt.getReactomeId());
                addNodes(rxt.getInputNodes(), rxt.getInputStoichiometries(), reaction.inputs);
                addNodes(rxt.getOutputNodes(), rxt.getOutputStoichiometries(), reaction.outputs);
                addNodes(rxt.getHelperNodes(), reaction.catalysts);
                addNodes(rxt.getActivatorNodes(), reaction.activators);
                addNodes(rxt.getInhibitorNodes(), reaction.inhibitors);
                drawn.reactions.add(reaction);
            }
            else if (r instanceof FlowLine) {
                FlowLine line = (FlowLine) r;
                Node input = line.getInputNode(0);
                Node output = line.getOutputNode(0);
                if (input == null || output == null || input.getReactomeId() == null || output.getReactomeId() == null)
                    continue;
                drawn.flowLines.add(new Long[] {input.getReactomeId(), output.getReactomeId()});
            }
        }
        return drawn;
    }

    private static void addNodes(List<Node> nodes, Map<Renderable, Integer> nodeToStoi, Map<Long, Integer> counts) {
        if (nodes == null)
            return;
        for (Node node : nodes) {
            if (node.getReactomeId() == null)
                continue;
            Integer stoi = nodeToStoi == null ? null : nodeToStoi.get(node);
            counts.merge(node.getReactomeId(), stoi == null || stoi < 1 ? 1 : stoi, Integer::sum);
        }
    }

    private static void addNodes(List<Node> nodes, Set<Long> ids) {
        if (nodes == null)
            return;
        for (Node node : nodes) {
            if (node.getReactomeId() != null)
                ids.add(node.getReactomeId());
        }
    }

    /**
     * Extract reactions and FlowLines from a pathway-browser layout JSON (the graph folder's
     * &lt;pathwayDbId&gt;.json). Stoichiometries are kept in the nodes' connectors.
     */
    static DrawnDiagram fromLayoutJson(JsonNode layout) {
        DrawnDiagram drawn = new DrawnDiagram();
        Map<Long, Long> nodeIdToReactomeId = new HashMap<>();
        // key: nodeId:edgeId:type
        Map<String, Integer> connectorToStoi = new HashMap<>();
        for (JsonNode node : layout.path("nodes")) {
            long nodeId = node.path("id").asLong();
            if (node.hasNonNull("reactomeId"))
                nodeIdToReactomeId.put(nodeId, node.path("reactomeId").asLong());
            for (JsonNode connector : node.path("connectors")) {
                JsonNode stoi = connector.path("stoichiometry").path("value");
                if (stoi.isNumber())
                    connectorToStoi.put(nodeId + ":" + connector.path("edgeId").asLong() + ":" +
                                        connector.path("type").asText(), stoi.asInt());
            }
        }
        for (JsonNode edge : layout.path("edges")) {
            if (!edge.hasNonNull("reactomeId"))
                continue;
            long edgeId = edge.path("id").asLong();
            DrawnReaction reaction = new DrawnReaction(edge.path("reactomeId").asLong());
            addLayoutNodes(edge.path("inputs"), edgeId, "INPUT", nodeIdToReactomeId, connectorToStoi, reaction.inputs);
            addLayoutNodes(edge.path("outputs"), edgeId, "OUTPUT", nodeIdToReactomeId, connectorToStoi, reaction.outputs);
            addLayoutNodes(edge.path("catalysts"), nodeIdToReactomeId, reaction.catalysts);
            addLayoutNodes(edge.path("activators"), nodeIdToReactomeId, reaction.activators);
            addLayoutNodes(edge.path("inhibitors"), nodeIdToReactomeId, reaction.inhibitors);
            drawn.reactions.add(reaction);
        }
        for (JsonNode link : layout.path("links")) {
            if (!"FlowLine".equals(link.path("renderableClass").asText()))
                continue;
            Long input = nodeIdToReactomeId.get(link.path("inputs").path(0).path("id").asLong(-1));
            Long output = nodeIdToReactomeId.get(link.path("outputs").path(0).path("id").asLong(-1));
            if (input != null && output != null)
                drawn.flowLines.add(new Long[] {input, output});
        }
        return drawn;
    }

    private static void addLayoutNodes(JsonNode list,
                                       long edgeId,
                                       String type,
                                       Map<Long, Long> nodeIdToReactomeId,
                                       Map<String, Integer> connectorToStoi,
                                       Map<Long, Integer> counts) {
        for (JsonNode entry : list) {
            long nodeId = entry.path("id").asLong();
            Long reactomeId = nodeIdToReactomeId.get(nodeId);
            if (reactomeId == null)
                continue;
            Integer stoi = connectorToStoi.get(nodeId + ":" + edgeId + ":" + type);
            counts.merge(reactomeId, stoi == null || stoi < 1 ? 1 : stoi, Integer::sum);
        }
    }

    private static void addLayoutNodes(JsonNode list, Map<Long, Long> nodeIdToReactomeId, Set<Long> ids) {
        for (JsonNode entry : list) {
            Long reactomeId = nodeIdToReactomeId.get(entry.path("id").asLong());
            if (reactomeId != null)
                ids.add(reactomeId);
        }
    }

    /**
     * What is drawn in a diagram, independent of the file format it was read from.
     */
    static class DrawnDiagram {
        final List<DrawnReaction> reactions = new ArrayList<>();
        // {tail (preceding) event dbId, arrow end (following) event dbId}
        final List<Long[]> flowLines = new ArrayList<>();
    }

    static class DrawnReaction {
        final Long dbId;
        // dbId to stoichiometry
        final Map<Long, Integer> inputs = new HashMap<>();
        final Map<Long, Integer> outputs = new HashMap<>();
        final Set<Long> catalysts = new HashSet<>();
        final Set<Long> activators = new HashSet<>();
        final Set<Long> inhibitors = new HashSet<>();

        DrawnReaction(Long dbId) {
            this.dbId = dbId;
        }
    }

    static class InstanceInfo {
        Long dbId;
        String displayName;
        String schemaClass;
        boolean isEvent;
        final Set<Long> precedingEvents = new LinkedHashSet<>();
        String created;
        String modified;
    }

    private static class PathwayInfo {
        final Long dbId;
        final String displayName;

        PathwayInfo(Long dbId, String displayName) {
            this.dbId = dbId;
            this.displayName = displayName;
        }
    }
}
