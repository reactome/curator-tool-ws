package org.reactome.curation.controller;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;

import javax.websocket.server.PathParam;

import org.reactome.curation.exceptions.DatabaseObjectNotFoundException;
import org.reactome.curation.model.CurationAttribute;
import org.reactome.curation.service.CurationService;
import org.reactome.server.graph.domain.model.Complex;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.EntityWithAccessionedSequence;
import org.reactome.server.graph.domain.model.PhysicalEntity;
import org.reactome.server.graph.domain.model.ReactionLikeEvent;
import org.reactome.server.graph.domain.relationship.HasCompartment;
import org.reactome.server.graph.domain.relationship.Output;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;

import lombok.Data;
import lombok.NoArgsConstructor;

@RestController
@Data
@NoArgsConstructor
@RequestMapping("api/curation")
@SuppressWarnings("unchecked")
@CrossOrigin
public class CurationController {
    private static final Logger logger = LoggerFactory.getLogger(CurationController.class);
    
    @Autowired
    private CurationService service;
    @Autowired
    private ObjectMapper objectMapper;
    
    @GetMapping("findByDbId/{dbId}")
    public DatabaseObject findByDdId(@PathVariable("dbId") Long dbId) {
        DatabaseObject obj = service.findById(dbId);
        return obj;
    }
    
    //TODO: The error handling needs to be updated
    // See: https://www.toptal.com/java/spring-boot-rest-api-error-handling 
    /**
     * The obj needs to have java class information. Need a template for the front-end to see what information is needed
     * so that Spring cannot automatically convert JSON to obj.
     * @param obj
     * @return
     */
    @PostMapping("store")
    public Long store(@RequestBody DatabaseObject obj) {
        try {
            return service.store(obj);
        }
        catch(DatabaseObjectNotFoundException e) {
            logger.error("CurationController.store: " + e.getMessage(), e);
            throw e; // Return to the client
        }
        catch(Exception e) {
            logger.error("CurationController.store: " + e.getMessage(), e);
            throw new IllegalStateException(e.getMessage());
        }
    }
    
    @GetMapping("getAttributes/{className}")
    public List<CurationAttribute> getAttributes(@PathVariable("className") String className) {
        try {
            return service.getAttributes(className);
        }
        catch(Exception e) {
            logger.error("CurtionController.getAttributes: " + e.getMessage(), e);
            return Collections.EMPTY_LIST;
        }
    }

    @GetMapping("getSchemaClasses")
    public List<String> getSchemaClasses() {
        return service.getSchemaClasses();
    }


}
