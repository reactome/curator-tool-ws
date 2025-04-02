package org.reactome.curation.qa;

import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;

public abstract class QAChecker {
    
    public abstract QACheckResult performQACheck(SimpleInstance instance);
    
    public abstract String getCheckName();
    
    public QACheckResult getEmptyResult() {
        return new QACheckResult(getCheckName());
    }

}
