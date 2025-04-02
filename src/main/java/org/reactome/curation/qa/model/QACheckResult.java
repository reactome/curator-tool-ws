package org.reactome.curation.qa.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QACheckResult {
    private String checkName;
    private String[] columns;
    private String[][] rows;

    public QACheckResult(String checkName, String[] columns, String[][] rows) {
        this.checkName = checkName;
        this.columns = columns;
        this.rows = rows;
    }
    
    public QACheckResult(String checkName) {
        this.checkName = checkName;
    }
    
    public boolean isPassed() {
        return rows == null || rows.length == 0;
    }
    
}
