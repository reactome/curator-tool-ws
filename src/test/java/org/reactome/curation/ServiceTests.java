package org.reactome.curation;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.gk.model.ReactomeJavaConstants;
import org.junit.jupiter.api.Test;
import org.reactome.server.graph.domain.model.*;
import org.reactome.server.graph.repository.AdvancedDatabaseObjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.reactome.curation.model.CurationAttribute;
import org.reactome.curation.service.CurationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.core.JsonProcessingException;

@SpringBootTest
class ServiceTests {
    
    private static final Logger logger = LoggerFactory.getLogger(ServiceTests.class);

    @Autowired
    private CurationService curationService;
    @Autowired
    private AdvancedDatabaseObjectRepository advancedDatabaseObjectRepository;

    @Test
    void contextLoads() {
    }

    @Test
    public void testFindById() throws JsonProcessingException {
        assertNotNull(curationService);
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
            DatabaseObject obj = curationService.findById(dbIds[i]);
            assertEquals(obj.getClass(), classes[i]);
        }
    }
    
    @Test
    public void testGetAttributes() throws Exception {
        assertNotNull(curationService);
        String[] clsNames = {
                ReactomeJavaConstants.EntityWithAccessionedSequence,
                ReactomeJavaConstants.Pathway,
                ReactomeJavaConstants.Reaction,
                ReactomeJavaConstants.ReferenceGeneProduct
        };
        for (String clsName : clsNames) {
            List<CurationAttribute> attributes = curationService.getAttributes(clsName);
            logger.info(clsName + ":\n" + attributes);
        }
    }

    @Test
    public void updateDbObjectExisting() throws Exception {
        logger.info("Started testing curationService.updateDbObjectExisting");
        long start, time;
        start = System.currentTimeMillis();
        String test = new String("testGenericClass");
        DatabaseObject reaction = new Reaction(170984L);
        this.curationService.update(reaction, "displayName");
        time = System.currentTimeMillis() - start;
        logger.info("curationService.updateDbObject execution time:" + time + "ms");
        logger.info("Finished curationService.updateDbObjectExisting");
    }

    @Test
    public void updatePerson() throws Exception {
        logger.info("Started testing curationService.updatePerson");
        long start, time;
        start = System.currentTimeMillis();
        String projectAtt = new String("Test adding project name");
        Person person = advancedDatabaseObjectRepository.findById(140537L, 1);
        curationService.update(person, "project");
        time = System.currentTimeMillis() - start;
        logger.info("curationService.updateDbObject execution time:" + time + "ms");
        logger.info("Finished curationService.updatePerson");
    }

    @Test
    public void updateRelationship() throws Exception {
        logger.info("Started testing curationService.updateRelationship");
        long start, time;
        start = System.currentTimeMillis();
        // This is Guanming
        Person person = advancedDatabaseObjectRepository.findById(140537L, 1);
        // Would the attribute name be the relationship or the attribute of that attribute entity?
        // Or combo of both?
        List<Affiliation> affiliations = person.getAffiliation();
        Affiliation affiliation =  new Affiliation();
        affiliation.setDbId(curationService.getMaxDbId() + 1);
        affiliation.setDisplayName("Testing adding an affiliation");
        affiliations.add(affiliation);
        person.setAffiliation(affiliations);
        curationService.update(person, "affiliation");
        time = System.currentTimeMillis() - start;
        logger.info("curationService.updateDbObject execution time:" + time + "ms");
        logger.info("Finished curationService.updateRelationship");
    }

    @Test
    public void getMaxDbId() {
        logger.info("Started testing curationService.getMaxDbId");
        long start, time;
        start = System.currentTimeMillis();
        // Let run it 100 time to see the performance
        int times = 100;
        for (int i = 0; i < times; i++) {
            Long maxDbIdObserved = curationService.getMaxDbId();
            logger.info(i + ": " + maxDbIdObserved);
        }
        time = System.currentTimeMillis() - start;
        logger.info("curationService.getMaxDbId execution time:" + time + " ms");
        logger.info("Finished curationService.getMaxDbId");
    }

}
