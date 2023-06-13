package org.reactome.curation.repository;

import java.util.List;
import java.util.stream.Collectors;

import org.reactome.server.graph.domain.model.DatabaseObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.neo4j.core.Neo4jTemplate;
import org.springframework.stereotype.Repository;

import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * Apparently the default auto-generated code from Neo4jRepository cannot be used for our purpose.
 * It is extreme to load the whole reference graph for a object and cannot find a simple way to control
 * it. Therefore, we may need to write our own cyphery based CRUD operations. All read-only queries
 * should be based on graph-core.
 * We use a class, instead of extending Neo4jRepository interface, to implement this curation repository so that
 * we have a much better control (e.g. handling dbId, etc). This is a better approach.
 * @author wug
 *
 */
@Repository
@Data
public class CurationRepository {
    
    // A list of static queries we will use
    private final String MAX_DB_ID_QUERY = "MATCH (n:DatabaseObject) RETURN MAX(n.dbId)";
    private final String LIST_CLASSES_QUERY = "MATCH (d:DatabaseObject) RETURN DISTINCT d.schemaClass";
    
//    @Autowired
    private Neo4jClient neo4jClient;
//    @Autowired
    private Neo4jTemplate neo4jTemplate;
    
    // We will handle dbId at the Java layer for performance reason and easy control.
    // Pay attention to this since it is probably the utmost important information to avoid database collapse!!!
    private Long maxDbId;
    
    public CurationRepository(Neo4jClient neo4jClient, Neo4jTemplate neo4jTemplate) {
        this.neo4jClient = neo4jClient;
        this.neo4jTemplate = neo4jTemplate;
        // Some house keeping when the repository starts
        createDbIdIndex();
        maxDbId = getMaxDbId();
    }
    
    /**
     * Get the next dbId that can be used to create a new DatabaseObject.
     * This is a synchronized method so that it can be accessed by one thread only 
     * to avoid any id conflict.
     * @return
     */
    public synchronized Long nextDbId() {
        return ++maxDbId;
    }
    
    private Long getMaxDbId() {
        return neo4jClient.query(MAX_DB_ID_QUERY).fetchAs(Long.class).one().get();
    }
    
    /**
     * Save the passed object.
     * @param obj
     * @return
     */
    public Long save(DatabaseObject obj) {
        if (obj.getDbId() == null) 
            obj.setDbId(nextDbId());
        DatabaseObject saved = neo4jTemplate.save(obj);
        return saved.getDbId();
    }
    
    /**
     * Get the list of class names in the database.
     * @return
     */
    public List<String> getSchemaClasses() {
        return neo4jClient.query(LIST_CLASSES_QUERY).fetchAs(String.class).all().stream().collect(Collectors.toList());
    }
    
    /**
     * Use this method to create index for DatabaseObject's DB_ID. 
     */
    public void createDbIdIndex() {
        // This should be called once so the query is kept here
        String query = "CREATE INDEX db_id_index IF NOT EXISTS FOR (n:DatabaseObject) ON (n.dbId)";
        neo4jClient.query(query).run(); // Nothing is needed but still need to get something. Otherwise Cypher is not sent.
    }

}
