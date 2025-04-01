package org.reactome.curation.qa.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.reactome.server.graph.domain.model.DatabaseObject;

import java.util.ArrayList;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QAReport {

    private DatabaseObject instance;
    // Map the test that was run to the issue(s) that occurred more specifically
    // If no issues the array will be size=0
    private ArrayList<QACheck> testsRun;

    public QAReport(DatabaseObject databaseObject, ArrayList<QACheck> testsRun) {
        this.instance = databaseObject;
        this.testsRun = testsRun;
    }
}
