package org.reactome.curation.qa;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.reactome.curation.service.CurationService;
import org.reactome.server.graph.domain.model.Compartment;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.DefinedSet;
import org.reactome.server.graph.domain.model.EntitySet;
import org.reactome.server.graph.domain.model.EntityWithAccessionedSequence;
import org.reactome.server.graph.domain.model.PhysicalEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class CompartmentCheckerTest {
    
    @Autowired
    private QAService qaService;
    @Autowired
    private CurationService curationService;
    
    // Note: This is needed. 
    @Test
    void contextLoads() {
    }
    
    private List<Compartment> createCompartments(Long... dbIds) {
        return Stream.of(dbIds).map(dbId -> new Compartment(dbId)).collect(Collectors.toList());
    }
    
    private EntitySet createEntitySet() {
        Long dbId = -1L;
        
        DefinedSet entitySet = new DefinedSet();
        entitySet.setDbId(dbId --);
        entitySet.setDisplayName("EntitySet for Compartment Test");
        entitySet.setCompartment(createCompartments(17940L, 70101L));
     
        EntityWithAccessionedSequence member1 = new EntityWithAccessionedSequence();
        member1.setDbId(dbId--);
        member1.setDisplayName("First Member");
        member1.setCompartment(createCompartments(70101L, 984L));
        
        EntityWithAccessionedSequence member2 = new EntityWithAccessionedSequence();
        member2.setDbId(dbId--);
        member2.setDisplayName("Second Member");
        member2.setCompartment(createCompartments(984L));
        
        List<PhysicalEntity> members = new ArrayList<>();
        members.add(member1);
        members.add(member2);
        entitySet.setHasMember(members);
        
        return entitySet;
    }
    
    @Test
    public void checkEntitySetCompartments() throws Exception {
        // Create a new EntitySet
//        EntitySet entitySet = createEntitySet();
//        DatabaseObject rtn = curationService.commit(entitySet);
//        System.out.println("New EntitySet created: " + rtn);
//        
//        Long dbId = rtn.getDbId();
        
        Long dbId = 9851591L;
        QACheckUtilities.performQACheck(dbId, curationService, qaService);
    }
    
}
