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
class EntitySetTypeCheckTest {

    @Autowired
    private QAService qaService;
    @Autowired
    private CurationService curationService;
    
    // Note: This is needed. 
    @Test
    void contextLoads() {
    }
    
    private EntitySet createEntitySet() {
        Long dbId = -1L;

        // This set should be caught by the check
        DefinedSet entitySet = new DefinedSet();
        entitySet.setDbId(dbId--);
        entitySet.setDisplayName("EntitySet for EntitySet Type Check");
        
        EntityWithAccessionedSequence member1 = new EntityWithAccessionedSequence();
        // EGFR L858R
        member1.setDbId(1173212L);
        
        // EGF-like ligands:EGFR dimer [plasma membrane] [9624420]
        Complex member2 = new Complex();
        member2.setDbId(9624420L);
        
        // Set should be escaped. However, the members in the escaped set are still considered
        EntitySet member3 = new DefinedSet();
        // Phosphorylated EGFR dimer [plasma membrane] [180285]
        member3.setDbId(180285L);
        

        List<PhysicalEntity> members = new ArrayList<>();
        members.add(member1);
        members.add(member2);
        members.add(member3);
        entitySet.setHasMember(members);

        return entitySet;
    }

    @Test
    public void testEntitySetTypeCheck() throws Exception {
        // Create a new entitySet
//        EntitySet entitySet = createEntitySet();
//        DatabaseObject rtn = curationService.commit(entitySet);
////         Check this reaction
//        Long dbId = rtn.getDbId();
//        System.out.println("New entitySet created: " + rtn);
        // Run the above code should generate the following new EntitySet. However, the dbId
        // for that EntitySet may not be the same as the one used in the test below.
        Long dbId = 9851622L; // Use an existing entitySet for testing
        QACheckUtilities.performQACheck(dbId, curationService, qaService);
    }

}
