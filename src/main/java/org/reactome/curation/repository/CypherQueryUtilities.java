package org.reactome.curation.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.exceptions.DatabaseObjectNotFoundException;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.InstanceEdit;
import org.reactome.server.graph.domain.model.Species;
import org.reactome.server.graph.domain.model.Taxon;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import lombok.NoArgsConstructor;

/**
 * Group a set of utility methods to help with Cypher queries.
 */
@Component
@NoArgsConstructor
public class CypherQueryUtilities {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(CypherQueryUtilities.class);

    /**
     * Get the dbIds of all contained members via hasMember or hasCandidate for an EntitySet
     * specified by its dbId.
     * @param dbId
     * @param neo4jClient
     * @return
     */
    public Collection<Long> getMemberDbIdsForEntitySet(Long dbId, Neo4jClient neo4jClient) {
        // Use 1.. to exlcude set itself.
        String cypher =
                "MATCH (set:EntitySet {dbId: $dbId}) " +
                        "OPTIONAL MATCH (set)-[:hasMember|hasCandidate*1..]->(member:PhysicalEntity) " +
                        "RETURN DISTINCT member.dbId AS memberId";

        return neo4jClient.query(cypher)
                .bind(dbId).to("dbId")
                .fetchAs(Long.class)
                .mappedBy((typeSystem, record) -> record.get("memberId").asLong())
                .all();
    }

    /**
     * Collection dbIds of ReferenceEntities referred by a PhysicalEntity that is specified by its dbId.
     * @param dbId
     * @param neo4jClient
     * @return
     */
    public Collection<Long> getReferenceEntityDbIdsForPEId(Long dbId, Neo4jClient neo4jClient) {
        // Use 0.. to include pe itself.
        String cypher =
                "MATCH (pe:PhysicalEntity {dbId: $dbId}) " +
                        "OPTIONAL MATCH (pe)-[:hasComponent|hasMember|hasCandidate|repeatedUnit*0..]->(child:PhysicalEntity) " +
                        "WITH DISTINCT coalesce(child, pe) AS entity " +
                        "MATCH (entity)-[:referenceEntity]->(ref:ReferenceEntity) " +
                        "RETURN DISTINCT ref.dbId AS refDbId";

        return neo4jClient.query(cypher)
                .bind(dbId).to("dbId")
                .fetchAs(Long.class)
                .mappedBy((typeSystem, record) -> record.get("refDbId").asLong())
                .all();
    }

    /**
     * Check if a Complex or EntitySet contains any drug via its reference graph by hasMember, hasCandidate,
     * and hasComponent.
     * @param dbId
     * @param neo4jClient
     * @return
     */
    public boolean complexOrSetHasDrug(long dbId, Neo4jClient neo4jClient) {
        String cypher =
                "MATCH (d:DatabaseObject {dbId: $dbId}) " +
                        "WHERE d:Complex OR d:EntitySet " +
                        "OPTIONAL MATCH (d)-[:hasMember|hasCandidate|hasComponent*1..]->(drug:Drug) " +
                        "RETURN COUNT(drug) > 0 AS hasDrug";

        return neo4jClient.query(cypher)
                .bind(dbId).to("dbId")
                .fetchAs(Boolean.class)
                .one()
                .orElse(false);
    }

    /**
     * Use this method to create index for DatabaseObject's DB_ID.
     */
    public void createDbIdIndex(Neo4jClient neo4jClient) {
        // This should be called once so the query is kept here
        String query = "CREATE INDEX db_id_index IF NOT EXISTS FOR (n:DatabaseObject) ON (n.dbId)";
        neo4jClient.query(query).run(); // Nothing is needed but still need to get something. Otherwise Cypher is not
        // sent.
        // Create another index for _displayName for named based search (e.g. contains)
        query = "CREATE TEXT INDEX databaseobject_text_index_displayname IF "
                + "NOT EXISTS FOR (n:DatabaseObject) ON (n.displayName)";
        neo4jClient.query(query).run();
        //        // Create range index for order by displayName
        //        query = "CREATE RANGE INDEX databaseobject_range_index_displayname IF NOT EXISTS for (n:DatabaseObject) on (n.displayName)";
        //        neo4jClient.query(query).run();
        // For node lookup: by creating this index, we limit the search! (try profile in
        // cypher!).
        query = "CREATE LOOKUP INDEX node_label_lookup_index IF NOT EXISTS FOR (n) ON EACH labels(n)";
        neo4jClient.query(query).run();
    }

