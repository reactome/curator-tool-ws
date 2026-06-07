package org.reactome.curation.repository;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;

import org.reactome.curation.config.CuratorToolEnv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import lombok.Data;

/**
 * This class is used to handle pathway diagrams: save and load. It should be configured for the 
 * local directories that are used to persist JSON-based diagrams information.
 */
@Repository
@Data
public class PathwayDiagramRepository {
    private static final Logger logger = LoggerFactory.getLogger(PathwayDiagramRepository.class);
    
    @Autowired
    private CuratorToolEnv toolEnv;
    
    
    public String loadDiagramJson(String fileName) throws IOException {
        logger.debug("Loading file " + fileName + "...");
        File file = new File(toolEnv.getDiagramGraphDir(), fileName);
        if (!file.exists())
            return null;
        String text = new String(Files.readAllBytes(file.toPath()));
        return text;
    }
    
    /**
     * Check if the diagram JSON file exists for a given file name.
     * @param fileName
     * @return
     * @throws IOException
     */
    public boolean hasDiagramJson(Long dbId) throws IOException {
        String fileName = dbId + ".json";
        File file = new File(toolEnv.getDiagramGraphDir(), fileName);
        return file.exists();
    }
    
    public String loadCytoscapeNetwork(Long pathwayId) throws IOException {
        File file = new File(toolEnv.getDiagramCytoscapeDir(), pathwayId + ".json");
        if (!file.exists())
            return null;
        String text = new String(Files.readAllBytes(file.toPath()));
        return text;
    }
    
    public Boolean hasCytoscapeNetwork(Long pathwayId) throws IOException {
        File file = new File(toolEnv.getDiagramCytoscapeDir(), pathwayId + ".json");
        return file.exists();
    }
    
    public void saveCytoscapeNetwork(Long pathwayId, String networkJson) throws IOException {
        logger.debug("Saving cytoscape network for " + pathwayId + "...");
        File file = new File(toolEnv.getDiagramCytoscapeDir(), pathwayId + ".json");
        writeJSON(networkJson, file);
    }

    public void backupCytoscapeNetwork(Long pathwayDiagramId, String networkJson) throws IOException {
        logger.debug("Backup cytoscape network for " + pathwayDiagramId + "...");
        File fileName = new File(getCytoscapeJsonBackupDir(), pathwayDiagramId + ".json");
        writeJSON(networkJson, fileName);
    }

    /**
     * An editing cytoscape diagram is a back-up of a pathway diagram managed by a PathwayDiagram instance that is
     * under active editing. This is different from a cytoscape diagram uploaded. Only the user obtains a lock to
     * this editing diagram can access, which is controlled by the controller.
     * @param pathwayDiagramId
     * @return
     * @throws IOException
     */
    public Boolean hasEditingCytoscapeNetwork(Long pathwayDiagramId) throws IOException {
        File fileName = new File(getCytoscapeJsonBackupDir(), pathwayDiagramId + ".json");
        return fileName.exists();
    }

    public String loadEditingCytoscapeNetwork(Long pathwayDiagramId) throws IOException {
        File fileName = new File(getCytoscapeJsonBackupDir(), pathwayDiagramId + ".json");
        if (!fileName.exists())
            return null;
        String text = new String(Files.readAllBytes(fileName.toPath()));
        return text;
    }

    private void writeJSON(String networkJson, File file) throws IOException {
        FileWriter fileWriter = new FileWriter(file);
        BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);
        bufferedWriter.write(networkJson);
        bufferedWriter.close();
        fileWriter.close();
    }

    private String getCytoscapeJsonBackupDir() {
        File dir = new File(toolEnv.getDiagramCytoscapeDir(), "editing");
        return dir.getAbsolutePath();
    }

    public String getDiagramGraphDir() {
        return toolEnv.getDiagramGraphDir();
    }

}
