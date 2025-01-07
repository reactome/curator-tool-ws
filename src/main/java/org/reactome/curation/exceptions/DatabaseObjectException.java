package org.reactome.curation.exceptions;

import org.reactome.server.graph.domain.model.DatabaseObject;

@SuppressWarnings("serial")
public class DatabaseObjectException extends RuntimeException {
    Long dbId;
    String stId;
    String displayName;
    
    public DatabaseObjectException(DatabaseObject obj) {
        this.dbId = obj.getDbId();
        this.stId = obj.getStId();
        this.displayName = obj.getDisplayName();
    }
    
    public DatabaseObjectException(Long dbId) {
        this.dbId = dbId;
    }
    
    @Override
    public String getMessage() {
        return this.toString();
    }

}
