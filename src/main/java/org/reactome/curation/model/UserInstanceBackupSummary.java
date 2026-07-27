package org.reactome.curation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight summary of a single staged-instances backup file, as produced by
 * CurationFileRepository's overwrite-protection logic. Used to list a user's available
 * backups so they can choose one to restore.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(Include.NON_NULL)
public class UserInstanceBackupSummary {

    // Opaque identifier for the backup, to be passed back to getUserInstanceBackup(). This is
    // just the file name (e.g. "jdoe_backup_20260629_151444.json"), not a path.
    private String fileName;
    // File's last-modified time, in epoch milliseconds, for sorting/display on the client.
    private long lastModified;

}
