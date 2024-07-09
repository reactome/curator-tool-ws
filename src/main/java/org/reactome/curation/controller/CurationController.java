package org.reactome.curation.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.reactome.curation.model.CurationAttribute;
import org.reactome.curation.model.InstanceList;
import org.reactome.curation.model.ListOperand;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.model.SimpleSchemaClass;
import org.reactome.curation.service.CurationService;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    
    /**
     * Call this method to fill the attributes for a LiteratureReference represented in
     * the passed SimpleInstance.
     * @param instance
     * @return
     */
    @PostMapping("fillReference")
    public SimpleInstance fillReference(@RequestBody SimpleInstance instance) {
        try {
            SimpleInstance rtn = service.fillLiteratureReference(instance);
            return rtn;
        }
        catch(Exception e) {
            logger.error("CurationController.fillReference: " + e.getMessage(), e);
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
    public InstanceList listInstances(@PathVariable("className") String className,
                                      @PathVariable("skip") Integer skip,
                                      @PathVariable("limit") Integer limit,
                                      // Make sure to use Optional so that we can take a URL without query.
                                      @RequestParam("query") Optional<String> query) {
        return service.listInstances(className, 
                skip, 
                limit,
                query.isEmpty() ? null : query.get());
    }
    

    /**
     * Search instances for lists of attributes, operands and searckKeys. Basically this is a more 
     * powerful listInstances. But to make the API simpler, this method is split from listInstances.
     * The frontend should determine what API should be called.
     * @param className
     * @param skip
     * @param limit
     * @param attributes
     * @param operands
     * @param searchKeys
     * @return
     */
    @GetMapping("searchInstances/{className}/{skip}/{limit}")
    public InstanceList searchInstances(@PathVariable("className") String className,
                                        @PathVariable("skip") Integer skip,
                                        @PathVariable("limit") Integer limit,
                                        @RequestParam("attributes") Optional<String> attributes,
                                        @RequestParam("operands") Optional<String> operands,
                                        @RequestParam("searchKeys") Optional<String> searchKeys) {
        try {
            // In any of the following case, we will use listInstances
            if (attributes.isEmpty() || operands.isEmpty() || searchKeys.isEmpty())
                return service.listInstances(className, skip, limit, null);
            List<String> attributeList = List.of(attributes.get().split(","));
            List<String> operandList = List.of(operands.get().split(","));
            List<String> keyList = List.of(searchKeys.get().split(","));
            // Make sure all three lists have the same length
            if ((attributeList.size() != operandList.size()) ||
                    (attributeList.size() != keyList.size())) {
                String error = "The query parameters for searchInstances have different length.";
                logger.error(error);
                return new InstanceList(); // Just return an empty object.
            }
            // Need to find the attribute type for each attribute
            List<String> attributeTypeList = new ArrayList<>(attributeList.size());
            for (String attribute : attributeList) {
                if (service.isInstanceType(className, attribute))
                    attributeTypeList.add("instance"); // Doesn't matter whatever this is called.
                else
                    attributeTypeList.add("property");
            }
            // Need to map to the operands
            List<ListOperand> listOperandList = new ArrayList<>(operandList.size());
            for (String operand : operandList) {
                ListOperand listOperand = ListOperand.map(operand);
                listOperandList.add(listOperand);
            }
            return service.listInstances(className, 
                    skip, 
                    limit,
                    attributeList,
                    attributeTypeList,
                    listOperandList,
                    keyList);
        }
        catch(Exception e) {
            logger.error("searchInstances: " + e.getMessage(), e);
            return new InstanceList(); // Return an empty object.
        }
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
            logger.error("CurationController.getSchemaClassTree: " + e.getMessage(), e);
            return new SimpleSchemaClass(); // Just return an empty node
        }
    }
    
    //TODO: Need to change the account into a more secure way. Currently just for prototyping!
    @GetMapping("loadInstances/{account}")
    public List<SimpleInstance> loadInstances(@PathVariable("account") String account) {
        try {
            return service.loadInstances(account);
        }
        catch(Exception e) {
            logger.error("CurationController.loadInstances: " + e.getMessage(), e);
            return Collections.EMPTY_LIST;
        }
    }
    
    //TODO: Need to change the account into a more secure way!!!
    @DeleteMapping("deletePersistedInstances/{account}")
    public void deletePersistedInstances(@PathVariable("account") String account) {
        try {
            service.deletePersistedInstances(account);
        }
        catch(Exception e) {
            logger.error("CurationController.deletePersistedInstances: " + e.getMessage(), e);
        }
    }
    
    //TODO: Need to change the account into a more secure way!!!
    @PostMapping("persistInstances/{account}")
    public void persistInstances(@RequestBody List<SimpleInstance> instances,
                                 @PathVariable("account") String account) {
        try {
            service.persitInstances(instances, account);
        }
        catch(Exception e) {
            logger.error("CurationController.persistInstances: " + e.getMessage(), e);
        }
    }

    @GetMapping("getEventTree/{speciesName}")
    public List<SimpleInstance> getEventTree(
            @PathVariable("speciesName") String speciesName,
            @RequestParam("class") Optional<String> className,
            @RequestParam("attributes") Optional<String> attributes,
            @RequestParam("attributeTypes") Optional<String> attributeTypes,
            @RequestParam("operands") Optional<String> operands,
            @RequestParam("searchKeys") Optional<String> searchKeys
    ) {
        return service.getEventTree(speciesName,
                className.isEmpty() ? null : className.get(),
                attributes.isEmpty() ? null : attributes.get(),
                attributeTypes.isEmpty() ? null : attributeTypes.get(),
                operands.isEmpty() ? null : operands.get(),
                searchKeys.isEmpty() ? null : searchKeys.get());
    }

    @GetMapping("getEventPlotData/{dbId}")
    public Map<String, List<Map<String, Object>>> getHierarchicalPlotData (
            @PathVariable("dbId") Long dbId,
            @RequestParam("type") String type
    ) {
        if (type.equals("hierarchical"))
            return service.getHierarchicalPlotData(dbId);
        else if (type.equals("reaction"))
            return service.getReactionPlotData(dbId);
        else
            return null;
    }
    
    /**
     * Fetch all reaction participants so that the reaction can be laid out fully in 
     * a pathway diagram.
     * @param dbId
     * @return
     */
    @GetMapping("fetchReactionWithParticipants/{dbId}")
    public SimpleInstance fetchReactionWithParticipants(@PathVariable("dbId") Long dbId) {
        return service.fetchReactionWithParticipants(dbId);
    }

    @GetMapping("getTestQACheckReport/{dbId}")
    public List<List<String>> getTestQACheckReport(
            @PathVariable("dbId") Long dbId,
            @RequestParam("checkType") String checkType,
            @RequestParam("editedAttributeNames") String editedAttributeNames,
            @RequestParam("editedAttributeValues") String editedAttributeValues
    ) {
        return service.getTestQACheckReport(dbId,
                checkType,
                Arrays.asList(editedAttributeNames.split(",")),
                Arrays.asList(editedAttributeValues.split(",")));
    }
}
