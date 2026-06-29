package org.reactome.curation.controller;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.reactome.curation.exceptions.DatabaseObjectNotFoundException;
import org.reactome.curation.exceptions.InstanceChangedException;
import org.reactome.curation.exceptions.InstanceDeletionException;
import org.reactome.curation.model.CurationAttribute;
import org.reactome.curation.model.DiagramLock;
import org.reactome.curation.model.InstanceList;
import org.reactome.curation.model.ListOperand;
import org.reactome.curation.model.NamedReferrerList;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.model.SimpleSchemaClass;
import org.reactome.curation.model.UserInstances;
import org.reactome.curation.qa.QAService;
import org.reactome.curation.qa.model.QAReport;
import org.reactome.curation.service.CurationService;
import org.reactome.curation.service.DiagramLockService;
import org.reactome.curation.service.EventDocxExportService;
import org.reactome.curation.service.PathwayDiagramService;
import org.reactome.curation.util.CurationAuditLogger;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.Deleted;
import org.reactome.server.graph.domain.model.Event;
import org.reactome.server.graph.domain.model.InstanceEdit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
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
    @Autowired
    private QAService qaService;
    @Autowired
    private StableIdentifierGenerator stableIdentifierGenerator;
    @Autowired
    private PathwayDiagramService diagramService;
    @Autowired
    private CurationAuditLogger auditLogger;
    @Autowired
    private EventDocxExportService eventDocxExportService;
    @Autowired
    private DiagramLockService diagramLockService;

    /**
     * This method basically provides as a delegate to load the pathway JSON files.
     * @param fileName
     * @return
     */
    @GetMapping("diagram/{fileName}")
    public JsonNode loadDiagram(@PathVariable("fileName") String fileName) {
        try {
            // To ensure the returned text is well formated JSON text for the front-end,
            // we will use JsonNode as a proxy for the JSON text.
            JsonNode rtn = diagramService.loadDiagramJson(fileName);
            if (rtn == null)
                return objectMapper.createObjectNode(); // Just return an empty JSON object
            return rtn;
        }
        catch(IOException e) {
            logger.error("CurationController.loadDiagram: " + e.getMessage(), e);
            throw new IllegalStateException(e.getMessage());
        }
    }
    
    @GetMapping("hasDiagram/{dbId}")
    public Boolean hasDiagram(@PathVariable("dbId") Long dbId) throws IOException {
        return diagramService.hasDiagramJson(dbId);
    }
    
    @GetMapping("getCyNetwork/{pathwayId}")
    public JsonNode loadCyNetwork(@PathVariable("pathwayId") Long pathwayId) {
        try {
            // To ensure the returned text is well formated JSON text for the front-end,
            // we will use JsonNode as a proxy for the JSON text.
            return diagramService.loadCyNetwork(pathwayId);
        } catch (IOException e) {
            logger.error("CurationController.loadCyNetwork: " + e.getMessage(), e);
            throw new IllegalStateException(e.getMessage());
        }
    }

