package org.reactome.curation;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.reactome.curation.service.PathwayDiagramService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
class DiagramServiceTests {
    
    private static final Logger logger = LoggerFactory.getLogger(DiagramServiceTests.class);

    @Autowired
    private PathwayDiagramService diagramService;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void contextLoads() {
    }

    @Test
    public void testExportPathwayDiagramJSON() throws Exception {
        assertNotNull(diagramService);
        Long pathwayDiagramId = 9631416L; 
        File file = new File("/Users/wug/Documents/web_curator_tool/diagram/cytoscape/9615710.json");
        JsonNode root = mapper.readTree(file);
        diagramService.exportPathwayDiagramJSON(pathwayDiagramId, root);
    }
    
   


}
