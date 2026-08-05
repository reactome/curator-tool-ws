package org.reactome.curation.exceptions;

import org.reactome.server.graph.domain.model.DatabaseObject;

/**
 * Thrown when a reference embedded in a DatabaseObject being saved (e.g. a Complex's
 * hasComponent, or any relationship-typed attribute) resolves to a dbId that exists in the
 * database, but under a DIFFERENT schema class than the caller expects - e.g. a stale/mistyped
 * dbId, or two distinct new objects colliding on the same client-assigned placeholder id.
 * <p>
 * This must be checked and thrown BEFORE CurationRepository.store()'s batched relationship
 * MATCH+CREATE Cypher runs: a single relationship target that doesn't match its expected label
 * makes that whole batched query match zero rows, silently discarding every relationship of the
 * object being saved - not just the mismatched one.
 */
@SuppressWarnings("serial")
public class DatabaseObjectTypeMismatchException extends DatabaseObjectException {

    private final String expectedClass;
    private final String actualClass;

    public DatabaseObjectTypeMismatchException(DatabaseObject obj, String expectedClass, String actualClass) {
        super(obj);
        this.expectedClass = expectedClass;
        this.actualClass = actualClass;
    }

    @Override
    public String toString() {
        return "Reference with dbId " + this.dbId + " (stId: " + this.stId + ", displayName: " + this.displayName
                + ") was expected to be a " + expectedClass + " but is stored as a " + actualClass
                + " - refusing to save to avoid wiping out its relationships.";
    }

}
