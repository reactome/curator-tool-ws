package org.reactome.curation.config;

import javax.annotation.PostConstruct;

import org.reactome.server.graph.utils.ReactomeGraphCore;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Configuration
// The following configuration is copied from content-service
//@EnableScheduling
//@EnableAsync
// The below is needed to make graph return to the correct class
@EntityScan({"org.reactome.server.graph.domain.model"})
//Need the following scan
@ComponentScan({"org.reactome.server.graph.repository", 
                "org.reactome.server.graph.service",
                "org.reactome.curation.repository"})
@EnableNeo4jRepositories({"org.reactome.server.graph.repository", "org.reactome.curation.repository"})
public class Neo4jConfig {

    @Value("${spring.neo4j.uri}")
    private String neo4jUri;

    @Value("${spring.neo4j.authentication.username}")
    private String neo4jUsername;

    @Value("${spring.neo4j.authentication.password}")
    private String neo4jPassword;

    @PostConstruct
    public void initializeReactomeGraphCore() {
        ReactomeGraphCore.initialise(neo4jUri, neo4jUsername, neo4jPassword);
    }

}
