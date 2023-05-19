package org.reactome.curation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@AutoConfiguration
public class CuratorToolWsApplication {

	public static void main(String[] args) {
		SpringApplication.run(CuratorToolWsApplication.class, args);
	}

}
