package org.reactome.curation.config;


import java.util.ArrayList;
import java.util.List;

import org.aspectj.lang.Aspects;
import org.reactome.server.graph.aop.LazyFetchAspect;
import org.reactome.server.graph.service.AdvancedDatabaseObjectService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

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
        ObjectMapper objectMapper = new ObjectMapper();
        // properties with null value, or what is considered empty, are not to be included.
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        MappingJackson2HttpMessageConverter mappingJackson2HttpMessageConverter = new MappingJackson2HttpMessageConverter(objectMapper);

        // To be enabled soon.
//        StringHttpMessageConverter stringHttpMessageConverter = new StringHttpMessageConverter();
//        stringHttpMessageConverter.setWriteAcceptCharset(false);
//        stringHttpMessageConverter.setSupportedMediaTypes(mediaTypes);
//      converters.add(stringHttpMessageConverter);

        converters.add(mappingJackson2HttpMessageConverter);
        super.configureMessageConverters(converters);

    }

}
