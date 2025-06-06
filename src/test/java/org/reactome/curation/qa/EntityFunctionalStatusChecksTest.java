package org.reactome.curation.qa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.reactome.curation.service.CurationService;
import org.reactome.server.graph.domain.model.BlackBoxEvent;
import org.reactome.server.graph.domain.model.CatalystActivity;
import org.reactome.server.graph.domain.model.CellDevelopmentStep;
import org.reactome.server.graph.domain.model.Complex;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.DefinedSet;
import org.reactome.server.graph.domain.model.Depolymerisation;
import org.reactome.server.graph.domain.model.EntitySet;
import org.reactome.server.graph.domain.model.EntityWithAccessionedSequence;
import org.reactome.server.graph.domain.model.Event;
import org.reactome.server.graph.domain.model.FailedReaction;
import org.reactome.server.graph.domain.model.LiteratureReference;
import org.reactome.server.graph.domain.model.Pathway;
import org.reactome.server.graph.domain.model.PhysicalEntity;
import org.reactome.server.graph.domain.model.Publication;
import org.reactome.server.graph.domain.model.Reaction;
import org.reactome.server.graph.domain.model.ReactionLikeEvent;
import org.reactome.server.graph.domain.model.SimpleEntity;
import org.reactome.server.graph.domain.model.Species;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class EntityFunctionalStatusChecksTest {

    @Autowired
    private QAService qaService;
    @Autowired
    private CurationService curationService;
    
    // Note: This is needed. 
    @Test
    void contextLoads() {
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
