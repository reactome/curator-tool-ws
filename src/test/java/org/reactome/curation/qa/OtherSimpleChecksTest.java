package org.reactome.curation.qa;

import org.junit.jupiter.api.Test;
import org.reactome.curation.service.CurationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class OtherSimpleChecksTest {

    @Autowired
    private QAService qaService;
    @Autowired
    private CurationService curationService;
    
    // Note: This is needed. 
    @Test
    void contextLoads() {
    }
    
    @Test
    public void testReviewStatusSlotCheck() throws Exception {
        // A known issue
        Long dbId = 912446L; // Use an existing Pathway for testing. This should be passed.
        QACheckUtilities.performQACheck(dbId, curationService, qaService);
    }
    
    
    @Test
    public void testEFSNormalEntityCheck() throws Exception {
        // A known issue
        Long dbId = 9666607L; // Use an existing entitySet for testing
        QACheckUtilities.performQACheck(dbId, curationService, qaService);
    }
    
    @Test
    public void testEFSDiseaseEntityCheck() throws Exception {
        // A known issue
        Long dbId = 9795102L; // Use an existing entitySet for testing
        QACheckUtilities.performQACheck(dbId, curationService, qaService);
    }

}
