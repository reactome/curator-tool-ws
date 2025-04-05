package org.reactome.curation.qa.model;

import java.util.List;

import org.reactome.server.graph.domain.model.DatabaseObject;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QAReport {

    private DatabaseObject instance;
    // Map the test that was run to the issue(s) that occurred more specifically
    // If no issues the array will be size=0
    private List<QACheckResult> qaResults;

    public QAReport(DatabaseObject databaseObject, List<QACheckResult> qaResults) {
        this.instance = databaseObject;
        this.qaResults = qaResults;
    }
}
