package org.reactome.curation.repository;

import java.io.File;
import java.util.Collections;
import java.util.List;

import org.reactome.curation.model.SimpleInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * This class is used to persist new and updated instances at a local file so that we can keep some intermediate
 * curation before pushed into the database. This should be useful to avoid the computer crash or set up some
 * test projects.
 */
@Repository
public class CurationFileRepository {
    private Logger logger = LoggerFactory.getLogger(CurationFileRepository.class);
    
    public CurationFileRepository() {
    }

    //TODO: Roll over the file name so that we can keep at least five backups!
    // 1). Keep 5 days
    // 2). Each day keep 5 backups
    public void persist(List<SimpleInstance> instances,
                        String fileName) throws Exception {
        File file = new File(fileName);
        ObjectMapper mapper = getObjectMapper();
        // Give it some format
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, instances);
        logger.info("Saved instances to " + file.getAbsolutePath());
    }
    
    /**
     * Delete the persisted instances for a case like users have committed all changed instances.
     * @param fileName
     * @throws Exception
     */
    public void deleteFile(String fileName) throws Exception {
        File file = new File(fileName);
        if (file.exists())
            file.delete();
    }
  
    public List<SimpleInstance> load(String fileName) throws Exception {
        File file = new File(fileName);
        // In case nothing there
        if (!file.exists())
            return Collections.emptyList();
        ObjectMapper mapper = getObjectMapper();
        TypeReference<List<SimpleInstance>> typeRef = new TypeReference<>(){};
        List<SimpleInstance> instances = mapper.readValue(file, typeRef);
        return instances;
    }
    
    private ObjectMapper getObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        return mapper;
    }
    
}
