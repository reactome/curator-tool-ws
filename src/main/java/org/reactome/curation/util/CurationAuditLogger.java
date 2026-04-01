package org.reactome.curation.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.Deleted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Utility class for logging all curation write actions (create, update, delete).
 * Provides comprehensive audit trails including user, dbId, displayName, action type, and success status.
 */
@Component
public class CurationAuditLogger {
    private static final Logger auditLogger = LoggerFactory.getLogger("curation-audit");
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * Log an add action for a new DatabaseObject.
     */
    public void logAdd(String username, DatabaseObject obj, boolean success, String errorMessage) {
        String displayName = obj != null ? obj.getDisplayName() : "N/A";
        Long dbId = obj != null && obj.getDbId() != null ? obj.getDbId() : -1L;
        String schemaClassName = getSchemaClassName(obj);
        logAction(username, dbId, displayName, schemaClassName, "ADD", success, errorMessage);
    }

    /**
     * Log an update action for an existing DatabaseObject.
     *
     * @param username The curator performing the action
     * @param obj The updated object
     * @param success Whether the operation succeeded
     * @param errorMessage Error message if operation failed (null if successful)
     */
    public void logUpdate(String username, DatabaseObject obj, boolean success, String errorMessage) {
        String displayName = obj != null ? obj.getDisplayName() : "N/A";
        Long dbId = obj != null && obj.getDbId() != null ? obj.getDbId() : -1L;
        String schemaClassName = getSchemaClassName(obj);
        logAction(username, dbId, displayName, schemaClassName, "UPDATE", success, errorMessage);
    }

    /**
     * Log a delete action for a DatabaseObject.
     *
     * @param username The curator performing the action
     * @param obj The deleted object
     * @param success Whether the operation succeeded
     * @param errorMessage Error message if operation failed (null if successful)
     */
    public void logDelete(String username, DatabaseObject obj, boolean success, String errorMessage) {
        String displayName = obj != null ? obj.getDisplayName() : "N/A";
        Long dbId = obj != null && obj.getDbId() != null ? obj.getDbId() : null;
        String schemaClassName = getSchemaClassName(obj);
        logAction(username, dbId, displayName, schemaClassName, "DELETE", success, errorMessage);
    }

    /**
     * Log a bulk delete action (deleteByDeleted).
     *
     * @param username The curator performing the action
     * @param deleted The Deleted object containing multiple deleted instances
     * @param deletedObjectCount Number of objects deleted
     * @param success Whether the operation succeeded
     * @param errorMessage Error message if operation failed (null if successful)
     */
    public void logBulkDelete(String username, DatabaseObject deleted, int deletedObjectCount, boolean success, String errorMessage) {
        String displayName = deleted != null ? deleted.getDisplayName() : "N/A";
        Long dbId = deleted != null && deleted.getDbId() != null ? deleted.getDbId() : -1L;
        String schemaClassName = getSchemaClassName(deleted);
        String action = String.format("BULK_DELETE[count=%d]", deletedObjectCount);
        logAction(username, dbId, displayName, schemaClassName, action, success, errorMessage);
    }

    /**
     * Log a diagram update action (Cytoscape network).
     *
     * @param username The curator performing the action
     * @param pathwayDiagramId The dbId of the pathway diagram
     * @param displayName The display name of the pathway diagram
     * @param success Whether the operation succeeded
     * @param errorMessage Error message if operation failed (null if successful)
     */
    public void logDiagramUpdate(String username, Long pathwayDiagramId, String displayName, boolean success, String errorMessage) {
        logAction(username, pathwayDiagramId, displayName, "PathwayDiagram", "DIAGRAM_UPDATE", success, errorMessage);
    }

    private String getSchemaClassName(DatabaseObject obj) {
        if (obj == null) {
            return "N/A";
        }
        String className = obj.getSchemaClass();
        if (className != null)
            return className;
        className = obj.getClassName();
        if (className != null && !className.isBlank()) {
            return className;
        }
        return obj.getClass().getSimpleName();
    }

    /**
     * Core method to log an action with all details.
     *
     * @param username The curator performing the action
     * @param dbId The database ID of the modified object
     * @param displayName The display name of the modified object
     * @param schemaClassName
     * @param action The action type (CREATE, UPDATE, DELETE, BULK_DELETE, etc.)
     * @param success Whether the operation succeeded
     * @param errorMessage Error message if failed (null if successful)
     */
    private void logAction(String username,
                           Long dbId,
                           String displayName,
                           String schemaClassName,
                           String action,
                           boolean success,
                           String errorMessage) {
        String timestamp = LocalDateTime.now().format(formatter);
        String status = success ? "SUCCESS" : "FAILED";

        StringBuilder logMessage = new StringBuilder();
        logMessage.append("[").append(timestamp).append("] ");
        logMessage.append("USER=").append(username).append(" | ");
        logMessage.append("ACTION=").append(action).append(" | ");
        logMessage.append("DBID=").append(dbId).append(" | ");
        logMessage.append("DISPLAY_NAME=").append(displayName).append(" | ");
        logMessage.append("SCHEMA_CLASS=").append(schemaClassName).append(" | ");
        logMessage.append("STATUS=").append(status);

        if (!success && errorMessage != null) {
            logMessage.append(" | ERROR=").append(errorMessage);
        }

        auditLogger.info(logMessage.toString());
    }

}
