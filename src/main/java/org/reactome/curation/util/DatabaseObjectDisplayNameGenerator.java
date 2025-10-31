package org.reactome.curation.util;

import java.util.List;

import org.gk.schema.InvalidAttributeException;
import org.reactome.server.graph.domain.model.DeletedInstance;
import org.reactome.server.graph.domain.model.Taxon;

public class DatabaseObjectDisplayNameGenerator {
    
    /**
     * Ported from InstanceDisplayNameGenerator.generateDeletedInstanceName() in the Java desktop app.
     * @param instance
     * @return
     * @throws InvalidAttributeException
     * @throws Exception
     */
    public static String generateDeletedInstanceName(DeletedInstance instance) throws InvalidAttributeException, Exception {
        StringBuilder displayName = new StringBuilder();
        displayName.append("Deleted Instance - [");
        String clsName = instance.getClazz();
        displayName.append(clsName).append(": ");
        String name = instance.getName();
        displayName.append(name).append(" (");
        Integer dbId = instance.getDeletedInstanceDbId(); 
        displayName.append(dbId).append(")");
        // Sometimes we may don't have species
        List<Taxon> species = instance.getSpecies(); // This should be a list
        if (species == null || species.isEmpty())
            displayName.append("]");
        else {
            displayName.append(" - ");
            displayName.append(species.get(0).getDisplayName()).append("]"); // Use the first one
        }
        return displayName.toString();
    }

}
