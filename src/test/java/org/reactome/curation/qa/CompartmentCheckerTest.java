package org.reactome.curation.qa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.reactome.curation.service.CurationService;
import org.reactome.server.graph.domain.model.*;
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

    private Complex createComplex() {
        Long dbId = -1L;
        Complex complex = new Complex(dbId--);
        complex.setDisplayName("Test Complex Level 1");
        complex.setCompartment(createCompartments(70101L));
        complex.setIncludedLocation(createCompartments(17940L));

        Complex subComplex = new Complex(dbId--);
        subComplex.setDisplayName("Sub Complex");
        subComplex.setCompartment(createCompartments(70101L, 984L));

        List<PhysicalEntity> hasComponents = new ArrayList<>();
        hasComponents.add(subComplex);
        hasComponents.add(subComplex);
        complex.setHasComponent(hasComponents);

        EntityWithAccessionedSequence ewas = new EntityWithAccessionedSequence(dbId--);
        ewas.setReferenceType("ReferenceGeneProduct");
        ewas.setDisplayName("Complex Subunit 1");
        ewas.setCompartment(createCompartments(984L));

        List<PhysicalEntity> subunits = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            subunits.add(ewas);
        subComplex.setHasComponent(subunits);

        return complex;
    }

    private EntitySet createEntitySet() {
        Long dbId = -1L;

        DefinedSet entitySet = new DefinedSet();
        entitySet.setDbId(dbId--);
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

    private ReactionLikeEvent createReactionLikeEvent() {
        Long dbId = -1L;
        Reaction reaction = new Reaction(dbId--);
        reaction.setDisplayName("Reaction for Compartment Check");
        reaction.setCompartment(createCompartments(17963L, 24781L));

        SimpleEntity input1 = new SimpleEntity();
        input1.setDbId(dbId--);
        input1.setDisplayName("Test Reaction Input 1");
        input1.setCompartment(createCompartments(24781L, 20699L));

        EntityWithAccessionedSequence input2 = new EntityWithAccessionedSequence();
        input2.setDbId(dbId--);
        input2.setReferenceType("ReferenceGeneProduct");
        input2.setDisplayName("Test Reaction Input 2");
        input2.setCompartment(createCompartments(17963L, 17963L));

        CatalystActivity ca = new CatalystActivity();
        ca.setDbId(dbId--);
        ca.setDisplayName("CatalystActivity Test");
        Complex catalyst = new Complex();
        catalyst.setDbId(dbId--);
        catalyst.setDisplayName("Catalyst Test");
        catalyst.setCompartment(createCompartments(432051L, 20699L));
        ca.setPhysicalEntity(catalyst);
        reaction.setCatalystActivity(Collections.singletonList(ca));

        List<PhysicalEntity> inputs = new ArrayList<>();
        inputs.add(input1);
        inputs.add(input1);
        inputs.add(input2);
        reaction.setInput(inputs);
        return reaction;
    }


    @Test
    public void checkComplexCompartments() throws Exception {
//        // Create a new Complex
//        Complex complex = createComplex();
//        DatabaseObject rtn = curationService.commit(complex);
//        System.out.println("New Complex created: " + rtn);
//
//        Long dbId = rtn.getDbId();

        Long dbId = 9851600L;
        QACheckUtilities.performQACheck(dbId, curationService, qaService);
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
