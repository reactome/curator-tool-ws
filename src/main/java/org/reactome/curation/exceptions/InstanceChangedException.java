package org.reactome.curation.exceptions;

import org.reactome.server.graph.domain.model.DatabaseObject;

/**
 * This exception is thrown when a user wants to update an existing instance by this instance has been updated by 
 * another user.
 */
@SuppressWarnings("serial")
public class InstanceChangedException extends DatabaseObjectException {
    
    public InstanceChangedException(DatabaseObject obj) {
        super(obj);
    }
    
    
    @Override
    public String toString() {
        return "Instance has been updated elsewhere and cannot be committed (dbId, stId, displayName): " +
               this.dbId + ", " + this.stId + ", " + this.displayName;
    }

}
