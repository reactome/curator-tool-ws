package org.reactome.curation;

import org.reactome.server.graph.utils.ReactomeGraphCore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.ApplicationContext;

@SpringBootApplication
@AutoConfiguration
@EntityScan(basePackages = {"org.reactome.curation.user.model", "org.reactome.curation.model"})
public class CuratorToolWsApplication {

	public static void main(String[] args) {
		ApplicationContext context = SpringApplication.run(CuratorToolWsApplication.class, args);
		// This is a hack. However, apparently this cannot work in WSTests.
		ReactomeGraphCore.setContext(context);
	}

}
