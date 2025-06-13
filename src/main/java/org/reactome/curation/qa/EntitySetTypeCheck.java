package org.reactome.curation.qa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.reactome.server.graph.domain.model.EntitySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This check is to ensure that an EntitySet has members of the same type.
 * It will report if there are different types of members in an EntitySet.
 * Note: Set inside an EntitySet is allowed, so this check will not report.
 * 
 * @author gwu
 */
public class EntitySetTypeCheck extends QAChecker {
    private static final Logger logger = LoggerFactory.getLogger(EntitySetTypeCheck.class);
    
    public EntitySetTypeCheck() {
    }
    
    @Override
    public String getCheckName() {
        return "EntitySet Type Check";
    }

    @Override
    protected QACheckResult _performQACheck(SimpleInstance instance) {
        if (!shouldCheck(instance))
            return null;
        
        String relationships = getContainerRelationships(instance);
        // Just in case
        if (relationships == null) {
            logger.error("Cannot find any relationship: " + instance);
            throw new IllegalArgumentException("Cannot find any relationship: " + instance);
        }
        return checkSetType(instance, relationships, instance.getSchemaClassName());
    }
    
    private QACheckResult checkSetType(SimpleInstance inst, String followAttributes, String schemaClass) {
        String query = String.format("MATCH (container:%s {dbId: %d})\n"
                + "OPTIONAL MATCH (container)-[r:%s*]->(contained:PhysicalEntity)\n"
                + "UNWIND r AS rel\n"
                + "RETURN DISTINCT TYPE(rel) AS relType, contained.displayName, contained.dbId, contained.schemaClass",
                schemaClass, inst.getDbId(), followAttributes);
        
        Collection<Map<String, Object>> all = getNeoj4Client().query(query).fetch().all();

        if (all.isEmpty())
            return getEmptyResult();
        // Check if the types (schemaClass names) are the same across the reference graph.
        Set<String> allTypeNames = new HashSet<>();
        allTypeNames.add(inst.getSchemaClassName());
        for (Map<String, Object> row : all) {
            String clsName = (String) row.get("contained.schemaClass");
            // Escape Set
            if (clsName.equals(ReactomeJavaConstants.DefinedSet) || 
                clsName.equals(ReactomeJavaConstants.CandidateSet) ||
                clsName.equals(ReactomeJavaConstants.EntitySet)) // This should not happen
                continue; // Skip DefinedSet and CandidateSet
            allTypeNames.add(clsName);
        }
        if (allTypeNames.size() == 0 || allTypeNames.size() == 1)
            return getEmptyResult(); // Nothing to report
        // Should display whatever we have collected from the database
        String[] colNames = { "ReferredBy", "Reference", "SchemaClass" };
        List<String[]> rows = new ArrayList<>();
        // There is no need to report the container here since it doesn't matter here.
        // Repeat again to get the report.
        for (Map<String, Object> row : all) {
            String relType = (String) row.get("relType");
            String displayName = (String) row.get("contained.displayName");
            String clsName = (String) row.get("contained.schemaClass");
            if (clsName.equals(ReactomeJavaConstants.DefinedSet) || 
                clsName.equals(ReactomeJavaConstants.CandidateSet) ||
                clsName.equals(ReactomeJavaConstants.EntitySet)) // This should not happen
                continue; // Skip DefinedSet and CandidateSet
            Long dbId = (Long) row.get("contained.dbId");
            SimpleInstance contained = new SimpleInstance();
            contained.setDbId(dbId);
            contained.setDisplayName(displayName);
            String[] currentRow = new String[] {
              relType,
              contained.toString(),
              clsName
            };
            rows.add(currentRow);
        }
        return createResult(colNames, rows);
    }

    @Override
    public Collection<Class<?>> getTargetClasses() {
        Class<?>[] classes = {
                EntitySet.class
        };
        return Stream.of(classes).collect(Collectors.toSet());
    }
    
    

}
