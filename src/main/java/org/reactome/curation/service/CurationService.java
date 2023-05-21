package org.reactome.curation.service;

import org.reactome.curation.repository.CurationRepository;
//import org.reactome.server.graph.aop.LazyFetchAspect;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.repository.AdvancedDatabaseObjectRepository;
import org.reactome.server.graph.service.AdvancedDatabaseObjectService;
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
    
    // For curation specific stuff
    @Autowired
    private CurationRepository curationRepository;
    // For queries
    @Autowired
    private AdvancedDatabaseObjectRepository objectRepository;
    
    public DatabaseObject findById(Long dbId) {
       return objectRepository.findById(dbId, 1);
    }

}
