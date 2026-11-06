package org.reactome.curation.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.reactome.curation.model.ReactionParticipant;
import org.reactome.curation.model.ReactionRegulator;
import org.reactome.curation.model.ReactionStructure;
import org.reactome.curation.qa.DiagramSyncHelper.DrawnDiagram;
import org.reactome.curation.qa.DiagramSyncHelper.DrawnReaction;
import org.reactome.curation.qa.DiagramSyncHelper.InstanceInfo;
import org.reactome.curation.qa.model.DiagramSyncIssue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for DiagramSyncHelper that need no database: parsing of the layout JSON and the
 * comparison against database values.
 */
class DiagramSyncHelperTest {

    // Reaction 100: input 1 (x2), output 2, catalyst 3, inhibitor 4. Pathways 200 and 201, Reaction 100.
    // FlowLines join nodes only: pathway 200 -> pathway 201 and entity 2 -> pathway 201.
    private static final String LAYOUT = String.join("\n",
            "{",
            "  \"nodes\": [",
            "    {\"id\": 10, \"reactomeId\": 1, \"connectors\": [{\"edgeId\": 50, \"type\": \"INPUT\", \"stoichiometry\": {\"value\": 2}}]},",
            "    {\"id\": 11, \"reactomeId\": 2, \"connectors\": [{\"edgeId\": 50, \"type\": \"OUTPUT\", \"stoichiometry\": {\"value\": 1}}]},",
            "    {\"id\": 12, \"reactomeId\": 3, \"connectors\": [{\"edgeId\": 50, \"type\": \"CATALYST\"}]},",
            "    {\"id\": 13, \"reactomeId\": 4, \"connectors\": [{\"edgeId\": 50, \"type\": \"INHIBITOR\"}]},",
            "    {\"id\": 20, \"reactomeId\": 200, \"renderableClass\": \"ProcessNode\"},",
            "    {\"id\": 21, \"reactomeId\": 201, \"renderableClass\": \"ProcessNode\"}",
            "  ],",
            "  \"edges\": [",
            "    {\"id\": 50, \"reactomeId\": 100, \"renderableClass\": \"Reaction\",",
            "     \"inputs\": [{\"id\": 10}], \"outputs\": [{\"id\": 11}],",
            "     \"catalysts\": [{\"id\": 12}], \"inhibitors\": [{\"id\": 13}]}",
            "  ],",
            "  \"links\": [",
            "    {\"id\": 60, \"renderableClass\": \"FlowLine\", \"inputs\": [{\"id\": 20}], \"outputs\": [{\"id\": 21}]},",
            "    {\"id\": 62, \"renderableClass\": \"FlowLine\", \"inputs\": [{\"id\": 11}], \"outputs\": [{\"id\": 21}]},",
            "    {\"id\": 63, \"renderableClass\": \"EntitySetAndMemberLink\", \"inputs\": [{\"id\": 10}], \"outputs\": [{\"id\": 11}]}",
            "  ]",
            "}");

    @Test
    void parsesLayoutJson() throws Exception {
        DrawnDiagram drawn = DiagramSyncHelper.fromLayoutJson(parse(LAYOUT));
        assertEquals(1, drawn.reactions.size());
        DrawnReaction reaction = drawn.reactions.get(0);
        assertEquals(100L, reaction.dbId);
        assertEquals(Map.of(1L, 2), reaction.inputs);
        assertEquals(Map.of(2L, 1), reaction.outputs);
        assertEquals(java.util.Set.of(3L), reaction.catalysts);
        assertTrue(reaction.activators.isEmpty());
        assertEquals(java.util.Set.of(4L), reaction.inhibitors);
        // Both FlowLines are kept here. The entity -> pathway one is filtered out later, in compare().
        // The EntitySetAndMemberLink is not a FlowLine.
        assertEquals(2, drawn.flowLines.size());
        assertEquals(200L, drawn.flowLines.get(0)[0]);
        assertEquals(201L, drawn.flowLines.get(0)[1]);
        assertEquals(2L, drawn.flowLines.get(1)[0]);
        assertEquals(201L, drawn.flowLines.get(1)[1]);
    }

    @Test
    void reactionInSync() {
        assertNull(DiagramSyncHelper.compareReaction(drawnReaction(), structure()));
    }

