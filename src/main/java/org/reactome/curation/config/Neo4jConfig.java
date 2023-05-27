package org.reactome.curation.config;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.reactome.curation.model.CurationAttribute;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    
}
