package org.reactome.curation.controller;

import java.util.Collections;
import java.util.List;

import org.reactome.curation.exceptions.DatabaseObjectNotFoundException;
import org.reactome.curation.model.CurationAttribute;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.model.SimpleSchemaClass;
import org.reactome.curation.service.CurationService;
import org.reactome.server.graph.domain.model.DatabaseObject;
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

import com.fasterxml.jackson.databind.ObjectMapper;

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
    @Autowired
    private DatabaseObjectInstanceConverter converter;
    
    @GetMapping("findDatabaseObjectByDbId/{dbId}")
    public DatabaseObject findByDdId(@PathVariable("dbId") Long dbId) {
        DatabaseObject obj = service.findById(dbId);
        return obj;
    }
    
    @GetMapping("findByDbId/{dbId}")
    public SimpleInstance findByDdIdInInstance(@PathVariable("dbId") Long dbId) {
        try {
            DatabaseObject obj = service.findById(dbId);
            return converter.convert(obj);
        }
        catch(Exception e) {
            logger.error("CurationController.strore: " + e.getMessage(), e);
            throw new IllegalStateException(e.getMessage());
        }
    }
    
    //TODO: The error handling needs to be updated
    // See: https://www.toptal.com/java/spring-boot-rest-api-error-handling 
    /**
     * The obj needs to have java class information. Need a template for the front-end to see what information is needed
     * so that Spring cannot automatically convert JSON to obj.
     * @param obj
     * @return
     */
    @PostMapping("storeDatabaseObject")
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
    
    //TODO: The error handling needs to be updated
    // See: https://www.toptal.com/java/spring-boot-rest-api-error-handling 
    /**
     * Store a new SimpleInstance object that is posted in JSON.
     * @param obj
     * @return
     */
    @PostMapping("store")
    public Long store(@RequestBody SimpleInstance instance) {
        try {
            System.out.println(instance);
            DatabaseObject databaseObject = converter.convert(instance);
            return service.store(databaseObject);
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
    
    @GetMapping("listInstances/{className}/{skip}/{limit}")
    public List<SimpleInstance> listInstances(@PathVariable("className") String className,
                                              @PathVariable("skip") Integer skip,
                                              @PathVariable("limit") Integer limit) {
        return service.listInstances(className, skip, limit);
    }

    @GetMapping("getSchemaClasses")
    public List<String> getSchemaClasses() {
        return service.getSchemaClasses();
    }

    @GetMapping("getSchemaClassTree")
    public SimpleSchemaClass getSchemaClassTree() {
        try {
            return service.loadSchemaClassTree();
        }
        catch(Exception e) {
            logger.error("CurtionController.getSchemaClassTree: " + e.getMessage(), e);
            return new SimpleSchemaClass(); // Just return an empty node
        }
    }

}
