package org.reactome.curation.exceptions;

import org.reactome.server.graph.domain.model.DatabaseObject;

/**
 * Throw this exception when a DatabaseObject doesn't exit 
 * @author wug
 *
 */
@SuppressWarnings({"serial" })
public class DatabaseObjectNotFoundException extends DatabaseObjectException {
    
    public DatabaseObjectNotFoundException(DatabaseObject obj) {
        super(obj);
    }

    public DatabaseObjectNotFoundException(Long dbId) {
        super(dbId);
    }
    

    @Override
    public String toString() {
        return "Cannot find DatabaseObject with dbId and displayName: " +
               this.dbId + ", " + this.displayName;
    }

}
