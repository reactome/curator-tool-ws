package org.reactome.curation;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.gk.model.ReactomeJavaConstants;
import org.junit.jupiter.api.Test;
import org.reactome.curation.controller.CurationController;
import org.reactome.curation.model.CurationAttribute;
import org.reactome.curation.service.CurationService;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.Figure;
import org.reactome.server.graph.domain.model.InstanceEdit;
import org.reactome.server.graph.domain.model.Pathway;
import org.reactome.server.graph.domain.model.ReferenceDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.core.JsonProcessingException;

@SpringBootTest
class ControllerTests {

    protected static final Logger logger = LoggerFactory.getLogger("controllerTestLogger");

    @Autowired
    private CurationController controller;

    @Test
    void contextLoads() {
    }

    @Test
    public void testFindById() {
        assertNotNull(controller);
        Long[] dbIds = {
                109581L, // Pathway
                72810L, // NCBI Taxonomy
                9707103L, // A figure
                72811L, // InstanceEdit
        };
        Class<?>[] classes = {
                Pathway.class,
                ReferenceDatabase.class,
                Figure.class,
                InstanceEdit.class
        };
        for (int i = 0; i < dbIds.length; i++) {
            DatabaseObject obj = controller.findByDdId(dbIds[i]);
            assertEquals(obj.getClass(), classes[i]);
        }
    }
    
    @Test
    public void testGetAttributes() throws Exception {
        assertNotNull(controller);
        String[] clsNames = {
                ReactomeJavaConstants.EntityWithAccessionedSequence,
                ReactomeJavaConstants.Pathway,
                ReactomeJavaConstants.Reaction,
                ReactomeJavaConstants.ReferenceGeneProduct
        };
        for (String clsName : clsNames) {
            List<CurationAttribute> attributes = controller.getAttributes(clsName);
            System.out.println(clsName + ":\n" + attributes);
        }
    }

    @Test
    public void testGetMaxDbId() {
        Long maxDbIdObserved = controller.getMaxDbId();
        assertThat(maxDbIdObserved).isEqualTo(1000000000000000001L);
    }

    @Test
    public void testGenerateDbId() {
        Long generatedDbId = controller.generateDbId();
        logger.info(generatedDbId.toString());
    }

    @Test
    public void testGetSchema() {
        List<String> schemaClasses = controller.getSchema();
        for(int i=0; i<schemaClasses.size(); i++){
            logger.info(schemaClasses.get(i));
        }
    }


}
