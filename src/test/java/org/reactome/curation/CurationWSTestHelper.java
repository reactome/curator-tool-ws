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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.TypeResolverBuilder;
import com.fasterxml.jackson.databind.node.JsonNodeType;

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
