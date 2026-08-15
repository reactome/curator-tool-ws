package org.reactome.curation.exceptions;

import org.reactome.server.graph.domain.model.DatabaseObject;

/**
 * Thrown when CurationRepository.storeShell() cannot persist a new DatabaseObject because the
 * dbId it tried to save under - whether assigned by nextDbId() or supplied by the caller -
 * collides with a node another, independent process already inserted, even after retrying with
 * freshly assigned dbIds.
 */
@SuppressWarnings("serial")
public class DbIdConflictException extends DatabaseObjectException {

    public DbIdConflictException(DatabaseObject obj, Throwable cause) {
        super(obj);
        initCause(cause);
    }

    @Override
    public String toString() {
        return "Could not save \"" + this.displayName + "\" because dbId " + this.dbId +
                " is already used by another instance, most likely inserted by another process at the " +
                "same time. Please try saving again.";
    }

}
