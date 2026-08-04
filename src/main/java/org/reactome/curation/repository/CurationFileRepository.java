package org.reactome.curation.repository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

//import org.reactome.curation.model.UserInstanceBackupSummary;
import org.reactome.curation.model.UserInstances;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * This class is used to persist new and updated instances at a local file so that we can keep some intermediate
 * curation before pushed into the database. This should be useful to avoid the computer crash or set up some
 * test projects.
 */
@Repository
public class CurationFileRepository {
    private Logger logger = LoggerFactory.getLogger(CurationFileRepository.class);
    
    @Value("${staged-file.backup.max-count:25}")
    private int maxBackupCount;
    
    public CurationFileRepository() {
    }

    public void persist(UserInstances userInstances,
                        String fileName) throws Exception {
        persistObject(userInstances, fileName);
    }

    private void persistObject(Object payload,
                               String fileName) throws Exception {
        File file = new File(fileName);
        
        // Backup the existing file before overwriting
        if (file.exists()) {
            backupFile(fileName);
        }
        
        if (!ensureDirectoryExists(file)) {
            throw new IOException("Failed to create directory for file: " + file.getAbsolutePath());
        }
        
        ObjectMapper mapper = getObjectMapper();
        // Give it some format
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, payload);
        logger.info("Saved instances to " + file.getAbsolutePath());
    }
    
    private boolean ensureDirectoryExists(File file) {
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            return parentDir.mkdirs();
        }
        return true;
    }
    
    /**
     * Create a backup of the specified file with a timestamp suffix.
     * Manages the number of backup files according to the configured maximum.
     * 
     * @param fileName the file to backup
     * @throws IOException if backup fails
     */
    private void backupFile(String fileName) throws IOException {
        File originalFile = new File(fileName);
        if (!originalFile.exists()) {
            return;
        }
        
        // Create backup filename with timestamp
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String timestamp = dateFormat.format(new Date());

        // Get the base name and extension
        String baseName = originalFile.getName();
        String backupFileName;
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex > 0) {
            String nameWithoutExt = baseName.substring(0, dotIndex);
            String extension = baseName.substring(dotIndex);
            backupFileName = nameWithoutExt + "_backup_" + timestamp + extension;
        } else {
            backupFileName = baseName + "_backup_" + timestamp;
        }

        File backupFile = new File(originalFile.getParent(), backupFileName);
        
        // Copy the file
        Files.copy(originalFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        logger.info("Created backup: " + backupFile.getAbsolutePath());
        
        // Clean up old backups
        cleanupOldBackups(originalFile);
    }
    
    /**
     * Remove old backup files if the number exceeds the configured maximum.
     * Keeps the most recent backups based on file modification time.
     * 
     * @param originalFile the original file whose backups should be cleaned
     */
    private void cleanupOldBackups(File originalFile) {
        File parentDir = originalFile.getParentFile();
        if (parentDir == null || !parentDir.exists()) {
            return;
        }

        final String backupPrefix = getBackupPrefix(originalFile);
        File[] backupFiles = parentDir.listFiles((dir, name) ->
            name.startsWith(backupPrefix)
        );

        if (backupFiles == null || backupFiles.length <= maxBackupCount) {
            return;
        }
        
        // Sort by last modified time (oldest first)
        Arrays.sort(backupFiles, Comparator.comparingLong(File::lastModified));
        
        // Delete oldest files beyond the maximum count
        int filesToDelete = backupFiles.length - maxBackupCount;
        for (int i = 0; i < filesToDelete; i++) {
            if (backupFiles[i].delete()) {
                logger.info("Deleted old backup: " + backupFiles[i].getName());
            } else {
                logger.warn("Failed to delete old backup: " + backupFiles[i].getName());
            }
        }
    }
    
    /**
     * The prefix shared by all backup files of the given original file, e.g. for
     * ".../jdoe/jdoe.json" this is "jdoe_backup_" - matches the naming scheme used by backupFile().
     */
    private String getBackupPrefix(File originalFile) {
        String baseName = originalFile.getName();
        int dotIndex = baseName.lastIndexOf('.');
        String baseNameWithoutExt = dotIndex > 0 ? baseName.substring(0, dotIndex) : baseName;
        return baseNameWithoutExt + "_backup_";
    }

    /**
     * List the available backups of the given file (most recently modified first).
     * @param fileName the canonical (non-backup) file whose backups should be listed
     */
//    public List<UserInstanceBackupSummary> listBackups(String fileName) {
//        File originalFile = new File(fileName);
//        File parentDir = originalFile.getParentFile();
//        if (parentDir == null || !parentDir.exists())
//            return Collections.emptyList();
//
//        final String backupPrefix = getBackupPrefix(originalFile);
//        File[] backupFiles = parentDir.listFiles((dir, name) -> name.startsWith(backupPrefix));
//        if (backupFiles == null || backupFiles.length == 0)
//            return Collections.emptyList();
//
//        List<UserInstanceBackupSummary> summaries = new ArrayList<>();
//        for (File backupFile : backupFiles) {
//            summaries.add(new UserInstanceBackupSummary(backupFile.getName(), backupFile.lastModified()));
//        }
//        summaries.sort((a, b) -> Long.compare(b.getLastModified(), a.getLastModified()));
//        return summaries;
//    }

    /**
     * Load the content of one specific backup of the given file. To avoid path traversal or
     * reading another user's backup, backupFileName is validated to be a bare file name (no
     * path separators) that both matches this file's backup-naming prefix and resolves to a
     * regular file directly inside the same directory as fileName.
     * @param fileName the canonical (non-backup) file whose backup directory should be searched
     * @param backupFileName the backup's file name, as returned by listBackups()
     * @throws Exception if backupFileName is invalid or the backup cannot be read
     */
    public UserInstances loadBackup(String fileName, String backupFileName) throws Exception {
        File originalFile = new File(fileName);
        File parentDir = originalFile.getParentFile();
        if (parentDir == null)
            throw new IllegalArgumentException("Invalid file: " + fileName);

        String backupPrefix = getBackupPrefix(originalFile);
        if (backupFileName == null
                || backupFileName.contains("/")
                || backupFileName.contains("\\")
                || !backupFileName.startsWith(backupPrefix)) {
            throw new IllegalArgumentException("Invalid backup file name: " + backupFileName);
        }

        File backupFile = new File(parentDir, backupFileName);
        if (backupFile.getParentFile() == null
                || !backupFile.getParentFile().getCanonicalFile().equals(parentDir.getCanonicalFile())
                || !backupFile.exists()
                || !backupFile.isFile()) {
            throw new IllegalArgumentException("Backup file not found: " + backupFileName);
        }

        ObjectMapper mapper = getObjectMapper();
        TypeReference<UserInstances> typeRef = new TypeReference<>(){};
        return mapper.readValue(backupFile, typeRef);
    }

    /**
     * Delete the persisted instances for a case like users have committed all changed instances.
     * @param fileName
     * @throws Exception
     */
    public void deleteFile(String fileName) throws Exception {
        File file = new File(fileName);
        if (file.exists())
            file.delete();
    }
  
    public UserInstances load(String fileName) throws Exception {
        File file = new File(fileName);
        // In case nothing there
        if (!file.exists())
            return new UserInstances();
        ObjectMapper mapper = getObjectMapper();
        TypeReference<UserInstances> typeRef = new TypeReference<>(){};
        UserInstances userInstances = mapper.readValue(file, typeRef);
        return userInstances;
    }
    
    private ObjectMapper getObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        return mapper;
    }
    
}
