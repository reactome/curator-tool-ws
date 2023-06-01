package org.reactome.curation.controller;

import java.util.Collections;
import java.util.List;

import org.reactome.curation.model.CurationAttribute;
import org.reactome.curation.service.CurationService;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import lombok.Data;
import lombok.NoArgsConstructor;

@RestController
@Data
@NoArgsConstructor
@RequestMapping("api/curation")
@SuppressWarnings("unchecked")
public class CurationController {
    private static final Logger logger = LoggerFactory.getLogger(CurationController.class);
    
    @Autowired
    private CurationService service;
    
    @GetMapping("findByDbId/{dbId}")
    @ResponseBody
    public DatabaseObject findByDdId(@PathVariable("dbId") Long dbId) {
        DatabaseObject obj = service.findById(dbId);
        return obj;
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

    @GetMapping("fetch/getMaxDbId")
    @ResponseBody
    public Long getMaxDbId() {
        logger.info("Request for the maximum dbId");
        return service.getMaxDbId();
    }

    @GetMapping("generateDbId")
    @ResponseBody
    public Long generateDbId() {
        logger.info("Request for new dbId");
        return service.generateDbId();
    }

    @GetMapping("fetch/getSchema")
    @ResponseBody
    public List<String> getSchema() {
        return service.getSchema();
    }


}