//    @GetMapping("getUserLocks/{username}")
//    public List<DiagramLock> getUserLocks(@PathVariable("username") String username) {
//        try {
//            return diagramLockService.getUserLocks(username);
//        }
//        catch(Exception e) {
//            logger.error("CurationController.getUserLocks: " + e.getMessage(), e);
//            return new ArrayList<>(); // Just return an empty list
//        }
//    }

    @GetMapping("loadBackupCyNetwork/{pathwayId}")
    public JsonNode loadBackupCyNetwork(@PathVariable("pathwayId") Long pathwayId) {
        try {
            return diagramService.loadBackupCyNetwork(pathwayId);
        }
        catch(IOException e) {
            logger.error("CurationController.loadBackupCyNetwork: " + e.getMessage(), e);
            throw new IllegalStateException(e.getMessage());
        }
    }
    
    @GetMapping("hasCyNetwork/{pathwayId}")
    public Boolean hasCyNetwork(@PathVariable("pathwayId") Long pathwayId) throws IOException {
        return diagramService.hasCyNetwork(pathwayId);
    }
    
    //NB: This method has not been listed in the test!
    @PostMapping("uploadCyNetwork/{pathwayId}")
    public Boolean saveCyNetwork(@PathVariable("pathwayId") Long pathwayDiagramId,
                                        @RequestBody JsonNode networkJson) {
        String username = getUsername();
        try {
            JsonNode defaultPersonNode = networkJson == null ? null : networkJson.get("defaultPersonId");
            if (defaultPersonNode == null || !defaultPersonNode.canConvertToLong()) {
                logger.error("CurationController.saveCyNetwork: The defaultPersonId is missing or invalid.");
                return Boolean.FALSE;
            }
            Long dbId = defaultPersonNode.asLong();
            if (dbId <= 0) {
                logger.error("CurationController.saveCyNetwork: The defaultPersonId is not in the database.");
                return Boolean.FALSE;
            }
            DatabaseObject pdInst = service.findById(pathwayDiagramId);
            if (pdInst == null) {
                logger.error("CurationController.saveCyNetwork: Cannot find the PathwayDiagram instance with dbId: {}", pathwayDiagramId);
                return Boolean.FALSE;
            }
            InstanceEdit ie = converter.createInstanceEdit(dbId);
            diagramService.saveCyNetwork(pathwayDiagramId, networkJson);
            // Delete the back up if any
            diagramService.deleteBackupCyNetwork(pathwayDiagramId);
            service.addModifiedIE(pdInst, ie);
            auditLogger.logDiagramUpdate(username, pathwayDiagramId, true, null);
            return Boolean.TRUE;
        }
        catch(Exception e) {
            logger.error("CurationController.saveCyNetwork: " + e.getMessage(), e);
            auditLogger.logDiagramUpdate(username, pathwayDiagramId, false, e.getMessage());
            return Boolean.FALSE;
        }
    }

    /**
     * Back up a Cytoscape network json under active editing to avoid any loss and also make sure
     * the user may be able to recover their edits after closing browser and before uploading the diagram
     * to keep the intermediate changes.
     * @param pathwayDiagramId
     * @param lockId
     * @param networkJson
     * @return
     */
    @PostMapping("backupCyNetwork/{diagramId}/{lockId}")
    public Boolean backupCyNetwork(@PathVariable("diagramId") Long pathwayDiagramId,
                                          @PathVariable("lockId") String lockId,
                                          @RequestBody JsonNode networkJson) {
        // To backup, the user must be have the lock to avoid overwrite other's changes.
        String username = getUsername();
        DiagramLock lock = diagramLockService.getLock(pathwayDiagramId);
        if (lock == null || !lock.getUsername().equals(username) || !lock.getLockId().equals(lockId)) {
            logger.error("CurationController.backupCyNetwork: The lockId is empty or invalid.");
            return Boolean.FALSE;
        }
        try {
            diagramService.backupCyNetwork(pathwayDiagramId, networkJson);
            lock.setHasBackupDiagram(true);
            return Boolean.TRUE;
        }
        catch(Exception e) {
            logger.error("CurationController.backupCyNetwork: " + e.getMessage(), e);
            return Boolean.FALSE;
        }
    }

    /**
     * Locks a diagram for a specific user. This prevents other users from modifying the diagram
     * until it is unlocked.
     *
     * @return DiagramLock object containing lock information
     */
    @GetMapping("lockDiagram/{dbId}")
    public DiagramLock lockDiagram(@PathVariable("dbId") Long pathwayDiagramDbId) {
        String username = getUsername();
        DiagramLock lock = diagramLockService.lockDiagram(pathwayDiagramDbId, username);
        if (lock != null && lock.getUsername() != null && !username.equals(lock.getUsername())) {
            String error = username + " tried to lock the diagram " + pathwayDiagramDbId + " but it has already been locked.";
            auditLogger.logDiagramUpdate(username, pathwayDiagramDbId, false, error);
            return null;
        }
        auditLogger.logDiagramLock(username, pathwayDiagramDbId, true, null);
        return lock;
    }

    /**
     * Unlocks a diagram for the current user.
     *
     * @param diagramLock of the diagram to unlock
     * @return true if unlock was successful, false otherwise
     */
    @PostMapping("unlockDiagram")
    public Boolean unlockDiagram(@RequestBody DiagramLock diagramLock) {
        String username = getUsername();
        if (diagramLock.getUsername() != null && !username.equals(diagramLock.getUsername())) {
            String error = username + " tried to unlock a diagram " + diagramLock.getDiagramDbId() + " locked by " + diagramLock.getUsername();
            auditLogger.logDiagramUnlock(username, diagramLock.getDiagramDbId(), false, error);
            return Boolean.FALSE;
        }
        auditLogger.logDiagramUnlock(username, diagramLock.getDiagramDbId(), true, null);
        Boolean unlocked = diagramLockService.unlockDiagram(diagramLock);
        if (unlocked) {
            // Unlock a diagram will delete any backup for this diagram
            diagramService.deleteBackupCyNetwork(diagramLock.getDiagramDbId());
        }
        return unlocked;
    }

    /**
     * Checks if a diagram is locked and returns lock information. If not, null will be returned.
     *
     * @param pathwayDiagramDbId the dbId of a PathwayDiagram instance for lock query.
     * @return DiagramLock object if locked, null otherwise
     */
    @GetMapping("hasDiagramLocked/{dbId}")
    public DiagramLock hasDiagramLocked(@PathVariable("dbId") Long pathwayDiagramDbId) {
        DiagramLock lock = diagramLockService.getLock(pathwayDiagramDbId);
        if (lock == null)
            return null;
        // Need to remove lockId for security reason in case it is hacked
        // A DiagramLock without lockId cannot be used to unlock a diagram.
        DiagramLock copy = new DiagramLock();
        copy.setDiagramDbId(lock.getDiagramDbId());
        copy.setUsername(lock.getUsername());
        copy.setLockedAt(lock.getLockedAt());
        return copy;
    }

    /**
     * Get all DiagramLocks for the current user.
     * @return
     */
    @GetMapping("getDiagramLocks")
    public List<DiagramLock> getDiagramLocks() {
        String username = getUsername();
        List<DiagramLock> locks = diagramLockService.getUserLocks(username);
        // Check if any diagrams under editing. Since whenever unlocking a diagram will delete a backup
        // diagram, this should be safe for state synchronization: this user will not get other users' editing
        // diagrams
        if (locks != null && locks.size() > 0) {
            locks.stream().forEach(lock -> {
                lock.setHasBackupDiagram(diagramService.hasBackupCyNetwork(lock.getDiagramDbId()));
            });
        }
        return locks; // This user will have full DiagramLocks including lock ids so that they can delete it.
    }

    @GetMapping("findDatabaseObjectByDbId/{dbId}")
    public DatabaseObject findByDdId(@PathVariable("dbId") Long dbId) {
        DatabaseObject obj = service.findById(dbId);
        return obj;
    }
    
    @GetMapping("existsByDbId/{dbId}")
    public Boolean existsByDbId(Long dbId) {
        return this.service.existsById(dbId);
    }
    
    @GetMapping("findByDbId/{dbId}")
    public SimpleInstance findByDdIdInInstance(@PathVariable("dbId") Long dbId) {
        try {
            DatabaseObject obj = service.findById(dbId);
            if (obj == null)
                throw new DatabaseObjectNotFoundException(dbId);
            return converter.convert(obj);
        }
        catch(DatabaseObjectNotFoundException e1) {
            logger.error("CurationController.findByDdIdInInstance: " + e1.getMessage(), e1);
            throw e1;
        }
        catch(Exception e) {
            logger.error("CurationController.findByDdIdInInstance: " + e.getMessage(), e);
            throw new IllegalStateException(e.getMessage());
        }
    }
    
    /**
     * 
     * @param dbIds
     * @return
     * @deprecated: Don't call this method. This is highly efficient and may cause out of memory issue.
     */
    @Deprecated
    @PostMapping("findByDbIds")
    public List<SimpleInstance> findByDbIds(@RequestBody List<Long> dbIds) {
        try {
            List<DatabaseObject> objs = service.findInstancesByIds(dbIds);
            List<SimpleInstance> instances = new ArrayList<>(objs.size());
            for (DatabaseObject obj : objs) {
                SimpleInstance instance = converter.convert(obj);
                instances.add(instance);
            }
            return instances;
        }
        catch(Exception e) {
            logger.error("CurationController.findByDbIds: " + e.getMessage(), e);
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

    /**
     * Call this method to fill the attributes for a UniProt-backed reference sequence instance
     * (ReferenceGeneProduct, ReferencePeptideSequence, ReferenceRNASequence, or ReferenceIsoform)
     * based on its UniProt identifier.
     *
     * @param instance the reference sequence SimpleInstance containing an {@code identifier} attribute
     * @return the same instance with attributes filled from UniProt
     */
    @PostMapping("fillRefSequence")
    public SimpleInstance fillSequence(@RequestBody SimpleInstance instance) {
        try {
            return service.fillReferenceSequence(instance);
        }
        catch (Exception e) {
            logger.error("CurationController.fillSequence: " + e.getMessage(), e);
            throw new IllegalStateException(e.getMessage());
        }
    }

    /**
     * Fill PsiMOD attributes from the EBI ontology service.
     */
    @PostMapping("fillExternalOntology")
    public SimpleInstance fillExternalOntology(@RequestBody SimpleInstance instance) {
        try {
            return service.fillExternalOntology(instance);
        }
        catch (Exception e) {
            logger.error("CurationController.fillExternalOntology: " + e.getMessage(), e);
            throw new IllegalStateException(e.getMessage());
        }
    }

    /**
     * Fill ChEBI ReferenceMolecule attributes from the EBI ontology service.
     */
    @PostMapping("fillChEBI")
    public SimpleInstance fillChEBI(@RequestBody SimpleInstance instance) {
        try {
            return service.fillChEBI(instance);
        }
        catch (Exception e) {
            logger.error("CurationController.fillChEBI: " + e.getMessage(), e);
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
        // Check if the passed instance can be committed
        if (this.service.isConflictWithStored(instance))
            throw new InstanceChangedException(instance);
        String username = getUsername();
        boolean isUpdate = instance.getDbId() != null && instance.getDbId() > 0;
        try {
            DatabaseObject databaseObject = converter.convert(instance, true);
            Set<DatabaseObject> newInstances = service.grepNewInstances(databaseObject);
            // Make sure itself is not included in newInstances
            if (newInstances.contains(databaseObject))
                newInstances.remove(databaseObject);
            // Keep the old dbIds
            Map<DatabaseObject, Long> obj2id = null;
            if (newInstances != null && newInstances.size() > 0) {
                obj2id = new HashMap<>();
                for (DatabaseObject obj : newInstances) {
                    // The front-end should not care about the InstanceEdit
                    // instance created here to track the creation or modification.
                    // Therefore, this new InstanceEdit is not returned here.
                    // Also it has not negative dbId. Its dbid is null.
                    if (obj.getDbId() == null)
                        continue; // Means a new instance created in this commit
                    obj2id.put(obj, obj.getDbId());
                }
            }

            // Step 1: Store new instances first so that we can have their correct dbIds
            if (!isUpdate) 
                service.commitNewInstanceInShell(databaseObject);
            for (DatabaseObject newInstance : newInstances) {
                service.commitNewInstanceInShell(newInstance);
            }
            // Step 2: Make sure stable identifiers are assigned if needed
            // We need to do this before the final commit so that the StableIdentifier 
            // instances can be committed together (Note: New StableIdentifier instances
            // have null dbId here).
            this.stableIdentifierGenerator.setStableIdentifier(databaseObject);
            for (DatabaseObject newInstance : newInstances) {
                this.stableIdentifierGenerator.setStableIdentifier(newInstance);
            }
            // Step 3: Commit the stable identifiers
            // Need to see if the referred StableIdentifer needs to be committed
            // And commit it first if needed
            // The reason we do this here is to ensure the StableIdenrifier is updated at the server-side without
            // the user's intervention due to the fact that the front-end may not have the complete information.
            boolean isStableIdentifierModified = false;
            if (databaseObject.getStableIdentifier() != null && isUpdate) {
                // Means the existing StableIdentifier has been modified
                if (databaseObject.getModified() == databaseObject.getStableIdentifier().getModified()) {
                    commitAndAudit(username, databaseObject.getStableIdentifier(), isAdd(databaseObject.getStableIdentifier()));
                    isStableIdentifierModified = true;
                }
            }
            // Step 4: Now commit the instance itself
            // All new instances will not here due to step 2.
            DatabaseObject stored = commitAndAudit(username, databaseObject, !isUpdate);
            // Step 5: Commit all other new instances too.
            for (DatabaseObject newInstance : newInstances) {
                commitAndAudit(username, newInstance, true);
            }
                        
            // For the front end, we just need to return a SimpleInstance having attributes that may change
            SimpleInstance rtn = converter.convertInShell(stored);
            if (isStableIdentifierModified)
                rtn.setStableIdentifierModified(true);
            if (obj2id != null && obj2id.size() > 0) {
                Map<Long, Long> newInstOld2NewId = new HashMap<>();
                obj2id.forEach((obj, id) -> newInstOld2NewId.put(id, obj.getDbId()));
                if (newInstOld2NewId.containsKey(instance.getDbId()))
                    newInstOld2NewId.remove(instance.getDbId()); // Don't include itself
                rtn.setNewInstOld2NewId(newInstOld2NewId);
            }
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
    public SimpleInstance delete(@RequestBody SimpleInstance instance) {
        String username = getUsername();
        try {
            DatabaseObject obj = converter.convert(instance);
            InstanceEdit ie = converter.createInstanceEdit(instance);
            if (service.delete(obj, ie)) {
                auditLogger.logDelete(username, instance, true, null);
                SimpleInstance ieInst = converter.convert(ie);
                return ieInst;
            }
            throw new InstanceDeletionException(obj);
        }
        catch(Exception e) {
            logger.error("CurationController.delete: " + e.getMessage(), e);
            auditLogger.logDelete(username, instance, false, e.getMessage());
            throw new IllegalStateException(e.getMessage());
        }
    }
    
    /**
     * Delete one or more than one instances by parsing the Deleted object from the front end.
     * @param instance
     * @return
     */
    @PostMapping("deleteByDeleted")
    public SimpleInstance deleteByDeleted(@RequestBody SimpleInstance instance) {
        String username = getUsername();
        try {
            // Instance should be Deleted. Otherwise, let an exception be thrown.
            Deleted obj = (Deleted) converter.convert(instance);
            InstanceEdit ie = converter.createInstanceEdit(instance);
            Deleted rtn = service.deleteByDeleted(obj, ie);
            int count = obj.getDeletedInstanceDbId() == null ? 0 : obj.getDeletedInstanceDbId().size();
            // Use rtn so that we can get the dbId for Deleted instance.
            auditLogger.logBulkDelete(username, rtn, count, true, null);
            return converter.convert(rtn);
        }
        catch(Exception e) {
            logger.error("CurationController.deleteByDeleted: " + e.getMessage(), e);
            auditLogger.logBulkDelete(username, instance, 0, false, e.getMessage());
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
     * Fetch a pathway diagram for a given pathway dbId. The returned SimpleInstance 
     * is a shell object having only dbId, displayName, schemaClass. 
     * 
     */
    @GetMapping("fetchPathwayDiagramForPathway/{dbId}")
    public SimpleInstance fetchPathwayDiagramForPathway(@PathVariable("dbId") Long dbId) {
        SimpleInstance instance = service.fetchPathwayDiagramForPathway(dbId);
        if (instance == null)
            throw new DatabaseObjectNotFoundException(dbId);
        return instance;
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
                    attributeTypeList.add("instance");
                else if (service.isListType(className, attribute))
                    attributeTypeList.add("list");
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
    
    @GetMapping("loadInstances/{account}")
    public UserInstances loadInstances(@PathVariable("account") String account) {
        try {
            return service.loadUserInstances(account);
        }
        catch(Exception e) {
            logger.error("CurationController.loadInstances: " + e.getMessage(), e);
            return new UserInstances();
        }
    }

    // @GetMapping("loadPathwayDiagrams/{account}")
    // COMMENTED OUT: DiagramsPersistencePayload not defined
    // public List<DiagramsPersistencePayload> loadPathwayDiagrams(@PathVariable("account") String account) {
    //     try {
    //         UserInstances instances = service.loadUserInstances(account);
    //         if (instances == null || instances.getPathwayDiagrams() == null)
    //             return new ArrayList<>();
    //         return instances.getPathwayDiagrams();
    //     }
    //     catch (Exception e) {
    //         logger.error("CurationController.loadPathwayDiagrams: " + e.getMessage(), e);
    //         return new ArrayList<>();
    //     }
    // }

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
    
    @PostMapping("persistInstances/{account}")
    public void persistInstances(@RequestBody UserInstances instances,
                                 @PathVariable("account") String account) {
        try {
            service.persitInstances(instances, account);
        }
        catch(Exception e) {
            logger.error("CurationController.persistInstances: " + e.getMessage(), e);
        }
    }

    // /**
    //  * Persist a pathway diagram instance for a user session using the same flow as persistInstances.
    //  */
    // @PostMapping("persistPathwayDiagram/{account}")
    // COMMENTED OUT: DiagramsPersistencePayload not defined, service methods not available
    // public Boolean persistPathwayDiagram(@RequestBody List<DiagramsPersistencePayload> payload,
    //                                      @PathVariable("account") String account) {
    //     try {
    //         if (payload == null || payload.isEmpty())
    //             throw new IllegalArgumentException("Payload must be a non-empty array");
    //
    //         String username = getUsername();
    //         if (!Objects.equals(username, account))
    //             throw new IllegalArgumentException("Account path variable does not match the authenticated user");
    //
    //         List<DiagramsPersistencePayload> validatedPayloads = new ArrayList<>(payload.size());
    //         for (DiagramsPersistencePayload item : payload) {
    //             if (item == null || item.getNode() == null || item.getNode().isNull())
    //                 throw new IllegalArgumentException("Each payload item must contain a network JsonNode");
    //             Long pathwayDiagramId = item.getPathwayDiagramId();
    //             if (pathwayDiagramId == null || pathwayDiagramId <= 0)
    //                 throw new IllegalArgumentException("Each payload item must contain a valid pathwayDiagramId");
    //
    //             DiagramLock sessionLock = diagramLockService.getLock(pathwayDiagramId);
    //             if (sessionLock == null || !username.equals(sessionLock.getUsername()))
    //                 throw new IllegalArgumentException("No matching lock found for pathwayDiagramId " + pathwayDiagramId + " and user " + username);
    //
    //             validatedPayloads.add(new DiagramsPersistencePayload(item.getNode(), pathwayDiagramId));
    //         }
    //
    //         service.persistDiagramInstances(validatedPayloads, account);
    //         return Boolean.TRUE;
    //     }
    //     catch (Exception e) {
    //         logger.error("CurationController.persistPathwayDiagram: " + e.getMessage(), e);
    //         return Boolean.FALSE;
    //     }
    // }

    // /**
    //  * Persist/update a single pathway diagram instance for a user session.
    //  * Uses the same validation and lock checks as the batch endpoint.
    //  */
    // @PostMapping("persistSinglePathwayDiagram/{account}")
    // COMMENTED OUT: depends on persistPathwayDiagram which requires DiagramsPersistencePayload
    // public Boolean persistSinglePathwayDiagram(@RequestBody DiagramsPersistencePayload payload,
    //                                            @PathVariable("account") String account) {
    //     return persistPathwayDiagram(Collections.singletonList(payload), account);
    // }



    @GetMapping("getEventTree/{speciesName}")
    public List<SimpleInstance> getEventTree(@PathVariable("speciesName") String speciesName) {
        return service.getEventTree(speciesName);
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

    /**
     * Fetch all referrers of an instance
     * @param dbId
     * @return
     */
    @GetMapping("getReferrers/{dbId}")
    public Collection<NamedReferrerList> getReferrers(@PathVariable("dbId") Long dbId) throws Exception {
        Collection<NamedReferrerList> referrers =  service.getReferrers(dbId);
        return referrers;
    }

    @GetMapping(value = "exportEventDocx/{dbId}", produces = "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    public ResponseEntity<byte[]> exportEventDocx(@PathVariable("dbId") Long dbId) {
        try {
            DatabaseObject obj = service.findById(dbId);
            if (obj == null)
                throw new DatabaseObjectNotFoundException(dbId);
            if (!(obj instanceof Event))
                throw new IllegalArgumentException("Instance " + dbId + " is not an Event.");

            Event event = (Event) obj;
            byte[] content = eventDocxExportService.exportEventDocx(event);
            String filename = eventDocxExportService.buildFileName(event);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(eventDocxExportService.getDocxContentType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentLength(content.length)
                    .body(content);
        }
        catch (DatabaseObjectNotFoundException | IllegalArgumentException e) {
            throw e;
        }
        catch (Exception e) {
            logger.error("CurationController.exportEventDocx: " + e.getMessage(), e);
            throw new IllegalStateException(e.getMessage());
        }
    }

    /**
     * Find existing instances whose defining attributes match those of the passed instance.
     * The schema class of the passed instance is used to look up which attributes are
     * defining (ALL_DEFINING or ANY_DEFINING).  Only attributes that are both defining
     * and present in the passed instance's attribute map are included in the query.
     *
     * @param instance the candidate instance to match against
     * @return list of lightweight SimpleInstance shells (dbId, displayName, schemaClass)
     *         for each matched instance, or an empty list if none are found
     */
    @PostMapping("matchInstances")
    public List<SimpleInstance> matchInstances(@RequestBody SimpleInstance instance) {
        try {
            return service.findMatchedInstances(instance);
        }
        catch (Exception e) {
            logger.error("CurationController.matchInstances: " + e.getMessage(), e);
            throw new IllegalStateException(e.getMessage());
        }
    }

    //TODO; use the global exception handling
    @PostMapping("qaReport")
    public QAReport fetchQAReport(@RequestBody SimpleInstance instance)  {
        try {
            QAReport rtn = this.qaService.performQACheck(instance) ;
            return rtn;
        }
        catch(Exception e) {
            logger.error("QAController.qaReport: " + e.getMessage(), e);
            throw new IllegalStateException(e.getMessage());
        }
    }

    private String getUsername() {
        try {
            org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) {
                return auth.getName();
            }
        } catch (Exception e) {
            logger.error("Could not get username from security context: " + e.getMessage());
        }
        return "UNKNOWN";
    }

    private DatabaseObject commitAndAudit(String username, DatabaseObject obj, boolean isAdd) throws Exception {
        try {
            DatabaseObject saved = service.commit(obj);
            if (isAdd)
                auditLogger.logAdd(username, saved, true, null);
            else
                auditLogger.logUpdate(username, saved, true, null);
            return saved;
        }
        catch (Exception e) {
            if (isAdd)
                auditLogger.logAdd(username, obj, false, e.getMessage());
            else
                auditLogger.logUpdate(username, obj, false, e.getMessage());
            throw e;
        }
    }

    private boolean isAdd(DatabaseObject obj) {
        return obj == null || obj.getDbId() == null || obj.getDbId() <= 0;
    }
}
