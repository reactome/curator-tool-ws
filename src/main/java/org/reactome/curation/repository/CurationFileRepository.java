package org.reactome.curation.repository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;

import org.reactome.curation.model.PathwayDiagramLockPayload;
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

    public void persist(PathwayDiagramLockPayload payload,
                        String fileName) throws Exception {
        persistObject(payload, fileName);
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
        
        // Get the base name without extension
        String baseName = originalFile.getName();
        String baseNameWithoutExt = baseName;
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseNameWithoutExt = baseName.substring(0, dotIndex);
        }
        
        // Find all backup files for this base name
        final String backupPrefix = baseNameWithoutExt + "_backup_";
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