    /**
     * Query species abbreviation for a given species dbId.
     * 
     * @param speciesDbId
     * @return abbreviation or null if not found
     * @throws Exception
     */
    public String querySpeciesAbbreviation(Long speciesDbId, Neo4jClient neo4jClient) {
        String query = "" +
                "MATCH (s:Taxon) " +
                "WHERE s.dbId = $dbId " +
                "RETURN s.abbreviation AS abbreviation " +
                "LIMIT 1";
        Optional<Map<String, Object>> result = neo4jClient.query(query)
                .bindAll(Map.of("dbId", speciesDbId))
                .fetch().first();
        if (result.isEmpty() || result.get().get("abbreviation") == null)
            throw new DatabaseObjectNotFoundException(speciesDbId);  
        return (String) result.get().get("abbreviation");
    }

    /**
     * This is basically a shortcut of attribute-based search for a pathway diagram. The implementation 
     * may call listInstances(). However, we'd like to support a pathway id based search here. 
     * @param pathwayId
     * @return
     */
    public SimpleInstance fetchPathwayDiagramForPathway(Long pathwayId, Neo4jClient neo4jClient, CurationRepository curationRepository) {
        String query = "" +
                "MATCH (inst:PathwayDiagram)-[:representedPathway]->(p:Pathway) " +
                "WHERE p.dbId =  " + pathwayId + " " +
                "RETURN inst.dbId, inst.displayName, inst.schemaClass " +
                "LIMIT 1";
        Optional<Map<String, Object>> result = neo4jClient.query(query).fetch().first();
        if (result.isEmpty())
            return null;
        SimpleInstance inst = constructInstance(result.get(), ReactomeJavaConstants.PathwayDiagram);
        return inst;
    }

    SimpleInstance constructInstance(Map<String, Object> map, String className) {
        SimpleInstance inst = new SimpleInstance();
        inst.setDbId(Long.parseLong(map.get("inst.dbId").toString()));
        // Use + in case displayName is null
        inst.setDisplayName(map.get("inst.displayName") + "");
        Object schemaClassName = map.get("inst.schemaClass");
        if (schemaClassName != null)
            inst.setSchemaClassName(schemaClassName.toString());
        else {
            inst.setSchemaClassName(className); // Just in case! This should not happen!
            logger.warn("No schemaClass name in the database for instance with dbId = " + inst.getDbId());
        }
        Object refSchemaClass = map.get("inst.ref.schemaClass");
        if (refSchemaClass != null)
            inst.setAttribute("refSchemaClass", refSchemaClass.toString());
        return inst;
    }

    public Set<Taxon> grepSpecies(Long dbId, String followAttributes, String schemaClass, Neo4jClient neo4jClient) {
        String query = String.format("MATCH (container:%s {dbId: %d})\n"
                + "OPTIONAL MATCH (container)-[:species]->(s:Taxon)\n" + "WITH container, s AS containerSpecies\n"
                + "OPTIONAL MATCH (container)-[r:%s*]->(pe:PhysicalEntity)\n"
                + "WITH container, containerSpecies, r, COLLECT(pe) AS containeds\n"
                + "UNWIND containeds AS pe\n"
                + "UNWIND r AS role\n"
                + "OPTIONAL MATCH (pe)-[:species]->(species:Taxon)\n"
                + "WITH container, containerSpecies, COLLECT(DISTINCT species) AS cSpecies, role, pe\n"
                + "UNWIND cSpecies as containedSpecies\n"
                + "RETURN containedSpecies.dbId, containedSpecies.displayName",
                schemaClass, dbId, followAttributes);

        Set<Taxon> speciesSet = new HashSet<>();
        Collection<Map<String, Object>> all = neo4jClient.query(query).fetch().all();
        for (Map<String, Object> map : all) {
            String containedSpeciesDisplayName = (String) map.get("containedSpecies.displayName");
            Long containedSpeciesDbId = (Long) map.get("containedSpecies.dbId");
            // Create a simple instance to model the species and add to map
            Taxon species = new Taxon();
            species.setDbId(containedSpeciesDbId);
            species.setDisplayName(containedSpeciesDisplayName);
            speciesSet.add(species);
        }
        return speciesSet;
    }

    /**
     * Query species for a given DatabaseObject.
     * 
     * @param obj
     * @return Species or null if not found
     * @throws Exception
     */
    public List<Taxon> queryInstanceTaxon(DatabaseObject obj, Neo4jClient neo4jClient) {
        String query = "MATCH (n:DatabaseObject {dbId: $dbId}) " +
                "OPTIONAL MATCH (n)-[:species]->(s:Taxon) " +  // Using Taxon here to be consistent with the data model
                "RETURN s.dbId AS dbId, s.displayName AS displayName, labels(s) AS labels";
        List<Taxon> speciesList = new ArrayList<>();
        neo4jClient.query(query)
        .bind(obj.getDbId()).to("dbId")
        .fetch()
        .all()
        .forEach(record -> {
            Map<String, Object> species = (Map<String, Object>) record;
            if (species != null) {
                @SuppressWarnings("unchecked")
                List<String> labels = (List<String>) species.get("labels");
                Taxon taxon = null;
                if (labels.contains("Species")) {
                    taxon = new Species();
                } else {
                    taxon = new Taxon();
                }
                taxon.setDbId((Long) species.get("dbId"));
                taxon.setDisplayName((String) species.get("displayName"));
                speciesList.add(taxon);
            }
        });
        return speciesList;
    }   

