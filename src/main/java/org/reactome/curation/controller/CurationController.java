package org.reactome.curation.controller;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import org.springframework.web.bind.annotation.RequestParam;
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
     * Call this method to either store a new instance or update an existing 
     * instance. The implementation of this method will figure out which approach
     * should be used.
     * @param instance
     * @return
     */
    @PostMapping("commit")
    public SimpleInstance commit(@RequestBody SimpleInstance instance) {
        try {
            DatabaseObject databaseObject = converter.convert(instance);
            DatabaseObject stored = service.commit(databaseObject);
            // For the front end, we just need to return a SimpleInstance having attributes that may change
            SimpleInstance rtn = converter.convertInShell(stored);
            return rtn;
        }
        catch(Exception e) {
            logger.error("CurationController.commit: " + e.getMessage(), e);
            throw new IllegalStateException(e.getMessage());
        } 
    }
    
    /**
     * Delete a SimpleInstance object. 
     * @param instance
     * @return
     */
    @PostMapping("delete")
    public Boolean delete(@RequestBody SimpleInstance instance) {
        try {
            DatabaseObject obj = converter.convert(instance);
            return service.delete(obj);
        }
        catch(Exception e) {
            logger.error("CurationController.delete: " + e.getMessage(), e);
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
    
    /**
     * This API can accept an optional parameter called query for searching based on display name
     * in the format like ?query=TP53. 
     * Note: the query string should be encoded using the standard http way from the front end (e.g. no space, etc).
     * @param className
     * @param skip
     * @param limit
     * @return
     */
    @GetMapping("listInstances/{className}/{skip}/{limit}")
    public List<SimpleInstance> listInstances(@PathVariable("className") String className,
                                              @PathVariable("skip") Integer skip,
                                              @PathVariable("limit") Integer limit,
                                              // Make sure to use Optional so that we can take a URL without query.
                                              @RequestParam("query") Optional<String> query) { 
        return service.listInstances(className, skip, limit, query.isEmpty() ? null : query.get());
    }
    
    /**
     * This API is used to find an instance based on its displayName in a list of provided class names.
     * @param displayName
     * @param clsNames
     * @return
     */
    @GetMapping("findByDisplayName")
    public SimpleInstance findByDisplayName(@RequestParam("displayName") String displayName,
                                            @RequestParam("classNames") String clsNames) {
        String[] tokens = clsNames.split(",");
        List<String> clsNameList = Stream.of(tokens).map(token -> token.trim()).collect(Collectors.toList());
        return service.findInstance(displayName, clsNameList);
    }
    
    /**
     * This method accepts an optional query as listInstances.
     * @param className
     * @return
     */
    @GetMapping("countInstances/{className}")
    public Integer countInstances(@PathVariable("className") String className,
                                  @RequestParam("query") Optional<String> query) {
        return service.countInstances(className, query.isEmpty() ? null : query.get());
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

    @GetMapping("getEventTree/{speciesName}")
    public List<SimpleInstance> getEventTree(
            @PathVariable("speciesName") String speciesName,
            @RequestParam("class") Optional<String> className,
            @RequestParam("attribute") Optional<String> attribute,
            @RequestParam("attributeType") Optional<String> attributeType,
            @RequestParam("operand") Optional<String> operand,
            @RequestParam("query") Optional<String> searchKey
    ) {
        return service.getEventTree(speciesName,
                className.isEmpty() ? null : className.get(),
                attribute.isEmpty() ? null : attribute.get(),
                attributeType.isEmpty() ? null : attributeType.get(),
                operand.isEmpty() ? null : operand.get(),
                searchKey.isEmpty() ? null : searchKey.get());
    }
}
