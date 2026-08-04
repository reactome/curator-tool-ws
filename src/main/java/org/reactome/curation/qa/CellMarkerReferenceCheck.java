package org.reactome.curation.qa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.reactome.server.graph.domain.model.Cell;

public class CellMarkerReferenceCheck extends QAChecker {
    
    public CellMarkerReferenceCheck() {}
    
    @Override
    public String getCheckName() {
        return "Cell Marker Reference Check";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected QACheckResult _performQACheck(SimpleInstance instance) {
        String query = String.format(
                "MATCH (cell:%s {dbId: %d})-[:markerReference]->(mr:MarkerReference)\n"
                + "OPTIONAL MATCH (mr)-[:cell]->(markerCell:Cell), \n"
                + "               (mr)-[:marker]->(mrMarker:PhysicalEntity), \n"
                + "               (cell)-[:proteinMarker|RNAMarker]->(cellMarker:PhysicalEntity)\n"
                + "RETURN \n"
                + "  cell.dbId, cell.displayName, \n"
                + "  mr.dbId, mr.displayName, \n"
                + "  markerCell.dbId, markerCell.displayName, \n"
                + "  mrMarker.dbId, mrMarker.displayName, \n"
                + "  cellMarker.dbId, cellMarker.displayName", 
                instance.getSchemaClassName(), instance.getDbId());
        
        Collection<Map<String, Object>> results = getNeoj4Client().query(query).fetch().all();
        
        if (results.isEmpty()) {
            return getEmptyResult(); // No issues found
        }
        
        Map<Long, SimpleInstance> markerReferences = new HashMap<>();
        for (Map<String, Object> row : results) {
            Long mrDbId = (Long) row.get("mr.dbId");
            if (mrDbId == null) {
                continue; // Skip if no MarkerReference found
            }
            String mrDisplayName = (String) row.get("mr.displayName");
            SimpleInstance markerReference = markerReferences.get(mrDbId);   
            if (markerReference == null) {
                markerReference = new SimpleInstance();
                markerReference.setDbId(mrDbId);
                markerReference.setDisplayName(mrDisplayName);
                markerReferences.put(mrDbId, markerReference);
            }
            
            // Fill the information for MarkerReference
            parseReference(markerReference, "cell", "markerCell", row);
            parseReference(markerReference, "marker", "mrMarker", row);
            // Fill the information for Cell
            parseReference(instance, "marker", "cellMarker", row);
        }
        
        String[] colNames = { "MarkerReference", "Issue"};
        List<String[]> rows = new ArrayList<>();
        Set<SimpleInstance> cellMarkers = (Set<SimpleInstance>) instance.getAttribute("marker");
        // For each comparison
        if (cellMarkers == null) 
            cellMarkers = new HashSet<>();
        for (SimpleInstance markerReference : markerReferences.values()) {
            Set<SimpleInstance> cells = (Set<SimpleInstance>) markerReference.getAttribute("cell");
            Set<SimpleInstance> markers = (Set<SimpleInstance>) markerReference.getAttribute("marker");
            
            // Check if cells contains the checked instance
            if (cells != null && !cells.contains(instance)) {
                String issue = "MarkerReference doesn't refer to the Cell instance";
                rows.add(new String[] { 
                    markerReference.toString(), 
                    issue 
                });
            }
            
            // Check if MarkerReference and Cell refer to the same marker (at least one is shared)
            if (markers == null || markers.isEmpty()) {
                String issue = "MarkerReference does not have any markers";
                rows.add(new String[] { 
                    markerReference.getDisplayName(), 
                    issue 
                });
            }
            else {
                // Since we are using this set only once, we can use retainAll to check if there is any intersection
                markers.retainAll(cellMarkers);
                if (markers.isEmpty()) {
                    String issue = "MarkerReference markers do not match Cell markers";
                    rows.add(new String[] { 
                        markerReference.toString(), 
                        issue 
                    });
                }
            }
        }
        if (rows.isEmpty()) {
            return getEmptyResult(); // No issues found
        }
        return createResult(colNames, rows);
    }
    
    private void parseReference(SimpleInstance inst, String attribute, String key, Map<String, Object> row) {
        Long dbId = (Long) row.get(key + ".dbId");
        if (dbId == null) {
            return; // No dbId found, nothing to do
        }
        Set<SimpleInstance> refs = (Set<SimpleInstance>) inst.getAttribute(attribute);
        if (refs == null) {
            refs = new HashSet<>();
            inst.setAttribute(attribute, refs);
        }
        SimpleInstance ref = new SimpleInstance();
        ref.setDbId(dbId);
        ref.setDisplayName((String) row.get(key + ".displayName"));
        if (refs.contains(ref)) { // hashcode of SimpleInstance is based on dbId and displayName, so we can use it as a key
            return; // Already added, no need to add again
        }
        refs.add(ref); 
    }

    @Override
    public Collection<Class<?>> getTargetClasses() {
        return Collections.singletonList(Cell.class);
    }
    

}