    /**
     * A helper method to add an InstanceEdit to an Instance's modified and modifiedList attributes.
     * Here is what need to be done:
     * 1). Delete the existing modified relationship if there is one.
     * 2). Create a new modified relationship to the passed InstanceEdit.
     * 3). Create a new modifiedList relationship to the passed InstanceEdit with rank = maxRank + 1.
     * @param instance
     * @param ieNode
     * @param ie
     */
    public void addModifiedIE(DatabaseObject instance, 
                              InstanceEdit ie,
                              Neo4jClient neo4jClient) {
        String cypher = ""
                + "MATCH (inst:" + getNodeLabel(instance) + " {dbId: $instanceDbId}) "
                + "OPTIONAL MATCH (inst)<-[r_mod:modified]-(:InstanceEdit) "
                + "DELETE r_mod "
                + "WITH inst "
                + "MATCH (ie:" + getNodeLabel(ie) + " {dbId: $ieDbId}) "
                + "CREATE (inst)<-[:modified]-(ie) "
                + "WITH inst, ie "
                + "OPTIONAL MATCH (inst)<-[prevRel:modifiedList]-(:InstanceEdit) "
                + "WITH inst, ie, coalesce(max(prevRel.order), 0) AS maxOrder "
                + "CREATE (inst)<-[:modifiedList {order: maxOrder + 1}]-(ie) "
                + "RETURN inst, ie";

        neo4jClient.query(cypher)
        .bind(instance.getDbId()).to("instanceDbId")
        .bind(ie.getDbId()).to("ieDbId")
        .run();
    }

    /**
     * Add an existing InstanceEdit to an Event via its structureModified relationship.
     * @param dbObj
     * @param ie
     * @param neo4jClient
     */
    public void downgradeReviewStatusWithStructureChange(DatabaseObject dbObj,
                                                         InstanceEdit ie,
                                                         Neo4jClient neo4jClient) {
        String cypher =
                "OPTIONAL MATCH (e:Event {dbId: $eventDbId}) " +
                        "WITH e WHERE e IS NOT NULL " +
                        "OPTIONAL MATCH (e)-[r:reviewStatus]->(rs:ReviewStatus) " +
                        "OPTIONAL MATCH (e)-[pr:previousReviewStatus]->(:ReviewStatus) " +
                        "WITH e, r, rs, pr, " +
                        "  CASE " +
                        "    WHEN rs.displayName = 'three stars' THEN 'one star' " +
                        "    WHEN rs.displayName IN ['four stars','five stars'] THEN 'two stars' " +
                        "    ELSE rs.displayName " +
                        "  END AS newRsName " +
                        "OPTIONAL MATCH (newRs:ReviewStatus {displayName: newRsName}) " +
                        "FOREACH (_ IN CASE " +
                        "    WHEN rs IS NOT NULL AND rs.displayName <> newRsName THEN [1] " +
                        "    ELSE [] END | " +
                        "    DELETE pr, r " +
                        "    MERGE (e)-[:previousReviewStatus]->(rs) " +
                        "    MERGE (e)-[:reviewStatus]->(newRs) " +
                        ") " +
                        "WITH e " + // Bridge variable after FOREACH
                        "MATCH (ie:InstanceEdit {dbId: $ieDbId}) " +
                        "OPTIONAL MATCH (e)<-[sm:structureModified]-(:InstanceEdit) " +
                        "WITH e, ie, coalesce(max(sm.order), 0) AS maxOrder " +
                        "CREATE (e)<-[:structureModified {order: maxOrder + 1}]-(ie)";

        neo4jClient.query(cypher)
        .bind(dbObj.getDbId()).to("eventDbId")
        .bind(ie.getDbId()).to("ieDbId")
        .run();
    }

    public String getNodeLabel(DatabaseObject obj) {
        // This is a hack for convenience in case a SimpleInstance is used
        // for attribute values
        String clsName = null;
        if (obj instanceof SimpleInstance)
            clsName = DatabaseObject.class.getSimpleName();
        else
            clsName = obj.getClass().getSimpleName();
        int index = clsName.lastIndexOf(".");
        return clsName.substring(index + 1);
    }

    /**
     * A utility to create a unique name for a node
     *
     * @param obj
     * @return
     */
    public String getNodeName(DatabaseObject obj) {
        return "obj_" + obj.getDbId();
    }

}
