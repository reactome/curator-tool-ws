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
class SpeciesCheckerTest {

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

        DefinedSet entitySet = new DefinedSet();
        entitySet.setDbId(dbId--);
        entitySet.setDisplayName("EntitySet for Compartment Test");
        Long[] speciesIds = { 48887L, 164493L };
        List<Species> species = createSpeciesList(speciesIds);
        entitySet.setSpecies(species);

        EntityWithAccessionedSequence member1 = new EntityWithAccessionedSequence();
        member1.setDbId(dbId--);
        member1.setDisplayName("First Member");
        member1.setSpecies(createSpecies(451465L));

        EntityWithAccessionedSequence member2 = new EntityWithAccessionedSequence();
        member2.setDbId(dbId--);
        member2.setDisplayName("Second Member");
        member2.setSpecies(createSpecies(451465L));

        List<PhysicalEntity> members = new ArrayList<>();
        members.add(member1);
        members.add(member2);
        entitySet.setHasMember(members);

        return entitySet;
    }

    private Pathway createPathway() {
        Long dbId = -1L;
        Pathway pathway = new Pathway(dbId--);
        pathway.setDisplayName("Pathway for Species Check");
        Long[] speciesIds = { 48887L, 164493L };
        List<Species> species = createSpeciesList(speciesIds);
        pathway.setSpecies(species);

        Event event1 = new BlackBoxEvent();
        event1.setDbId(dbId--);
        event1.setDisplayName("Test BlackBox Event");
        speciesIds = new Long[] { 451465L, 159879L };
        species = createSpeciesList(speciesIds);
        event1.setSpecies(species);

        Reaction event2 = new Reaction();
        event2.setDbId(dbId--);
        event2.setDisplayName("Test Reaction");
        speciesIds = new Long[] { 2671792L, 48898L };
        species = createSpeciesList(speciesIds);
        event2.setSpecies(species);

        Event event3 = new CellDevelopmentStep();
        event3.setDbId(dbId--);
        event3.setDisplayName("Test Cell Development Event");
        speciesIds = new Long[] { 451465L, 62596L };
        species = createSpeciesList(speciesIds);
        event3.setSpecies(species);

        Event event4 = new Depolymerisation();
        event4.setDbId(dbId--);
        event4.setDisplayName("Test Depolymerisation Event");
        speciesIds = new Long[] { 451465L, 9603609L };
        species = createSpeciesList(speciesIds);
        event4.setSpecies(species);

        Event event5 = new FailedReaction();
        event5.setDbId(dbId--);
        event5.setDisplayName("Failed Reaction");
        speciesIds = new Long[] { 451465L, 68323L };
        species = createSpeciesList(speciesIds);
        event5.setSpecies(species);

        List<Event> events = new ArrayList<>();
        events.add(event1);
        events.add(event2);
        events.add(event3);
        events.add(event4);
        events.add(event5);

        pathway.setHasEvent(events);
        return pathway;
    }

    private ReactionLikeEvent createReactionLikeEvent() {
        Long dbId = -1L;
        Reaction reaction = new Reaction(dbId--);
        reaction.setDisplayName("Test Reaction");
        Long[] speciesIds = { 48887L, 164493L };
        List<Species> species = createSpeciesList(speciesIds);
        reaction.setSpecies(species);

        SimpleEntity input1 = new SimpleEntity();
        input1.setDbId(dbId--);
        input1.setDisplayName("Test Reaction Input 1");
        input1.setSpecies(createSpecies(5229092L));

        EntityWithAccessionedSequence input2 = new EntityWithAccessionedSequence();
        input2.setDbId(dbId--);
        input2.setReferenceType("ReferenceGeneProduct");
        input2.setDisplayName("Test Reaction Input 2");
        input2.setSpecies(createSpecies(48887L));

        CatalystActivity ca = new CatalystActivity();
        ca.setDbId(dbId--);
        ca.setDisplayName("CatalystActivity Test");
        Complex catalyst = new Complex();
        catalyst.setDbId(dbId--);
        catalyst.setDisplayName("Catalyst Test");
        speciesIds = new Long[] { 451465L, 159879L };
        species = createSpeciesList(speciesIds);
        catalyst.setSpecies(species);
//        catalyst.setSpecies(createSpecies(159879L));
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
    public void testReactionSpeciesCheck() throws Exception {
        // Create a new reaction
//        ReactionLikeEvent reaction = createReactionLikeEvent();
//        DatabaseObject rtn = curationService.commit(reaction);
////         Check this reaction
//        Long dbId = rtn.getDbId();
//        System.out.println("New reaction created: " + rtn);
        Long dbId = 9851588L;
        checkSpecies(dbId);
    }
    
    @Test
    public void testPathwaySpeciesCheck() throws Exception {
//        // Create a new pathway
//        Pathway pathway = createPathway();
//        DatabaseObject rtn = curationService.commit(pathway);
////         Check this reaction
//        Long dbId = rtn.getDbId();
//        System.out.println("New pathway created: " + rtn);
        Long dbId = 210992L;
        checkSpecies(dbId);
    }

    @Test
    public void testEntitySetSpeciesCheck() throws Exception {
//        // Create a new entitySet
//        EntitySet entitySet = createEntitySet();
//        DatabaseObject rtn = curationService.commit(entitySet);
////         Check this reaction
//        Long dbId = rtn.getDbId();
//        System.out.println("New entitySet created: " + rtn);
        Long dbId = 9851327L;
        checkSpecies(dbId);
    }

    private void checkSpecies(Long dbId) {
        QACheckUtilities.performQACheck(dbId, curationService, qaService);
    }

    private Species createSpecies(Long speciesId) {
        Species species = new Species();
        species.setDbId(speciesId);
        return species;
    }

    private List<Species> createSpeciesList(Long[] speciesIds) {
        List<Species> species = new ArrayList<>();
        for (Long speciesId : speciesIds) {
            Species tmp = createSpecies(speciesId);
            species.add(tmp);
        }
        return species;
    }

    private Complex createComplexWithNewComplexAndSubunit() {
        Long dbId = -1L;
        Complex complex = new Complex(dbId--);
        Long[] speciesIds = { 48887L, 164493L };
        List<Species> species = createSpeciesList(speciesIds);
        complex.setSpecies(species);
        complex.setDisplayName("Test Complex Level 1");

        Complex subComplex = new Complex(dbId--);
        subComplex.setDisplayName("Sub Complex");
        speciesIds = new Long[] { 48887L, 5229092L };
        species = createSpeciesList(speciesIds);
        subComplex.setSpecies(species);

        List<PhysicalEntity> hasComponents = new ArrayList<>();
        hasComponents.add(subComplex);
        hasComponents.add(subComplex);
        complex.setHasComponent(hasComponents);

        EntityWithAccessionedSequence ewas = new EntityWithAccessionedSequence(dbId--);
        ewas.setReferenceType("ReferenceGeneProduct");
        ewas.setDisplayName("Complex Subunit 1");
        List<PhysicalEntity> subunits = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            subunits.add(ewas);
        subComplex.setHasComponent(subunits);
        // Add literature references to test list and order
        Long[] refIds = { 9626035L, 9624149L, 9615711L };
        List<Publication> refs = Stream.of(refIds).map(id -> new LiteratureReference(id)).collect(Collectors.toList());
        complex.setLiteratureReference(refs);

        return complex;
    }

    @Test
    public void testComplexSpeciesCheck() throws Exception {
        // Create a new complex
//        Complex complex = createComplexWithNewComplexAndSubunit();
//        DatabaseObject rtn = curationService.commit(complex);
////         Check this complex
//        System.out.println("New complex created: " + rtn);
//        Long dbId = rtn.getDbId();
        Long dbId = 9851573L;
        checkSpecies(dbId);
    }

    // TODO: Test the cases for ReactionLikeEvent, EntitySet, and Pathway!!!

}
