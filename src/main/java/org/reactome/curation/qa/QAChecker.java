package org.reactome.curation.qa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.reactome.server.graph.domain.model.Complex;
import org.reactome.server.graph.domain.model.EntitySet;
import org.reactome.server.graph.domain.model.Pathway;
import org.reactome.server.graph.domain.model.ReactionLikeEvent;

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
    
    /**
     * Utility method. Place here for easy access without creating a new small class. 
     * @param instances
     * @param dbId
     * @return
     */
    public static final boolean contains(List<SimpleInstance> instances, Long dbId) {
        for (SimpleInstance inst : instances) {
            if (inst.getDbId().equals(dbId))
                return true;
        }
        return false;
    }
    
    public abstract Collection<Class<?>> getTargetClasses();
    
    protected Collection<Class<?>> getContainerLikeClasses() {
        Class<?>[] classes = {
                ReactionLikeEvent.class,
                Complex.class,
                Pathway.class,
                EntitySet.class
        };
        return Stream.of(classes).collect(Collectors.toSet());
    }
    
    /**
     * Validate the role between container and contained, e.g. between a reaction and its catalyst.
     * @param role
     * @return
     */
    protected String validateContainerContainedRole(String role) {
        if (role.equals(ReactomeJavaConstants.catalystActivity) || role.equals(ReactomeJavaConstants.regulatedBy))
            return null; // For reaction. We don't want to show that since they are the intermediate step
        if (role.equals(ReactomeJavaConstants.physicalEntity))
            return "catalyst"; // For easy to understanding. PE is too generic.
        return role;
    }
    
    public boolean shouldCheck(SimpleInstance instance) {
        Class<?> instanceCls = instance.getGraphModelClass();
        for (Class<?> cls : getTargetClasses()) {
            if (cls.isAssignableFrom(instanceCls))
                return true;
        }
        return false;
    }
    
    public QACheckResult getEmptyResult() {
        return new QACheckResult(getCheckName());
    }
    
    protected QACheckResult createResult(String[] colNames, List<String[]> rows) {
        return new QACheckResult(getCheckName(), colNames, rows);
    }
    
    protected String getContainerRelationships(SimpleInstance instance) {
        Class<?> graphModelCls = instance.getGraphModelClass();
        if (graphModelCls == null)
            return null;
        List<String> followAttributes = new ArrayList<>();
        if (Complex.class.isAssignableFrom(graphModelCls)) {
            followAttributes.add(ReactomeJavaConstants.hasComponent);
        }
        else if (EntitySet.class.isAssignableFrom(graphModelCls)) {
            followAttributes.add(ReactomeJavaConstants.hasMember);
            followAttributes.add(ReactomeJavaConstants.hasCandidate);
        }
        else if (Pathway.class.isAssignableFrom(graphModelCls)) {
            followAttributes.add(ReactomeJavaConstants.hasEvent);
        }
        else if (ReactionLikeEvent.class.isAssignableFrom(graphModelCls)) {
            followAttributes.add(ReactomeJavaConstants.input);
            followAttributes.add(ReactomeJavaConstants.output);
            followAttributes.add(ReactomeJavaConstants.catalystActivity);
            followAttributes.add(ReactomeJavaConstants.physicalEntity);
            followAttributes.add(ReactomeJavaConstants.regulatedBy);
            followAttributes.add(ReactomeJavaConstants.regulator);
        }
        if (followAttributes.isEmpty())
            return null;
        String relationships = String.join("|", followAttributes);
        return relationships;
    }

}
