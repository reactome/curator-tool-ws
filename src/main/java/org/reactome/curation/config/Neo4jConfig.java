package org.reactome.curation.config;

import org.reactome.server.graph.aop.LazyFetchAspect;
import org.reactome.server.graph.repository.AdvancedDatabaseObjectRepository;
import org.reactome.server.graph.service.AdvancedDatabaseObjectService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

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
@ComponentScan({"org.reactome.server.graph.repository", "org.reactome.curation.repository"})
//@EnableNeo4jRepositories({"org.reactome.server.graph.repository", "org.reactome.curation.repository"})
public class Neo4jConfig {
    
//    @Autowired
//    private AdvancedDatabaseObjectRepository repo;
    
//    @Bean
//    LazyFetchAspect getLazyFetchAspect() {
//        LazyFetchAspect aspect = LazyFetchAspect.aspectOf();
//        aspect.setEnableAOP(true);
//        return aspect;
//    }
    
}
