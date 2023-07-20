package org.reactome.curation;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.reactome.curation.config.DatabaseObjectTypeResolverBuilder;
import org.reactome.server.graph.domain.model.Complex;
import org.reactome.server.graph.domain.model.EntityWithAccessionedSequence;
import org.reactome.server.graph.domain.model.LiteratureReference;
import org.reactome.server.graph.domain.model.PhysicalEntity;
import org.reactome.server.graph.domain.model.Publication;
import org.reactome.server.graph.domain.model.Reaction;
import org.reactome.server.graph.domain.model.SimpleEntity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping;
import com.fasterxml.jackson.databind.jsontype.TypeResolverBuilder;

public class CurationWSTestHelper {
    
    public static Reaction createReaction() {
        Long dbId = -1L;
        Reaction reaction = new Reaction(dbId--);
        reaction.setDisplayName("Test Reaction");
        
        SimpleEntity input1 = new SimpleEntity();
        input1.setDbId(dbId--);
        input1.setDisplayName("Test Reaction Input 1");;
        EntityWithAccessionedSequence input2 = new EntityWithAccessionedSequence();
        input2.setDbId(dbId--);
        input2.setReferenceType("ReferenceGeneProduct");
        input2.setDisplayName("Test Reaction Input 2");
        List<PhysicalEntity> inputs = new ArrayList<>();
        inputs.add(input1);
        inputs.add(input1);
        inputs.add(input2);
        reaction.setInput(inputs);
        
        List<PhysicalEntity> outputs = new ArrayList<>();
        SimpleEntity output1 = new SimpleEntity();
        output1.setDbId(dbId --);
        output1.setDisplayName("Test Reaction Output 1");
        outputs.add(output1);
        outputs.add(output1);
        EntityWithAccessionedSequence output2 = new EntityWithAccessionedSequence();
        output2.setReferenceType("ReferenceGeneProduct");
        output2.setDbId(dbId --);
        output2.setDisplayName("Test Reaction Output 2");
        outputs.add(output2);
        // Apparently when this method is called, the original order is not kept
        // Same with setInput(). This is not good! However, apparently it works 
        // at the database side. Weird!
        reaction.setOutput(outputs);
        
        Long[] dbIds = {9625187L, 9625186L, 9625184L};
        List<Publication> references = Stream.of(dbIds)
                .map(id -> new LiteratureReference(id))
                .collect(Collectors.toList());
        reaction.setLiteratureReference(references);
        
        return reaction;
    }
    
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
        
        // Add literature references to test list and order
        Long[] refIds = {9626035L, 9624149L, 9615711L};
        List<Publication> refs = Stream.of(refIds).map(id -> new LiteratureReference(id)).collect(Collectors.toList());
        complex.setLiteratureReference(refs);
        
        return complex;
    }
    
    public static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        TypeResolverBuilder<?> typeResolver = new DatabaseObjectTypeResolverBuilder(DefaultTyping.NON_FINAL,
                mapper.getPolymorphicTypeValidator());
        typeResolver.init(JsonTypeInfo.Id.CLASS, null);
        typeResolver.inclusion(JsonTypeInfo.As.PROPERTY);
        typeResolver.typeProperty("@JavaClass");
        mapper.setDefaultTyping(typeResolver);
        
//        TypeResolverBuilder<?> serializerTyper = ObjectMapper.DefaultTypeResolverBuilder
//                .construct(ObjectMapper.DefaultTyping.NON_FINAL, mapper.getPolymorphicTypeValidator());
//        serializerTyper = serializerTyper.init(JsonTypeInfo.Id.CLASS, null);
//        serializerTyper = serializerTyper.inclusion(JsonTypeInfo.As.PROPERTY);
//        mapper.setDefaultTyping(serializerTyper);

        return mapper;
    }

}
