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


}
