package org.reactome.curation.config;


import java.util.List;
import java.util.Set;
import java.util.SortedSet;

import org.aspectj.lang.Aspects;
import org.reactome.server.graph.aop.LazyFetchAspect;
import org.reactome.server.graph.domain.model.Complex;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.PhysicalEntity;
import org.reactome.server.graph.domain.model.ReactionLikeEvent;
import org.reactome.server.graph.domain.relationship.HasCompartment;
import org.reactome.server.graph.domain.relationship.Output;
import org.reactome.server.graph.service.AdvancedDatabaseObjectService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping;
import com.fasterxml.jackson.databind.jsontype.TypeResolverBuilder;

// It is needed to define the following bean to enable the correct JSON one hop serialization
@Configuration
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class WebConfig extends WebMvcConfigurationSupport {
    // This is required by LazyFetchAspect.
    @Autowired
    private AdvancedDatabaseObjectService objectService;

    // The following bean is needed and should be use false for setEnabledAOP to avoid
    // null exception. Also refer to: https://github.com/reactome/graph-core about LazyFetchAspect.
    @Bean
    LazyFetchAspect lazyFetchAspect() {
        LazyFetchAspect asp = Aspects.aspectOf(LazyFetchAspect.class);
        // The following is needed to make sure only one hop reference graph is pulled out for json
        asp.setEnableAOP(false);
        return asp;
    }

    // The following configuration follows https://stackoverflow.com/questions/51261809/spring-boot-jackson-non-null-property-not-working
    // To make ObjectMapper doesn't export null. The application.properties configuration cannot work reliable.
    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {        
        ObjectMapper mapper = new ObjectMapper();
        // Refer to https://stackoverflow.com/questions/12353774/how-to-customize-jackson-type-information-mechanism
        // and https://www.demo2s.com/java/jackson-typeresolverbuilder-tutorial-with-examples.html
        // Also the comment about this class.
        TypeResolverBuilder<?> typeResolver = new DatabaseObjectTypeResolverBuilder(DefaultTyping.NON_FINAL,
                mapper.getPolymorphicTypeValidator());
        typeResolver.init(JsonTypeInfo.Id.CLASS, null);
        typeResolver.inclusion(JsonTypeInfo.As.PROPERTY);
        typeResolver.typeProperty("@JavaClass");
        mapper.setDefaultTyping(typeResolver);
        
        // properties with null value, or what is considered empty, are not to be included.
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        mapper.addMixIn(PhysicalEntity.class, PEMixIn.class);
        mapper.addMixIn(Complex.class, ComplexMixIn.class);
        mapper.addMixIn(ReactionLikeEvent.class, ReactionlikeEventMixIn.class);
        mapper.addMixIn(DatabaseObject.class, DatabaseObjectMixIn.class);
        MappingJackson2HttpMessageConverter mappingJackson2HttpMessageConverter = new MappingJackson2HttpMessageConverter(mapper);

        // To be enabled soon.
        //        StringHttpMessageConverter stringHttpMessageConverter = new StringHttpMessageConverter();
        //        stringHttpMessageConverter.setWriteAcceptCharset(false);
        //        stringHttpMessageConverter.setSupportedMediaTypes(mediaTypes);
        //      converters.add(stringHttpMessageConverter);

        converters.add(mappingJackson2HttpMessageConverter);
        super.configureMessageConverters(converters);
    }

    static interface PEMixIn {
        @JsonIgnore
        public void setCompartment(SortedSet<HasCompartment> compartment);
    }

    static interface ComplexMixIn {
        @JsonIgnore
        public void setIncludedLocation(SortedSet<HasCompartment> includedLocation);
    }

    /**
     * For ReactionlikeEvent, we don't need the first setOutput(), but we need
     * the second setOutput(). Otherwise, output cannot be deserialized into Java
     * output from JSON. 
     * @author wug
     *
     */
    static interface ReactionlikeEventMixIn {
        @JsonIgnore
        public void setOutput(Set<Output> output);
        
        @JsonSetter // Must add!
        public void setOutput(List<PhysicalEntity> output);
    }

    static interface DatabaseObjectMixIn {
        @JsonIgnore
        public String getClassName();
        @JsonIgnore
        public String getSchemaClass();
    }
    
}
