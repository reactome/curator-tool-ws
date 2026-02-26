package org.reactome.curation.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;

@Configuration
@PropertySource("classpath:application.properties")
//Need the following scan
@ComponentScan({ "org.reactome.curation.service" })
// TODO: Need to see how to merge it together with CurationToolProperties.
public class CuratorToolEnv {

    @Autowired
    private Environment env;

    public CuratorToolEnv() {

    }

    // The following two are used to configure PubMed URL
    public String getPubmedUrl1() {
        return env.getProperty("pubmedUrl1");
    }

    public String getPubmedUrl2() {
        return env.getProperty("pubmedUrl2");
    }

    public String getFileRepoDir() {
        return env.getProperty("file_repo_dir");
    }
    
    public String getDiagramGraphDir() {
        return env.getProperty("diagram_graph_dir");
    }
    
    public String getDiagramCytoscapeDir() {
        return env.getProperty("diagram_cytoscape_dir");
    }
    
    /**
     * InstanceEdit will be reused for a certain period of time for a user.
     * This is to avoid creating too many InstanceEdit objects for a user in a short period of time. The default value is 60 seconds.
     * @return
     */
    public Integer getInstanceEditDuration() {
        String value = env.getProperty("instance_edit_duration");
        if (value == null)
            return 60; // Default to 60 seconds
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return 60;
        }
    }

}
