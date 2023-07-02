package org.reactome.curation;

import java.util.ArrayList;
import java.util.List;

import org.reactome.server.graph.domain.model.Complex;
import org.reactome.server.graph.domain.model.EntityWithAccessionedSequence;
import org.reactome.server.graph.domain.model.PhysicalEntity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;

public class CurationWSTestHelper {
    
    public static Complex createComplexWithNewComplexAndSubunit() {
        Long dbId = -1L;
        Complex complex = new Complex(dbId--);
        complex.setDisplayName("Test Complex Level 1");
        Complex subComplex = new Complex(dbId--);
        subComplex.setDisplayName("Test Complex Level 2_1");
        Complex subComplex1 = new Complex(dbId--);
        subComplex1.setDisplayName("Test Complex Level 2_2");
        List<PhysicalEntity> hasComponents = new ArrayList<>();
        hasComponents.add(subComplex);
        hasComponents.add(subComplex);
        hasComponents.add(subComplex1);
        hasComponents.add(subComplex1);
        hasComponents.add(subComplex1);
        complex.setHasComponent(hasComponents);
        EntityWithAccessionedSequence ewas = new EntityWithAccessionedSequence(dbId--);
        ewas.setReferenceType("ReferenceGeneProduct");
        ewas.setDisplayName("Complex Subunit 1");
        List<PhysicalEntity> subunits = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            subunits.add(ewas);
        subComplex.setHasComponent(subunits);
        EntityWithAccessionedSequence ewas1 = new EntityWithAccessionedSequence(dbId--);
        ewas1.setReferenceType("ReferenceGeneProduct");
        ewas1.setDisplayName("Complex Subunit 2");
        List<PhysicalEntity> subunits1 = new ArrayList<>();
        for (int i = 0; i < 2; i++)
            subunits1.add(ewas1);
        subComplex1.setHasComponent(subunits1);
        return complex;
    }
    
    public static ObjectMapper createObjectMapper() {
        // Need subtype information
        // Ref: https://www.baeldung.com/jackson-inheritance
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("org.reactome.server.graph.domain.model")
                .allowIfSubType("java.util")
                .build();
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        mapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL);
        return mapper;
    }

}
