package org.reactome.curation.service;

import org.reactome.curation.repository.CurationRepository;
//import org.reactome.server.graph.aop.LazyFetchAspect;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.repository.AdvancedDatabaseObjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Service
@NoArgsConstructor
//@EnableScheduling
//@EnableAsync
//@EntityScan({"org.reactome.server.graph.domain.model"})
//@EnableNeo4jRepositories({"org.reactome.server.graph.repository", "org.reactome.curation.repository"})
public class CurationService {
    
    @Autowired
    private CurationRepository curationRepository;
    @Autowired
    private AdvancedDatabaseObjectRepository objectRepository;
    
    public DatabaseObject findById(Long dbId) {
       return curationRepository.findByDbId(dbId);
//        return objectRepository.findById(dbId, 1);
    }

}
