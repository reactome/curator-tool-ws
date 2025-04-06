package org.reactome.curation.qa;

import java.util.Map;
import java.util.Set;

import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;

/**
 * A utility class for EntitySet compartment checker.
 */
public class ComplexCompartmentCheckHelper implements CompartmentCheckHelper {
    
    public ComplexCompartmentCheckHelper() {
        
    }
    
    @Override 
    public QACheckResult checkCompartment(Map<Long, SimpleInstance> containerId2Comp,
                                          Map<Long, SimpleInstance> includedId2Comp,
                                          Map<String, SimpleInstance> idRole2Contained,
                                          Map<Long, SimpleInstance> containedId2Comp) {
        return null;
        // Check adjacency
//      Collection<String> nonAdjacent = new ArrayList<>();
//      for (String containerDbId : complexCompartments) {
//          String surroundByQuery = String.format("MATCH (compartment:Compartment {dbId: %d})\n"
//                  + "OPTIONAL MATCH (compartment)-[:surroundedBy]->(s:Compartment)\n"
//                  + "WITH compartment, s AS allowedCompartments\n"
//                  + "return allowedCompartments.dbId, compartment.dbId", Long.parseLong(containerDbId));
//
//          Collection<Map<String, Object>> allSurroundedBy = neo4jClient.query(surroundByQuery).fetch().all();
//          List<String> neighbors = new ArrayList<>();
//          for (Map<String, Object> map : allSurroundedBy) {
//              if (!((map.get("complexLocation.dbId")) == null)) {
//                  String surroundByContainerDbId = map.get("complexLocation.dbId").toString();
//                  neighbors.add(surroundByContainerDbId);
//              }
//          }
//
//          for (String complexId : complexCompartments) {
//              if (!neighbors.contains(complexId)) {
//                  nonAdjacent.add(complexId);
//              }
//          }
//      }
    }

}
