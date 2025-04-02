package org.reactome.curation.qa;

import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;

public interface QAChecker {
    
    public QACheckResult performQACheck(SimpleInstance instance);

}
