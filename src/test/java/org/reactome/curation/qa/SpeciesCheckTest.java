package org.reactome.curation.qa;

import org.junit.jupiter.api.Test;
import org.reactome.curation.qa.model.QAReport;
import org.reactome.curation.service.CurationService;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SpeciesCheckTest {

    @Autowired
    private SpeciesCheck qualityCheckRepository;
    @Autowired
    private QAService qaService;
    @Autowired
    private CurationService curationService;

    @Test
    void contextLoads() {
    }

    @Test
    public void testComplexSpeciesCheck() {
        DatabaseObject obj = curationService.findById(9660013L);
        QAReport result =
                this.qaService.createQAReport(obj); // Complex
        System.out.println(result);
    }

    @Test
    public void complexWithoutSpeciesOrCompartment() {
        DatabaseObject obj = curationService.findById(9851304L);

        QAReport result =
                this.qaService.createQAReport(obj); // Complex
        System.out.println(result);
    }

    //TODO: add tests for the entity set, pathway, and reaction
    // Maybe this file should be just cases for the species check, created a new folder for qatests

    @Test
    public void testReactionSpeciesCheck() {
        DatabaseObject obj = curationService.findById(5627353L);
        QAReport result =
                this.qaService.createQAReport(obj); // Reaction
        System.out.println(result);
    }

    @Test
    public void testReactionWithIncorrectSpeciesCheck() {
        DatabaseObject obj = curationService.findById(9851310L);
        QAReport result =
                this.qaService.createQAReport(obj); // Test reaction with intentionally incorrect species
        System.out.println(result);
    }

}
