package org.reactome.curation.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.exceptions.DatabaseObjectNotFoundException;
import org.reactome.curation.model.CurationAttribute.DefiningAttributeValue;
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
            // However, it does happen for InstanceEdit class. May need to check the data and update it. For now, just log a warning.
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
     * @param neo4jClient
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
                + "OPTIONAL MATCH (inst)<-[dup:modifiedList]-(ie) "
                + "WITH inst, ie WHERE dup IS NULL "
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
                        "OPTIONAL MATCH (e)<-[dup:structureModified]-(ie) " +
                        "WITH e, ie WHERE dup IS NULL " +
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
    
    /**
     * Build a Cypher query to find existing instances that match a new instance based on defining attributes.
     * In Reactome's data model, defining attributes determine if two instances should be considered identical.
     * There are two types of defining attributes:
     * - ALL_DEFINING: All values must match
     * - ANY_DEFINING: At least one value must match
     * 
     * @param schemaClass the schema class name (e.g., "Pathway", "Reaction")
     * @param definingAttributes map of attribute names to their values and defining types
     * @param neo4jClient the Neo4j client for executing queries
     * @return a list of dbIds of matching instances, or empty list if no matches found
     * 
     * Example usage:
     * <pre>
     * Map&lt;String, DefiningAttributeValue&gt; attributes = new HashMap&lt;&gt;();
     * attributes.put("name", new DefiningAttributeValue("MyPathway", DefiningType.ALL_DEFINING, false));
     * attributes.put("species", new DefiningAttributeValue(48887L, DefiningType.ALL_DEFINING, true));
     * List&lt;Long&gt; matchingDbIds = queryUtilities.findMatchingInstancesByDefiningAttributes(
     *     "Pathway", attributes, neo4jClient);
     * </pre>
     */
    public List<Long> findMatchingInstancesByDefiningAttributes(
            String schemaClass,
            Map<String, DefiningAttributeValue> definingAttributes,
            Neo4jClient neo4jClient) {
        
        if (definingAttributes == null || definingAttributes.isEmpty()) {
            logger.warn("No defining attributes provided for matching");
            return new ArrayList<>();
        }
        
        // Build the Cypher query
        StringBuilder matchBuilder  = new StringBuilder();
        matchBuilder.append("MATCH (n:").append(schemaClass).append(") ");
        
        // Separate ALL_DEFINING and ANY_DEFINING attributes
        List<String> allDefiningClauses = new ArrayList<>();
        List<String> anyDefiningClauses = new ArrayList<>();
        Map<String, Object> parameters = new HashMap<>();
        // Extra MATCH clauses needed for reference attributes (cannot introduce
        // new variables inside a WHERE pattern expression in Neo4j).
        List<String> refMatchClauses = new ArrayList<>();
        
        for (Map.Entry<String, DefiningAttributeValue> entry : definingAttributes.entrySet()) {
            String attName = entry.getKey();
            DefiningAttributeValue defValue = entry.getValue();
            Object value = defValue.getValue();
            boolean isReference = defValue.isReference();
            
            if (value == null) {
                continue; // Skip null values
            }
            
            String whereClause;
            if (isReference) {
                String paramName = attName + "_dbId";
                boolean isAll = defValue.getDefiningType() == org.reactome.curation.model.CurationAttribute.DefiningType.ALL_DEFINING;
                if (isAll) {
                    if (value instanceof Collection) {
                        // ALL_DEFINING with multiple reference values: the candidate's
                        // relationships for this attribute must equal the given values
                        // exactly, including multiplicity (e.g. a homodimer-like list with
                        // the same target dbId twice must match a candidate with exactly two
                        // edges to that target, not one). A MATCH clause per list entry
                        // cannot enforce this: separate MATCH statements are not required to
                        // bind to distinct relationships, so two "dbId: X" MATCH clauses can
                        // both silently reuse the same single edge. Instead, check size() per
                        // DISTINCT value (exact per-value multiplicity) plus a total size()
                        // check (rejects extra, unlisted relationships) - both entirely in the
                        // WHERE clause, so no MATCH-clause aliasing/cartesian concerns.
                        // A bare "size(pattern)" is deprecated by Neo4j in favor of a pattern
                        // comprehension ("size([pattern | expr])"), which is what's used below.
                        Collection<?> values = (Collection<?>) value;
                        if (values.isEmpty()) {
                            continue;
                        }
                        Map<Object, Integer> countByValue = new HashMap<>();
                        for (Object v : values) {
                            countByValue.merge(v, 1, Integer::sum);
                        }
                        List<String> perValueClauses = new ArrayList<>();
                        int i = 0;
                        for (Map.Entry<Object, Integer> countEntry : countByValue.entrySet()) {
                            String valueParamName = paramName + "_" + i;
                            String valueCountParamName = valueParamName + "_count";
                            parameters.put(valueParamName, countEntry.getKey());
                            parameters.put(valueCountParamName, countEntry.getValue());
                            perValueClauses.add("size([(n)-[:" + attName + "]->(:DatabaseObject {dbId: $"
                                    + valueParamName + "}) | 1]) = $" + valueCountParamName);
                            i++;
                        }
                        String totalCountParamName = paramName + "_totalCount";
                        parameters.put(totalCountParamName, values.size());
                        perValueClauses.add("size([(n)-[:" + attName + "]->(:DatabaseObject) | 1]) = $" + totalCountParamName);
                        whereClause = "(" + String.join(" AND ", perValueClauses) + ")";
                    } else {
                        // ALL_DEFINING: a required MATCH guarantees the relationship exists.
                        // Use a unique alias per attribute so multiple ALL_DEFINING references
                        // can coexist in the same query.
                        parameters.put(paramName, value);
                        String refAlias = "ref_" + attName;
                        refMatchClauses.add("MATCH (n)-[:" + attName + "]->(" + refAlias + ":DatabaseObject)");
                        whereClause = refAlias + ".dbId = $" + paramName;
                    }
                } else {
                    parameters.put(paramName, value);
                    // ANY_DEFINING: use an EXISTS subquery so the check is a true post-filter
                    // on the outer row.  OPTIONAL MATCH + WHERE does NOT work here: Neo4j
                    // treats a WHERE placed directly after OPTIONAL MATCH as part of the
                    // optional pattern — when the condition fails, Neo4j returns null for
                    // the alias instead of excluding the outer row, so ALL nodes pass through.
                    whereClause = value instanceof Collection
                            ? "EXISTS { (n)-[:" + attName + "]->(ref:DatabaseObject) WHERE ref.dbId IN $" + paramName + " }"
                            : "EXISTS { (n)-[:" + attName + "]->(ref:DatabaseObject) WHERE ref.dbId = $" + paramName + " }";
                }
            } else {
                // For simple attributes, match directly
                String paramName = attName + "_value";
                
                if (value instanceof Collection) {
                    // For multi-valued attributes, ALL_DEFINING requires every provided
                    // value to be present, ANY_DEFINING requires at least one.
                    Collection<?> values = (Collection<?>) value;
                    if (values.isEmpty()) {
                        continue;
                    }
                    boolean isAllNonRef = defValue.getDefiningType() == org.reactome.curation.model.CurationAttribute.DefiningType.ALL_DEFINING;
                    whereClause = (isAllNonRef ? "ALL" : "ANY") + "(v IN $" + paramName + " WHERE v IN n." + attName + ")";
                    parameters.put(paramName, new ArrayList<>(values));
                } else {
                    // For single-valued attributes
                    whereClause = "n." + attName + " = $" + paramName;
                    parameters.put(paramName, value);
                }
            }
            
            // Add to appropriate list based on defining type
            if (defValue.getDefiningType() == org.reactome.curation.model.CurationAttribute.DefiningType.ALL_DEFINING) {
                allDefiningClauses.add(whereClause);
            } else if (defValue.getDefiningType() == org.reactome.curation.model.CurationAttribute.DefiningType.ANY_DEFINING) {
                anyDefiningClauses.add(whereClause);
            }
        }
        
        // Append any reference MATCH clauses after the initial node MATCH
        for (String refMatch : refMatchClauses) {
            matchBuilder.append(refMatch).append(" ");
        }

        // Build WHERE clause
        List<String> whereClauses = new ArrayList<>();
        
        // ALL_DEFINING: All must match (AND condition)
        if (!allDefiningClauses.isEmpty()) {
            whereClauses.add("(" + String.join(" AND ", allDefiningClauses) + ")");
        }
        
        // ANY_DEFINING: At least one must match (OR condition)
        if (!anyDefiningClauses.isEmpty()) {
            whereClauses.add("(" + String.join(" OR ", anyDefiningClauses) + ")");
        }
        
        StringBuilder queryBuilder = new StringBuilder(matchBuilder);
        if (!whereClauses.isEmpty()) {
            queryBuilder.append("WHERE ");
            queryBuilder.append(String.join(" AND ", whereClauses));
            queryBuilder.append(" ");
        }
        
        queryBuilder.append("RETURN n.dbId AS dbId");
        
        String cypher = queryBuilder.toString();
        logger.debug("Finding matching instances with query: " + cypher);
        logger.debug("Parameters: " + parameters);
        
        // Execute query
        return neo4jClient.query(cypher)
                .bindAll(parameters)
                .fetchAs(Long.class)
                .mappedBy((typeSystem, record) -> record.get("dbId").asLong())
                .all()
                .stream()
                .collect(Collectors.toList());
    }

}