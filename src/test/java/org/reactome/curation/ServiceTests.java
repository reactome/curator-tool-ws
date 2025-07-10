package org.reactome.curation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.util.List;

import org.gk.model.ReactomeJavaConstants;
import org.junit.jupiter.api.Test;
import org.reactome.curation.model.CurationAttribute;
import org.reactome.curation.service.CurationService;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.ModifiedResidue;
import org.reactome.server.graph.domain.model.Pathway;
import org.reactome.server.graph.domain.model.PathwayDiagram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.core.JsonProcessingException;

@SpringBootTest
class ServiceTests {
    
    private static final Logger logger = LoggerFactory.getLogger(ServiceTests.class);

    @Autowired
    private CurationService curationService;

    @Test
    void contextLoads() {
    }
    
    @Test
    public void testUpdateModifiedResidue() throws Exception {
        assertNotNull(curationService);
        // This is a test to see if the updateModifiedResidue() method works.
        // It should not throw any exceptions.
        Long dbId = 140630L; // This is a known modified residue in the database.
        ModifiedResidue modifiedResidue = (ModifiedResidue) curationService.findById(dbId);
        System.out.println("Found modified residue: " + modifiedResidue);
        modifiedResidue.setCoordinate(103); // Nothing changes. Just check the update method.
        curationService.commit(modifiedResidue);
        System.out.println("Updated modified residue: " + modifiedResidue);
    }

    @Test
    public void testFindById() throws JsonProcessingException {
        assertNotNull(curationService);
        Long[] dbIds = {
                109581L, // Pathway
//                72810L, // NCBI Taxonomy
//                9707103L, // A figure
//                72811L, // InstanceEdit
                9006828L, // PathwayDiagram
        };
        Class<?>[] classes = {
                Pathway.class,
//                ReferenceDatabase.class,
//                Figure.class,
//                InstanceEdit.class,
                PathwayDiagram.class
        };
        for (int i = 0; i < dbIds.length; i++) {
            DatabaseObject obj = curationService.findById(dbIds[i]);
            System.out.println("Found object: " + obj);
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
                ReactomeJavaConstants.ReferenceGeneProduct,
                ReactomeJavaConstants.Species
        };
        for (String clsName : clsNames) {
            List<CurationAttribute> attributes = curationService.getAttributes(clsName);
            logger.info(clsName + ":\n" + attributes);
        }
    }

//    @Test
//    public void updateDbObjectExisting() throws Exception {
//        logger.info("Started testing curationService.updateDbObjectExisting");
//        long start, time;
//        start = System.currentTimeMillis();
//        String test = new String("testGenericClass");
//        DatabaseObject reaction = new Reaction(170984L);
//        this.curationService.update(reaction, "displayName");
//        time = System.currentTimeMillis() - start;
//        logger.info("curationService.updateDbObject execution time:" + time + "ms");
//        logger.info("Finished curationService.updateDbObjectExisting");
//    }
//
//    @Test
//    public void updatePerson() throws Exception {
//        logger.info("Started testing curationService.updatePerson");
//        long start, time;
//        start = System.currentTimeMillis();
//        String projectAtt = new String("Test adding project name");
//        Person person = advancedDatabaseObjectRepository.findById(140537L, 1);
//        curationService.update(person, "project");
//        time = System.currentTimeMillis() - start;
//        logger.info("curationService.updateDbObject execution time:" + time + "ms");
//        logger.info("Finished curationService.updatePerson");
//    }
//
//    @Test
//    public void updateRelationship() throws Exception {
//        logger.info("Started testing curationService.updateRelationship");
//        long start, time;
//        start = System.currentTimeMillis();
//        // This is Guanming
//        Person person = advancedDatabaseObjectRepository.findById(140537L, 1);
//        // Would the attribute name be the relationship or the attribute of that attribute entity?
//        // Or combo of both?
//        List<Affiliation> affiliations = person.getAffiliation();
//        Affiliation affiliation =  new Affiliation();
//        affiliation.setDbId(curationService.getMaxDbId() + 1);
//        affiliation.setDisplayName("Testing adding an affiliation");
//        affiliations.add(affiliation);
//        person.setAffiliation(affiliations);
//        curationService.update(person, "affiliation");
//        time = System.currentTimeMillis() - start;
//        logger.info("curationService.updateDbObject execution time:" + time + "ms");
//        logger.info("Finished curationService.updateRelationship");
//    }

    @Test
    public void getNextDbId() {
        logger.info("Started testing curationService.getMaxDbId");
        long start, time;
        start = System.currentTimeMillis();
        // Let run it 100 time to see the performance
        int times = 10;
        for (int i = 0; i < times; i++) {
            Long maxDbIdObserved = curationService.getNextDbId();
            logger.info(i + ": " + maxDbIdObserved);
        }
        time = System.currentTimeMillis() - start;
        logger.info("curationService.getMaxDbId execution time:" + time + " ms");
        logger.info("Finished curationService.getMaxDbId");
    }

    @Test
    public void getAllMethods() {
        DatabaseObject obj = curationService.findById(109581L);
        Class<?> classOfObject = obj.getClass();
        //Method[] methods = classOfObject.getDeclaredMethods();
        Method[] methodsWithoutDeclared = classOfObject.getMethods();
    }

    @Test
    public void getDeclaredMethod() throws NoSuchMethodException {
        DatabaseObject obj = curationService.findById(109581L);
        Class<?> classOfObject = obj.getClass();
        //Method[] methods = classOfObject.getDeclaredMethods();
        Method declaredMethod = classOfObject.getDeclaredMethod("getSpecies");

        //if(!declaredMethod && classOfObject.getPar)
    }

}
