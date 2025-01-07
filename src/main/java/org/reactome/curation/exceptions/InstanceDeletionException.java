package org.reactome.curation.exceptions;

import org.reactome.server.graph.domain.model.DatabaseObject;

@SuppressWarnings("serial")
public class InstanceDeletionException extends DatabaseObjectException {
    
    public InstanceDeletionException(DatabaseObject obj) {
        super(obj);
    }
    
    @Override
    public String toString() {
        return "Error in deleting instance (dbId, stId, displayName): " +
               this.dbId + ", " + this.stId + ", " + this.displayName;
    }

}
