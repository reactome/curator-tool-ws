package org.reactome.curation.exceptions;

/**
 * Thrown by CurationRepository when the durable dbId watermark (H2) is higher than the graph's
 * own MAX(n.dbId) at startup - meaning the Neo4j store was rolled back or replaced with an older
 * copy after some dbIds were already handed out. Allocating new dbIds in this state would reissue
 * ones already used by curated (and now-invisible) instances, silently duplicating them.
 *
 * Writes stay blocked until an administrator resolves the mismatch; reads are unaffected.
 */
@SuppressWarnings("serial")
public class DbRollbackDetectedException extends RuntimeException {

    public DbRollbackDetectedException() {
        super("Database rollback detected: the graph database's highest dbId is lower than the " +
                "durable watermark this application previously recorded. New dbIds cannot be " +
                "safely assigned until an administrator resolves this. Contact an administrator.");
    }
}
