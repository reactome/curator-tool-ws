package org.reactome.curation.repository;

import org.reactome.server.graph.domain.model.DatabaseObject;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

/**
 * Apparently the default auto-generated code from Neo4jRepository cannot be used for our purpose.
 * It is extreme to load the whole reference graph for a object and cannot find a simple way to control
 * it. Therefore, we may need to write our own cyphery based CRUD operations. All read-only queries
 * should be based on graph-core.
 * @author wug
 *
 */
public interface CurationRepository extends Neo4jRepository<DatabaseObject, Long>{
    
    // This is just a test. There is no need to be here and don't use this one
    // Use the Repositories implemented in graph-core for all queries related stuff.
    @Query("MATCH (a:DatabaseObject{dbId:$dbId})-[r]-(m) RETURN a, COLLECT(r), COLLECT(m)")
    public DatabaseObject findByDbId(Long dbId);

}
