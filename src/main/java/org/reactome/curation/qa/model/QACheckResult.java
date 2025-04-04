package org.reactome.curation.qa.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QACheckResult {
    private String checkName;
    private String[] columns;
    private List<String[]> rows;

    public QACheckResult(String checkName, String[] columns, List<String[]> rows) {
        this.checkName = checkName;
        this.columns = columns;
        this.rows = rows;
    }
    
    public QACheckResult(String checkName) {
        this.checkName = checkName;
    }
    
    public boolean isPassed() {
        return rows == null || rows.size() == 0;
    }
    
}
