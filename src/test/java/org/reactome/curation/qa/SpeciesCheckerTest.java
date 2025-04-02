package org.reactome.curation.qa;

import org.junit.jupiter.api.Test;
import org.reactome.curation.controller.CurationController;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QAReport;
import org.reactome.curation.service.CurationService;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SpeciesCheckerTest {

    @Autowired
    private QAService qaService;
    @Autowired
    private CurationController curationController;

    @Test
    void contextLoads() {
    }

    @Test
    public void testComplexSpeciesCheck() {
        SimpleInstance obj = curationController.findByDdIdInInstance(9626045L);
        QAReport result = this.qaService.performQACheck(obj); // Complex
        System.out.println(result);
    }

//    @Test
//    public void complexWithoutSpeciesOrCompartment() {
//        DatabaseObject obj = curationService.findById(9851304L);
//
//        QAReport result =
//                this.qaService.performQACheck(obj); // Complex
//        System.out.println(result);
//    }
//
//    //TODO: add tests for the entity set, pathway, and reaction
//    // Maybe this file should be just cases for the species check, created a new folder for qatests
//
//    @Test
//    public void testReactionSpeciesCheck() {
//        DatabaseObject obj = curationService.findById(5627353L);
//        QAReport result =
//                this.qaService.performQACheck(obj); // Reaction
//        System.out.println(result);
//    }
//
//    @Test
//    public void testReactionWithIncorrectSpeciesCheck() {
//        DatabaseObject obj = curationService.findById(9851310L);
//        QAReport result =
//                this.qaService.performQACheck(obj); // Test reaction with intentionally incorrect species
//        System.out.println(result);
//    }

}
