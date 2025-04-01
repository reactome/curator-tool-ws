package org.reactome.curation.qa.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QACheckAttributes {
    private String issueName;
    private String issueDetails;

    public QACheckAttributes(String issueName, String issueDetails) {
        this.issueName = issueName;
        this.issueDetails = issueDetails;
    }
}
