package org.reactome.curation.repository;

import java.util.List;

import org.reactome.server.graph.domain.model.DatabaseObject;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

/**
 * Apparently the default auto-generated code from Neo4jRepository cannot be used for our purpose.
 * It is extreme to load the whole reference graph for a object and cannot find a simple way to control
 * it. Therefore, we may need to write our own cyphery based CRUD operations. All read-only queries
 * should be based on graph-core.
 * @author wug
 *
 */
public interface CurationRepository extends Neo4jRepository<DatabaseObject, Long> {
    
    // This is just a test. There is no need to be here and don't use this one
    // Use the Repositories implemented in graph-core for all queries related stuff.
    @Query("MATCH (a:DatabaseObject{dbId:$dbId})-[r]-(m) RETURN a, COLLECT(r), COLLECT(m)")
    public DatabaseObject findByDbId(Long dbId);

    /**
     * To create the performance of this query, add a range index to dbId using the following 
     * CREATE INDEX db_id_index IF NOT EXISTS FOR (n:DatabaseObject) ON (n.dbId)
     * The client should call createDbIdIndex() function at least once.
     * @return
     */
    @Query("MATCH (n:DatabaseObject) RETURN MAX(n.dbId)") // Make a node reference instead
    Long getMaxDbId();
    
    /**
     * Use this method to create index for DatabaseObject's DB_ID. 
     */
    @Query("CREATE INDEX db_id_index IF NOT EXISTS FOR (n:DatabaseObject) ON (n.dbId)")
    void createDbIdIndex(); 
    
    @Query("MATCH (d:DatabaseObject) RETURN DISTINCT d.schemaClass")
    List<String> getSchemaClasses();

}
