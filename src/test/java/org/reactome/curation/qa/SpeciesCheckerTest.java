package org.reactome.curation.qa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.reactome.curation.controller.CurationController;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.reactome.curation.qa.model.QAReport;
import org.reactome.curation.service.CurationService;
import org.reactome.server.graph.domain.model.CatalystActivity;
import org.reactome.server.graph.domain.model.Complex;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.EntityWithAccessionedSequence;
import org.reactome.server.graph.domain.model.LiteratureReference;
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

    @Test
    void contextLoads() {
    }
    
    private ReactionLikeEvent createReactionLikeEvent() {
        Long dbId = -1L;
        Reaction reaction = new Reaction(dbId--);
        reaction.setDisplayName("Test Reaction");
        Long[] speciesIds = {48887L, 164493L};
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
        speciesIds = new Long[] {451465L, 159879L};
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

    private void checkSpecies(Long dbId) {
        DatabaseObject obj = curationService.findById(dbId);
        SimpleInstance inst = new SimpleInstance();
        inst.setDbId(obj.getDbId());
        inst.setDisplayName(obj.getDisplayName());
        inst.setSchemaClassName(obj.getClassName());
        QAReport report = this.qaService.performQACheck(inst); 
        System.out.println("\nQA Result for " + obj);
        for (QACheckResult result : report.getQaResults()) {
            System.out.println("Checker: " + result.getCheckName());
            if (result.isPassed()) {
                System.out.println("passed!");
                continue;
            }
            System.out.println("Columns: " + String.join("; ", result.getColumns()));
            for (String[] row : result.getRows()) {
                System.out.println("Row: " + String.join("; ", row));
            }
            System.out.println();
        }
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
        Long[] speciesIds = {48887L, 164493L};
        List<Species> species = createSpeciesList(speciesIds);
        complex.setSpecies(species);
        complex.setDisplayName("Test Complex Level 1");
        
        Complex subComplex = new Complex(dbId--);
        subComplex.setDisplayName("Sub Complex");
        speciesIds = new Long[]{48887L, 5229092L};
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
        Long[] refIds = {9626035L, 9624149L, 9615711L};
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
