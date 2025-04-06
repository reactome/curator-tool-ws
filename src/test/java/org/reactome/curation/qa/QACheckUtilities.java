package org.reactome.curation.qa;

import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.reactome.curation.qa.model.QAReport;
import org.reactome.curation.service.CurationService;
import org.reactome.server.graph.domain.model.DatabaseObject;

public class QACheckUtilities {

    public static void performQACheck(Long dbId, 
                                      CurationService curationService,
                                      QAService qaService) {
        DatabaseObject obj = curationService.findById(dbId);
        SimpleInstance inst = new SimpleInstance();
        inst.setDbId(obj.getDbId());
        inst.setDisplayName(obj.getDisplayName());
        inst.setSchemaClassName(obj.getSchemaClass());
        QAReport report = qaService.performQACheck(inst);
        System.out.println("\nQA Result for " + obj);
        for (QACheckResult result : report.getQaResults()) {
            System.out.println();
            System.out.println("Checker: " + result.getCheckName());
            System.out.println("Issue: " + result.getIssue());
            if (result.isPassed()) {
                System.out.println("passed!");
                continue;
            }
            System.out.println("Columns: " + String.join("; ", result.getColumns()));
            for (String[] row : result.getRows()) {
                System.out.println("Row: " + String.join("; ", row));
            }
        }
    }

}
