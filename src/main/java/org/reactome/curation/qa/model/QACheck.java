package org.reactome.curation.qa.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QACheck {
    private String checkName;
    private Boolean checkPassed;
    private String[] columns;
    private String[][] rows;


    public QACheck(String checkName,
                   boolean checkPassed,
                   String[] columns,
                   String[][] rows) {
        this.checkName = checkName;
        this.checkPassed = checkPassed;
        this.columns = columns;
        this.rows = rows;
    }
}
