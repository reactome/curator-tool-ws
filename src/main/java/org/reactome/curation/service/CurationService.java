package org.reactome.curation.service;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.reactome.curation.model.CurationAttribute;
import org.reactome.curation.repository.CurationRepository;
//import org.reactome.server.graph.aop.LazyFetchAspect;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.repository.AdvancedDatabaseObjectRepository;
import org.reactome.server.graph.service.helper.AttributeProperties;
import org.reactome.server.graph.service.util.DatabaseObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Service
@NoArgsConstructor
//@EnableScheduling
//@EnableAsync
//@EntityScan({"org.reactome.server.graph.domain.model"})
//@EnableNeo4jRepositories({"org.reactome.server.graph.repository", "org.reactome.curation.repository"})
@SuppressWarnings("unchecked")
public class CurationService {
    private static final Logger logger = LoggerFactory.getLogger(CurationService.class);
    
    private Map<String, List<CurationAttribute>> clsName2Attributes;
    
    // For curation specific stuff
    @Autowired
    private CurationRepository curationRepository;
    // For queries
    @Autowired
    private AdvancedDatabaseObjectRepository objectRepository;
    // To get the class attributes
    @Autowired
    private DatabaseObjectUtils databaseObjectUtils;
    
    public DatabaseObject findById(Long dbId) {
       return objectRepository.findById(dbId, 1);
    }
    
    public List<CurationAttribute> getAttributes(String clsName) throws Exception {
        if (clsName2Attributes == null) {
            logger.info("Loading clsName2Attributes...");
            clsName2Attributes = loadClsName2Attributes();
            logger.info("Done loading.");
        }
        if (clsName2Attributes == null)
            return Collections.EMPTY_LIST; // Just in case
        List<CurationAttribute> attributes = clsName2Attributes.get(clsName);
        if (attributes == null)
            return Collections.EMPTY_LIST;
        CurationAttribute any = attributes.stream().findAny().get();
        if (any.getProperties() != null)
            return attributes; // Loaded
        // Need to load attributes if needed
        Set<AttributeProperties> properties = databaseObjectUtils.getAttributeTable(clsName);
        // For quick assignment
        Map<String, AttributeProperties> name2prop = properties.stream()
                .collect(Collectors.toMap(AttributeProperties::getName, Function.identity()));
        attributes.forEach(att -> att.setProperties(name2prop.get(att.getName())));
        return attributes;
    }
    
    private Map<String, List<CurationAttribute>> loadClsName2Attributes() throws Exception {
        // There is no need to add "resources" before the file name even though this file is placed inside
        // the resources folder. Spring should figure it out.
        InputStream is = getClass().getClassLoader().getResourceAsStream("curation_schema_attributes.json");
        ObjectMapper mapper = new ObjectMapper();
        TypeReference<HashMap<String, List<CurationAttribute>>> typeRef = new TypeReference<>(){};
        return mapper.readValue(is, typeRef);
    }

}
