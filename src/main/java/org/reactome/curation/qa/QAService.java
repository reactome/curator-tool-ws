package org.reactome.curation.qa;

import org.reactome.curation.qa.model.QAReport;
import org.reactome.curation.qa.model.QACheck;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

@Service
public class QAService {

    @Autowired
    private SpeciesCheck speciesCheck;
    @Autowired
    private CompartmentCheck compartmentCheck;

    public QAReport createQAReport(DatabaseObject databaseObject) {
        // TODO: The tests to be run need to be configured based on the schema class, for now testing species
        ArrayList<QACheck> checks = new ArrayList<>();
        QACheck speciesCheckTest = this.speciesCheck.checkInstanceType(databaseObject);
        QACheck complexCheckTest = this.compartmentCheck.checkInstanceType(databaseObject);
        checks.add(speciesCheckTest);
        checks.add(complexCheckTest);
        QAReport qaReport = new QAReport(databaseObject, checks);
        return qaReport;
    }
}
