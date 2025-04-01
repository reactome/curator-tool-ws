package org.reactome.curation.qa;

import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QAReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

public class QAController {

    private static final Logger logger = LoggerFactory.getLogger(QAController.class);
    @Autowired
    private QAService service;

    @PostMapping("qaReport")
    public QAReport fetchQAReport(@RequestBody SimpleInstance instance)  {
        try {
            QAReport rtn = this.service.createQAReport(instance) ;
            return rtn;
        }
        catch(Exception e) {
            logger.error("QAController.qaReport: " + e.getMessage(), e);
            throw new IllegalStateException(e.getMessage());
        }
    }
}

//public SimpleInstance commit(@RequestBody SimpleInstance instance)  {
//    // Check if the passed instance can be committed
//    if (this.service.isConflictWithStored(instance))
//        throw new InstanceChangedException(instance);
//    try {
//        DatabaseObject databaseObject = converter.convert(instance, true);
//        Set<DatabaseObject> newInstances = service.grepNewInstances(databaseObject);
//        // Keep the old dbIds
//        Map<DatabaseObject, Long> obj2id = null;
//        if (newInstances != null && newInstances.size() > 0) {
//            obj2id = new HashMap<>();
//            for (DatabaseObject obj : newInstances)
//                obj2id.put(obj, obj.getDbId());
//        }
//        DatabaseObject stored = service.commit(databaseObject);
//        // For the front end, we just need to return a SimpleInstance having attributes that may change
//        SimpleInstance rtn = converter.convertInShell(stored);
//        if (obj2id != null && obj2id.size() > 0) {
//            Map<Long, Long> newInstOld2NewId = new HashMap<>();
//            obj2id.forEach((obj, id) -> newInstOld2NewId.put(id, obj.getDbId()));
//            if (newInstOld2NewId.containsKey(instance.getDbId()))
//                newInstOld2NewId.remove(instance.getDbId()); // Don't include itself
//            rtn.setNewInstOld2NewId(newInstOld2NewId);
//        }
//        return rtn;
//    }
//    catch(Exception e) {
//        logger.error("CurationController.commit: " + e.getMessage(), e);
//        throw new IllegalStateException(e.getMessage());
//    }
//}