    @Test
    void reactionOutOfSync() {
        DrawnReaction reaction = drawnReaction();
        reaction.inputs.put(1L, 1); // Stoichiometry differs
        assertEquals("input out of sync", DiagramSyncHelper.compareReaction(reaction, structure()));

        reaction = drawnReaction();
        reaction.inhibitors.clear();
        reaction.activators.add(4L); // Drawn as an activator, annotated as a negative regulation
        assertEquals("activator out of sync", DiagramSyncHelper.compareReaction(reaction, structure()));

        reaction = drawnReaction();
        reaction.catalysts.clear();
        assertEquals("catalyst out of sync", DiagramSyncHelper.compareReaction(reaction, structure()));
    }

    @Test
    void flowLineMissingPrecedingEvent() {
        DrawnDiagram drawn = new DrawnDiagram();
        drawn.flowLines.add(new Long[] {200L, 201L}); // Pathway to pathway, not annotated
        drawn.flowLines.add(new Long[] {201L, 202L}); // Pathway to pathway, annotated
        drawn.flowLines.add(new Long[] {2L, 202L});   // Entity to pathway, skipped
        drawn.flowLines.add(new Long[] {202L, 202L}); // A cycle drawn on one node, skipped
        Map<Long, InstanceInfo> infos = new HashMap<>();
        infos.put(200L, event(200L, "Pathway"));
        infos.put(201L, event(201L, "Pathway"));
        InstanceInfo p202 = event(202L, "Pathway");
        p202.precedingEvents.add(201L);
        infos.put(202L, p202);
        infos.put(2L, entity(2L));

        List<DiagramSyncIssue> issues = DiagramSyncHelper.compare(drawn, new HashMap<>(), infos);
        assertEquals(1, issues.size());
        DiagramSyncIssue issue = issues.get(0);
        assertEquals(201L, issue.getEventDbId());
        assertEquals("Pathway", issue.getEventSchemaClass());
        assertEquals("precedingEvent out of sync: missing 200", issue.getIssue());
        assertEquals(200L, issue.getMissingPrecedingEventDbId());
    }

    @Test
    void reactionIssueIsKeptOverFlowLineIssue() {
        DrawnDiagram drawn = new DrawnDiagram();
        DrawnReaction reaction = drawnReaction();
        reaction.outputs.clear();
        drawn.reactions.add(reaction);
        drawn.flowLines.add(new Long[] {200L, 100L});
        Map<Long, InstanceInfo> infos = new HashMap<>();
        infos.put(100L, event(100L, "Reaction"));
        infos.put(200L, event(200L, "Pathway"));

        List<DiagramSyncIssue> issues = DiagramSyncHelper.compare(drawn, Map.of(100L, structure()), infos);
        assertEquals(1, issues.size());
        assertEquals(100L, issues.get(0).getEventDbId());
        assertEquals("output out of sync", issues.get(0).getIssue());
        assertNull(issues.get(0).getMissingPrecedingEventDbId());
    }

    private DrawnReaction drawnReaction() {
        DrawnReaction reaction = new DrawnReaction(100L);
        reaction.inputs.put(1L, 2);
        reaction.outputs.put(2L, 1);
        reaction.catalysts.add(3L);
        reaction.inhibitors.add(4L);
        return reaction;
    }

    private ReactionStructure structure() {
        List<ReactionParticipant> inputs = new ArrayList<>(List.of(new ReactionParticipant(1L, 2)));
        List<ReactionParticipant> outputs = new ArrayList<>(List.of(new ReactionParticipant(2L, 1)));
        List<ReactionRegulator> regulators = List.of(
                new ReactionRegulator(4L, List.of("DatabaseObject", "Regulation", "NegativeRegulation")));
        return new ReactionStructure(100L, inputs, outputs, List.of(3L), regulators, "Reaction");
    }

    private InstanceInfo event(Long dbId, String schemaClass) {
        InstanceInfo info = new InstanceInfo();
        info.dbId = dbId;
        info.displayName = schemaClass + " " + dbId;
        info.schemaClass = schemaClass;
        info.isEvent = true;
        return info;
    }

    private InstanceInfo entity(Long dbId) {
        InstanceInfo info = new InstanceInfo();
        info.dbId = dbId;
        info.schemaClass = "SimpleEntity";
        info.isEvent = false;
        return info;
    }

    private JsonNode parse(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }
}
