package org.reactome.curation.qa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.reactome.curation.qa.model.QAReport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

@Service
public class QAService {
    
    @Autowired
    private Neo4jClient neo4jClient;
    
    // Used to control configured QA checkers
    private Map<String, QAChecker> qaName2Checker;
    
    public QAService() {
    }
    
    //TODO: To be updated by using a YML configuration file.
    private void initQACheckers() {
        qaName2Checker = new HashMap<>();
        QAChecker checker = new SpeciesChecker();
        checker.setNeo4jClient(neo4jClient);
        qaName2Checker.put(checker.getCheckName(), checker);
        checker = new CompartmentChecker();
        checker.setNeo4jClient(neo4jClient);
        qaName2Checker.put(checker.getCheckName(), checker);
        checker = new EntitySetTypeCheck();
        checker.setNeo4jClient(neo4jClient);
        qaName2Checker.put(checker.getCheckName(), checker);
        checker = new EntityFunctionalStatusNormalEntityCheck();
        checker.setNeo4jClient(neo4jClient);
        qaName2Checker.put(checker.getCheckName(), checker);
        checker = new EntityFunctionalStatusDiseaseEntityCheck();
        checker.setNeo4jClient(neo4jClient);    
        qaName2Checker.put(checker.getCheckName(), checker);
        checker = new ReviewStatusSlotCheck();
        checker.setNeo4jClient(neo4jClient);
        qaName2Checker.put(checker.getCheckName(), checker);
        checker = new CellMarkerReferenceCheck();
        checker.setNeo4jClient(neo4jClient);
        qaName2Checker.put(checker.getCheckName(), checker);
    }

    
    public QAReport performQACheck(SimpleInstance instance) {
        if (qaName2Checker == null)
            this.initQACheckers();
        ArrayList<QACheckResult> results = new ArrayList<>();
        for (String name : qaName2Checker.keySet()) {
            QACheckResult result = qaName2Checker.get(name).performQACheck(instance);
            if (result == null)
                continue; // We will return an empty result so that the front-end know that check is passsed.
            results.add(result);
        }
        QAReport qaReport = new QAReport(instance, results);
        return qaReport;
    }
}
