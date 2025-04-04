package org.reactome.curation.qa;

import java.util.ArrayList;
import java.util.Collection;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;

public abstract class QAChecker {
    
    /**
     * The entry point for doing QA check.
     * @param instance
     * @return
     */
    public abstract QACheckResult performQACheck(SimpleInstance instance);
    
    /**
     * The QA checker name.
     * @return
     */
    public String getCheckName() {
        return this.getClass().getName();
    }
    
    public abstract Collection<String> getTargetClasses();
    
    public boolean shouldCheck(SimpleInstance instance) {
        return this.getTargetClasses().contains(instance.getSchemaClassName());
    }
    
    public QACheckResult getEmptyResult() {
        return new QACheckResult(getCheckName());
    }
    
    protected String getRelationships(SimpleInstance instance) {
        ArrayList<String> followAttributes = new ArrayList<>();
        String schemaClassName = instance.getSchemaClassName();
        switch (schemaClassName) {
            case ReactomeJavaConstants.Complex: {
                followAttributes.add(ReactomeJavaConstants.hasComponent);
                break;
            }
            case ReactomeJavaConstants.EntitySet: {
                followAttributes.add(ReactomeJavaConstants.hasMember);
                followAttributes.add(ReactomeJavaConstants.hasCandidate);
                break;
            }
            case ReactomeJavaConstants.Pathway: {
                followAttributes.add(ReactomeJavaConstants.hasEvent);
                break;
            }
            case ReactomeJavaConstants.ReactionlikeEvent: {
                followAttributes.add(ReactomeJavaConstants.input);
                followAttributes.add(ReactomeJavaConstants.output);
                followAttributes.add(ReactomeJavaConstants.catalystActivity);
                followAttributes.add(ReactomeJavaConstants.physicalEntity);
                followAttributes.add(ReactomeJavaConstants.regulatedBy);
                followAttributes.add(ReactomeJavaConstants.regulator);
                break;
            }
        }
        if (followAttributes.isEmpty())
            return null;
        String relationships = String.join("|", followAttributes);
        return relationships;
    }

}
