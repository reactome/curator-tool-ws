package org.reactome.curation.qa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.reactome.curation.exceptions.DatabaseObjectNotFoundException;
import org.reactome.curation.qa.model.ReactionImbalance;
import org.reactome.curation.qa.model.ReactionImbalanceReport;
import org.reactome.curation.qa.model.ReferenceImbalance;
import org.springframework.data.neo4j.core.Neo4jClient;

/**
 * Check Reactions for imbalance between inputs and outputs. This is a port of org.gk.qualityCheck.ImbalanceCheck
 * in the CuratorTool project (itself modified from Esther's QA_collection.pm script).
 *
 * Inputs and outputs are broken down to EntityWithAccessionedSequences by following hasComponent, hasMember,
 * hasCandidate and repeatedUnit. Each EWAS is then mapped to its ReferenceGeneProduct, and a reaction is
 * imbalanced if the counts of ReferenceGeneProducts differ between the two sides. Polymers attached to an EWAS
 * as GroupModifiedResidue modifications (e.g. polyubiquitin) are counted too, minUnitCount times.
 *
 * Note: In the graph database stoichiometry is a relationship property instead of a repeated value as in
 * MySQL. Therefore, the count of each EWAS is the product of stoichiometries along the path from the reaction.
 * EntitySet members and candidates are all counted, the same as the desktop version.
 */
public class ReactionImbalanceHelper {
    // Reactions (not other ReactionLikeEvents, the same as the desktop version) contained by an Event.
    // hasEvent*0.. includes the Event itself in case it is a Reaction.
    private static final String REACTION_IDS_QUERY = ""
            + "MATCH (e:Event {dbId: $dbId}) "
            + "OPTIONAL MATCH (e)-[:hasEvent*0..]->(r:Reaction) "
            + "RETURN e.displayName AS displayName, collect(DISTINCT r.dbId) AS reactionDbIds";

    // EWASes used by inputs and outputs, with their count (product of stoichiometries along all paths).
    // A variable-length path never repeats a relationship, so a self-containing set cannot loop forever.
    private static final String EWAS_QUERY = ""
            + "UNWIND $dbIds AS rid "
            + "MATCH (r:Reaction {dbId: rid}) "
            + "MATCH p = (r)-[:input|output]->(:PhysicalEntity)"
            + "-[:hasComponent|hasMember|hasCandidate|repeatedUnit*0..]->(ewas:EntityWithAccessionedSequence) "
            + "WITH r, type(relationships(p)[0]) AS side, ewas, "
            + "reduce(s = 1, rel IN relationships(p) | s * coalesce(rel.stoichiometry, 1)) AS mult "
            + "WITH r, side, ewas, sum(mult) AS count "
            + "OPTIONAL MATCH (ewas)-[:referenceEntity]->(ref:ReferenceGeneProduct) "
            + "RETURN r.dbId AS reactionDbId, r.displayName AS reactionDisplayName, side, "
            + "ewas.dbId AS ewasDbId, ewas.startCoordinate AS start, ewas.endCoordinate AS end, "
            + "ref.dbId AS refDbId, ref.identifier AS identifier, ref.displayName AS refDisplayName, count";

    // ReferenceGeneProducts contributed by a single copy of an EWAS via polymer modifications
    private static final String MODIFICATION_QUERY = ""
            + "UNWIND $ewasDbIds AS eid "
            + "MATCH (:EntityWithAccessionedSequence {dbId: eid})-[:hasModifiedResidue]->(:GroupModifiedResidue)"
            + "-[:modification]->(poly:Polymer) "
            + "MATCH p = (poly)-[:hasComponent|hasMember|hasCandidate|repeatedUnit*]->(:EntityWithAccessionedSequence)"
            + "-[:referenceEntity]->(ref:ReferenceGeneProduct) "
            + "WITH eid, ref, coalesce(poly.minUnitCount, 1) * "
            + "reduce(s = 1, rel IN relationships(p) | s * coalesce(rel.stoichiometry, 1)) AS mult "
            + "RETURN eid AS ewasDbId, ref.dbId AS refDbId, ref.identifier AS identifier, "
            + "ref.displayName AS refDisplayName, sum(mult) AS count";

    // The latest InstanceEdit: modified holds the latest one. modifiedList and created are fallbacks.
    private static final String LAST_AUTHOR_QUERY = ""
            + "UNWIND $dbIds AS id "
            + "MATCH (r:DatabaseObject {dbId: id}) "
            + "OPTIONAL MATCH (r)<-[ml:modifiedList]-(m:InstanceEdit) "
            + "WITH r, m ORDER BY ml.order DESC "
            + "WITH r, collect(m.displayName)[0] AS lastInList "
            + "OPTIONAL MATCH (r)<-[:modified]-(lm:InstanceEdit) "
            + "WITH r, lastInList, collect(lm.displayName)[0] AS lastModified "
            + "OPTIONAL MATCH (r)<-[:created]-(c:InstanceEdit) "
            + "WITH r, lastInList, lastModified, collect(c.displayName)[0] AS created "
            + "RETURN r.dbId AS dbId, coalesce(lastModified, lastInList, created) AS lastAuthor";

