package org.reactome.curation.qa;


import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.reactome.curation.qa.model.QAReport;
import org.reactome.server.graph.domain.model.Complex;
import org.reactome.server.graph.domain.model.EntitySet;
import org.reactome.server.graph.domain.model.ReactionLikeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;

/**
 * The compartment check is quite complicated. The found issue is summarized in a single text. However, the detailed information
 * may need to find in the generic table. For the time, the table is used to report all compartment assignment for the curators 
 * to determine the cause. This may be enhanced in the future.
 * Different classes may have their own logic to determine the issue. Therefore, each class has its own check.
 *
 */
public class CompartmentChecker extends QAChecker {
    private static final Logger logger = LoggerFactory.getLogger(CompartmentChecker.class);
    // Cache the helper
    private Map<Class<?>, CompartmentCheckHelper> cls2helper = new HashMap<>();

    private Neo4jClient neo4jClient;

    public CompartmentChecker(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @Override
    public String getCheckName() {
        return "Compartment Check";
    }
    
    
    private CompartmentCheckHelper getHelper(SimpleInstance inst) {
        Class<?> cls = inst.getGraphModelClass();
        if (ReactionLikeEvent.class.isAssignableFrom(cls)) {
            CompartmentCheckHelper helper = cls2helper.get(ReactionLikeEvent.class);
            if (helper == null) {
                helper = new ReactionCompartmentCheckHelper();
                cls2helper.put(ReactionLikeEvent.class, helper);
            }
            return helper;
        }
        if (EntitySet.class.isAssignableFrom(cls)) {
            CompartmentCheckHelper helper = cls2helper.get(EntitySet.class);
            if (helper == null) {
                helper = new EntitySetCompartmentCheckHelper();
                cls2helper.put(EntitySet.class, helper);
            }
            return helper;
        }
        if (Complex.class.isAssignableFrom(cls)) {
            CompartmentCheckHelper helper = cls2helper.get(Complex.class);
            if (helper == null) {
                helper = new ComplexCompartmentCheckHelper();
                cls2helper.put(ReactionLikeEvent.class, helper);
            }
            return helper;
        }
        return null;
    }
    
    @Override
    public Collection<Class<?>> getTargetClasses() {
        Class<?>[] classes = {
                ReactionLikeEvent.class,
                Complex.class,
                EntitySet.class
        };
        return Stream.of(classes).collect(Collectors.toSet());
    }

    @Override
    public QACheckResult performQACheck(SimpleInstance instance) {
        if (!shouldCheck(instance))
            return null;
        
        String relationships = getContainerRelationships(instance);
        // Just in case
        if (relationships == null) {
            logger.error("Cannot find any relationship: " + instance);
            throw new IllegalArgumentException("Cannot find any relationship: " + instance);
        }

        return checkCompartment(instance, relationships, instance.getSchemaClassName());
    }

    private QACheckResult checkCompartment(SimpleInstance inst, String followAttributes, String schemaClass) {
        CompartmentCheckHelper helper = getHelper(inst);
        if (helper == null)
            return null;
        
        String query = String.format("MATCH (container:%s {dbId: %d})\n"
                + "OPTIONAL MATCH (container)-[:compartment]->(compartment:Compartment)\n"
                + "WITH container, compartment AS containerLocation\n"
                + "OPTIONAL MATCH (container)-[:includedLocation]->(ilc:Compartment)\n"
                + "WITH container, containerLocation, ilc AS includedLocation\n"
                + "OPTIONAL MATCH (container)-[r:%s*]->(contained:PhysicalEntity)\n"
                + "WITH container, containerLocation, includedLocation, COLLECT(contained) AS containeds, r\n"
                + "UNWIND containeds AS pe\n" 
                + "UNWIND r AS role\n"
                + "OPTIONAL MATCH (pe)-[:compartment]->(containedCompartments:Compartment)\n"
                + "WITH container, containerLocation, includedLocation, COLLECT(DISTINCT containedCompartments) AS cCompartment, role, pe\n"
                + "UNWIND cCompartment as containedLocation\n"
                + "RETURN containerLocation.dbId, containerLocation.displayName, "
                + "includedLocation.dbId, includedLocation.displayName, "
                + "containedLocation.dbId, containedLocation.displayName, "
                + "TYPE(role) AS relationshipType, "
                + "pe.displayName, pe.dbId",
                schemaClass, inst.getDbId(), followAttributes);

        Collection<Map<String, Object>> all = neo4jClient.query(query).fetch().all();
        if (all.isEmpty())
            return getEmptyResult();

        Map<Long, SimpleInstance> containerId2Comp = new HashMap<>();
        Map<Long, SimpleInstance> includedId2Comp = new HashMap<>();
        Map<String, SimpleInstance> idRole2Contained = new HashMap<>();
        Map<Long, SimpleInstance> containedId2Comp = new HashMap<>();
        
        for (Map<String, Object> map : all) {
            // Handle container compartments
            String containerCompDbIdText = map.get("containerLocation.dbId").toString();
            Long containerCompDbId = Long.parseLong(containerCompDbIdText);
            if (!containerId2Comp.containsKey(containerCompDbId)) {
                // Create a simple instance as a data structure for the container, add to list
                SimpleInstance containerCompt = new SimpleInstance();
                containerCompt.setDbId(containerCompDbId);
                containerCompt.setDisplayName(map.get("containerLocation.displayName").toString());
                containerId2Comp.put(containerCompt.getDbId(), containerCompt);
            }
            if (map.get("includedLocation.dbId") != null) {
                String includedLocationDbIdText = map.get("includedLocation.dbId").toString();
                Long includedLocDbId = Long.parseLong(includedLocationDbIdText);
                if (!includedId2Comp.containsKey(includedLocDbId)) {
                    // Create an entity as a data structure for the container, add to list
                    SimpleInstance containerCompt = new SimpleInstance();
                    containerCompt.setDbId(includedLocDbId);
                    containerCompt.setDisplayName(map.get("includedLocation.displayName").toString());
                    includedId2Comp.put(containerCompt.getDbId(), containerCompt);
                }
            }
            // Parse the contained PE
            String containedId = map.get("pe.dbId").toString();
            String role = map.get("relationshipType").toString();
            String key = containedId + ":" + role;
            SimpleInstance contained = idRole2Contained.get(key);
            if (contained == null) {
                String containedName = map.get("pe.displayName").toString();
                contained = new SimpleInstance();
                contained.setDisplayName(containedName);
                contained.setDbId(Long.parseLong(containedId));
                contained.setAttribute("role", role);
                idRole2Contained.put(key, contained);
            }
            // Parse contained compartment
            Long containedCompId = Long.parseLong(map.get("containedLocation.dbId").toString());
            @SuppressWarnings("unchecked")
            List<SimpleInstance> containedComps = (List<SimpleInstance>) contained.getAttribute(ReactomeJavaConstants.compartment);
            if (containedComps == null) {
                containedComps = new ArrayList<>();
                contained.setAttribute(ReactomeJavaConstants.compartment, containedComps);
            }
            boolean found = contains(containedComps, containedCompId);
            if (!found) {
                String containedCompName = map.get("containedLocation.displayName").toString();
                // Create a simple instance to model the component and add to list
                SimpleInstance containedComp = new SimpleInstance();
                containedComp.setDbId(containedCompId);
                containedComp.setDisplayName(containedCompName);
                containedComps.add(containedComp);
                // The map should be a subset of this
                if (!containedId2Comp.containsKey(containedComp.getDbId()))
                    containedId2Comp.put(containedComp.getDbId(), containedComp);
            }
        }

        helper.setNeo4jClient(neo4jClient);
        QACheckResult result = helper.checkCompartment(containerId2Comp, 
                includedId2Comp, 
                idRole2Contained, 
                containedId2Comp);
        result.setCheckName(getCheckName());
        return result;
    }

}