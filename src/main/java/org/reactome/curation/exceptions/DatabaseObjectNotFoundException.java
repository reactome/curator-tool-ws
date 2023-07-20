package org.reactome.curation.exceptions;

import org.reactome.server.graph.domain.model.DatabaseObject;

/**
 * Throw this exception when a DatabaseObject doesn't exit 
 * @author wug
 *
 */
@SuppressWarnings({"serial" })
public class DatabaseObjectNotFoundException extends RuntimeException {
    private Long dbId;
    private String stId;
    private String displayName;
    
    public DatabaseObjectNotFoundException(DatabaseObject obj) {
        this.dbId = obj.getDbId();
        this.stId = obj.getStId();
        this.displayName = obj.getDisplayName();
    }

    @Override
    public String getMessage() {
        return this.toString();
    }

    @Override
    public String toString() {
        return "Cannot find DatabaseObject with dbId, stId, and displayName: " +
               this.dbId + ", " + this.stId + ", " + this.displayName;
    }

}
