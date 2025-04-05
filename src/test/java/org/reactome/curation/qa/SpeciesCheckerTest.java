package org.reactome.curation.qa;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.reactome.curation.controller.CurationController;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.reactome.curation.qa.model.QAReport;
import org.reactome.curation.service.CurationService;
import org.reactome.server.graph.domain.model.Complex;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.EntityWithAccessionedSequence;
import org.reactome.server.graph.domain.model.LiteratureReference;
import org.reactome.server.graph.domain.model.PhysicalEntity;
import org.reactome.server.graph.domain.model.Publication;
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
    
    private Complex createComplexWithNewComplexAndSubunit() {
        Long dbId = -1L;
        Complex complex = new Complex(dbId--);
        Long[] speciesIds = {48887L, 164493L};
        List<Species> species = new ArrayList<>();
        for (Long speciesId : speciesIds) {
            Species tmp = new Species();
            tmp.setDbId(speciesId);
            species.add(tmp);
        }
        complex.setSpecies(species);
        complex.setDisplayName("Test Complex Level 1");
        
        Complex subComplex = new Complex(dbId--);
        subComplex.setDisplayName("Sub Complex");
        speciesIds = new Long[]{48887L, 5229092L};
        species = new ArrayList<>();
        for (Long speciesId : speciesIds) {
            Species tmp = new Species();
            tmp.setDbId(speciesId);
            species.add(tmp);
        }
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
        Complex complex = createComplexWithNewComplexAndSubunit();
        DatabaseObject rtn = curationService.commit(complex);
//         Check this complex
        Long dbId = rtn.getDbId();
        System.out.println("New complex created: " + rtn);
//        Long dbId = 9851564L;
        DatabaseObject obj = curationService.findById(dbId);
        SimpleInstance inst = new SimpleInstance();
        inst.setDbId(obj.getDbId());
        inst.setDisplayName(obj.getDisplayName());
        inst.setSchemaClassName(obj.getClassName());
        QAReport report = this.qaService.performQACheck(inst); // Complex
        System.out.println("\nQA Result for " + obj);
        for (QACheckResult result : report.getQaResults()) {
            System.out.println("Checker: " + result.getCheckName());
            System.out.println("Columns: " + String.join("; ", result.getColumns()));
            for (String[] row : result.getRows()) {
                System.out.println("Row: " + String.join("; ", row));
            }
            System.out.println();
        }
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
