package org.reactome.curation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.gk.model.ReactomeJavaConstants;
import org.junit.jupiter.api.Test;
import org.reactome.curation.controller.CurationController;
import org.reactome.curation.controller.DatabaseObjectInstanceConverter;
import org.reactome.curation.model.CurationAttribute;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.server.graph.domain.model.Complex;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.Figure;
import org.reactome.server.graph.domain.model.InstanceEdit;
import org.reactome.server.graph.domain.model.LiteratureReference;
import org.reactome.server.graph.domain.model.Pathway;
import org.reactome.server.graph.domain.model.Publication;
import org.reactome.server.graph.domain.model.ReferenceDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ControllerTests {

    private static final Logger logger = LoggerFactory.getLogger(ControllerTests.class);

    @Autowired
    private CurationController controller;
    @Autowired
    private DatabaseObjectInstanceConverter converter;

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
            logger.info(clsName + ":\n" + attributes);
        }
    }

    @Test
    public void testGetSchemaClasses() {
        List<String> schemaClasses = controller.getSchemaClasses();
        for(int i=0; i<schemaClasses.size(); i++){
            logger.info(schemaClasses.get(i));
        }
    }

    
    /**
     * Use this method to test update a complex's display name and with one literature reference replaced by
     * another. The tested complex is created by another test, testStoreComplexWithNewValue.
     * @throws Exception
     */
    @Test
    public void testUpdateComplex() throws Exception {
        Long dbId = 9851292L;
        DatabaseObject complex = controller.findByDdId(dbId);
        logger.info("Found complex: " + complex);
        // Replace the display name
        complex.setDisplayName("Updated Complex Name");
        // Add literature references to test list and order
        // Replace the second literature with a new one: 9624149L
        Long[] refIds = {9626035L, 9622837L, 9615711L}; 
        List<Publication> refs = Stream.of(refIds).map(id -> new LiteratureReference(id)).collect(Collectors.toList());
        ((Complex)complex).setLiteratureReference(refs);
        SimpleInstance instance = converter.convert(complex);
//        converter.convert(instance);
        controller.commit(instance);
    }

}