    private final Neo4jClient neo4jClient;

    public ReactionImbalanceHelper(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * Check all Reactions contained by an Event (a Pathway, or a Reaction itself).
     * @param eventDbId
     * @return
     */
    public ReactionImbalanceReport checkEvent(Long eventDbId) {
        Map<String, Object> row = neo4jClient.query(REACTION_IDS_QUERY)
                .bind(eventDbId).to("dbId")
                .fetch()
                .one()
                .orElseThrow(() -> new DatabaseObjectNotFoundException(eventDbId));
        List<Long> reactionDbIds = new ArrayList<>();
        for (Object id : (Collection<?>) row.get("reactionDbIds"))
            reactionDbIds.add(((Number) id).longValue());
        ReactionImbalanceReport report = new ReactionImbalanceReport();
        report.setEventDbId(eventDbId);
        report.setEventDisplayName((String) row.get("displayName"));
        report.setCheckedReactions(reactionDbIds.size());
        report.setReactions(checkReactions(reactionDbIds));
        return report;
    }

    /**
     * Check a list of Reactions. Only imbalanced ones are returned, sorted by display name.
     * @param reactionDbIds
     * @return
     */
    public List<ReactionImbalance> checkReactions(Collection<Long> reactionDbIds) {
        if (reactionDbIds.isEmpty())
            return new ArrayList<>();
        Collection<Map<String, Object>> rows = neo4jClient.query(EWAS_QUERY)
                .bind(reactionDbIds).to("dbIds")
                .fetch()
                .all();
        Map<Long, ReactionParticipants> id2participants = new LinkedHashMap<>();
        Set<Long> ewasDbIds = new HashSet<>();
        for (Map<String, Object> map : rows) {
            Long reactionDbId = toLong(map.get("reactionDbId"));
            ReactionParticipants participants = id2participants.computeIfAbsent(reactionDbId,
                    key -> new ReactionParticipants(key, (String) map.get("reactionDisplayName")));
            EwasUsage usage = new EwasUsage();
            usage.isInput = "input".equals(map.get("side"));
            usage.ewasDbId = toLong(map.get("ewasDbId"));
            usage.start = toInteger(map.get("start"));
            usage.end = toInteger(map.get("end"));
            usage.count = toLong(map.get("count")).intValue();
            Long refDbId = toLong(map.get("refDbId"));
            if (refDbId != null) {
                usage.ref = participants.getRef(refDbId,
                        (String) map.get("identifier"),
                        (String) map.get("refDisplayName"));
            }
            participants.usages.add(usage);
            ewasDbIds.add(usage.ewasDbId);
        }
        Map<Long, List<RefCount>> ewas2modRefs = fetchModificationRefs(ewasDbIds);
        List<ReactionImbalance> rtn = new ArrayList<>();
        for (ReactionParticipants participants : id2participants.values()) {
            ReactionImbalance imbalance = checkReaction(participants, ewas2modRefs);
            if (imbalance != null)
                rtn.add(imbalance);
        }
        fillLastAuthors(rtn);
        rtn.sort(Comparator.comparing(ReactionImbalance::getDisplayName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return rtn;
    }

    private ReactionImbalance checkReaction(ReactionParticipants participants,
                                            Map<Long, List<RefCount>> ewas2modRefs) {
        // refDbId -> {input count, output count}
        Map<Long, int[]> ref2counts = new HashMap<>();
        for (EwasUsage usage : participants.usages) {
            int index = usage.isInput ? 0 : 1;
            if (usage.ref != null)
                ref2counts.computeIfAbsent(usage.ref.dbId, key -> new int[2])[index] += usage.count;
            List<RefCount> modRefs = ewas2modRefs.get(usage.ewasDbId);
            if (modRefs == null)
                continue;
            for (RefCount modRef : modRefs) {
                Ref ref = participants.getRef(modRef.ref.dbId, modRef.ref.identifier, modRef.ref.displayName);
                ref2counts.computeIfAbsent(ref.dbId, key -> new int[2])[index] += usage.count * modRef.count;
            }
        }
        List<ReferenceImbalance> imbalances = new ArrayList<>();
        for (Long refDbId : ref2counts.keySet()) {
            int[] counts = ref2counts.get(refDbId);
            if (counts[0] == counts[1])
                continue;
            Ref ref = participants.id2ref.get(refDbId);
            imbalances.add(new ReferenceImbalance(refDbId, ref.identifier, ref.displayName, counts[0], counts[1]));
        }
        if (imbalances.isEmpty())
            return null;
        imbalances.sort(Comparator.comparing(ReferenceImbalance::getDisplayName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        ReactionImbalance rtn = new ReactionImbalance();
        rtn.setDbId(participants.dbId);
        rtn.setDisplayName(participants.displayName);
        rtn.setImbalances(imbalances);
        rtn.setCleavage(isCleavage(participants, imbalances));
        return rtn;
    }

    /**
     * Ported from ImbalanceCheck.checkCleavage() in the desktop version: a reaction is a cleavage if every
     * imbalanced protein with a coordinate beyond 1 is used by EWASes with more than one end coordinate.
     */
    private boolean isCleavage(ReactionParticipants participants, List<ReferenceImbalance> imbalances) {
        Set<Long> imbalancedRefIds = new HashSet<>();
        imbalances.forEach(imbalance -> imbalancedRefIds.add(imbalance.getRefDbId()));
        // An EWAS may be used in both sides. Only need to check it once.
        Set<Long> checkedEwasIds = new HashSet<>();
        Map<Long, Set<Integer>> ref2ends = new HashMap<>();
        for (EwasUsage usage : participants.usages) {
            if (usage.ref == null || !imbalancedRefIds.contains(usage.ref.dbId))
                continue;
            if (!checkedEwasIds.add(usage.ewasDbId))
                continue;
            boolean possibleCleaved = (usage.end != null && usage.end > 1) ||
                                      (usage.start != null && usage.start > 1);
            // Still take the end value even it is null or 1, the same as the desktop version
            if (possibleCleaved)
                ref2ends.computeIfAbsent(usage.ref.dbId, key -> new HashSet<>()).add(usage.end);
        }
        if (ref2ends.isEmpty())
            return false;
        for (Set<Integer> ends : ref2ends.values()) {
            if (ends.size() == 1) // The input and output have the same end coordinate.
                return false;
        }
        return true;
    }

    private Map<Long, List<RefCount>> fetchModificationRefs(Collection<Long> ewasDbIds) {
        Map<Long, List<RefCount>> rtn = new HashMap<>();
        if (ewasDbIds.isEmpty())
            return rtn;
        Collection<Map<String, Object>> rows = neo4jClient.query(MODIFICATION_QUERY)
                .bind(ewasDbIds).to("ewasDbIds")
                .fetch()
                .all();
        for (Map<String, Object> map : rows) {
            RefCount refCount = new RefCount();
            refCount.ref = new Ref(toLong(map.get("refDbId")),
                    (String) map.get("identifier"),
                    (String) map.get("refDisplayName"));
            refCount.count = toLong(map.get("count")).intValue();
            rtn.computeIfAbsent(toLong(map.get("ewasDbId")), key -> new ArrayList<>()).add(refCount);
        }
        return rtn;
    }

    private void fillLastAuthors(List<ReactionImbalance> imbalances) {
        if (imbalances.isEmpty())
            return;
        List<Long> dbIds = new ArrayList<>();
        imbalances.forEach(imbalance -> dbIds.add(imbalance.getDbId()));
        Collection<Map<String, Object>> rows = neo4jClient.query(LAST_AUTHOR_QUERY)
                .bind(dbIds).to("dbIds")
                .fetch()
                .all();
        Map<Long, String> id2author = new HashMap<>();
        for (Map<String, Object> map : rows)
            id2author.put(toLong(map.get("dbId")), (String) map.get("lastAuthor"));
        imbalances.forEach(imbalance -> imbalance.setLastAuthor(id2author.get(imbalance.getDbId())));
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Integer toInteger(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static class Ref {
        final Long dbId;
        final String identifier;
        final String displayName;

        Ref(Long dbId, String identifier, String displayName) {
            this.dbId = dbId;
            this.identifier = identifier;
            this.displayName = displayName;
        }
    }

    private static class RefCount {
        Ref ref;
        int count;
    }

    // One EWAS used in one side of a reaction
    private static class EwasUsage {
        boolean isInput;
        Long ewasDbId;
        Integer start;
        Integer end;
        int count;
        Ref ref; // null if the EWAS doesn't refer to a ReferenceGeneProduct
    }

    private static class ReactionParticipants {
        final Long dbId;
        final String displayName;
        final List<EwasUsage> usages = new ArrayList<>();
        final Map<Long, Ref> id2ref = new HashMap<>();

        ReactionParticipants(Long dbId, String displayName) {
            this.dbId = dbId;
            this.displayName = displayName;
        }

        Ref getRef(Long refDbId, String identifier, String displayName) {
            return id2ref.computeIfAbsent(refDbId, key -> new Ref(key, identifier, displayName));
        }
    }

}
