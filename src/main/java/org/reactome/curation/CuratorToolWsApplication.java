package org.reactome.curation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@SpringBootApplication
@AutoConfiguration
@EntityScan(basePackages = "org.reactome.curation.user.model")
public class CuratorToolWsApplication {

	public static void main(String[] args) {
		SpringApplication.run(CuratorToolWsApplication.class, args);
	}

}
