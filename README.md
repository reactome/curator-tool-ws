This project is used to provide a RESTful API for the Web-based Curator Tool developed using Angular. The backend of this project is a Neo4j graph database, which is used for curation.

## Configuration

The Spring configuration files are not in git because they hold environment-specific paths, and the credentials they need must never be committed. Create them from the templates in a fresh clone:

1. Create the local configuration from the template:

   ```bash
   cp src/main/resources/application.properties.template src/main/resources/application.properties.local
   ```

   `src/main/resources/application.properties` is a symlink to `application.properties.local`, so this is the file the application loads. Edit the values marked `CHANGE ME`, mainly the directories (`file_repo_dir`, `diagram_graph_dir`, `diagram_cytoscape_dir`, `figure_root_dir`).

2. Put the credentials in a `.env` file in the project root:

   ```bash
   cp .env.example .env
   ```

   Then fill in `NEO4J_URI`, `NEO4J_USER`, `NEO4J_PASSWORD`, `DATASOURCE_URL`, `DATASOURCE_USER` and `DATASOURCE_PASSWORD`. Spring Boot doesn't read `.env` by itself: the template imports it via `spring.config.import=optional:file:.env[.properties]`, relative to the working directory, so start the application (or IntelliJ run configuration) from the project root. Real environment variables, if set, override `.env`. Write the values as plain `KEY=value` lines, without quotes or `export`.

3. For the production server, create `application.properties.server` from the same template, set `isProduction=true` and use the server's directories. `deploy-server.sh` temporarily points the `application.properties` symlink at it while building the jar. On the server, put a `.env` with the production credentials in the directory the jar is started from, or set the same variables in the environment.

`application.properties.local`, `application.properties.server`, `.env` and `.env.prod` are listed in `.gitignore`. Only the templates (`application.properties.template` and `.env.example`) are committed.

To build standalone executable jars after making the needed configuration changes (for example `application.properties` and `services.properties`), run:

```bash
./mvnw clean package -DskipTests
```

This build now produces two runnable jars in `target/` plus a plain dependency jar:

- `curator-tool-ws-0.0.1-SNAPSHOT.jar` - the main web application
- `curator-tool-ws-0.0.1-SNAPSHOT-usermanager.jar` - the CLI for `UserManager`
- `curator-tool-ws-0.0.1-SNAPSHOT-plain.jar` - the non-executable jar that can be consumed from another Maven project

To run the web application (make sure Java 11 is used and any previous instance has been stopped. You may not need target in the following command):

```bash
java -jar target/curator-tool-ws-0.0.1-SNAPSHOT.jar 2>&1 > out.txt &
```

To run `UserManager` from the command line (make sure the jar file name is correct):

```bash
java -jar target/curator-tool-ws-0.0.1-SNAPSHOT-usermanager.jar help
java -jar target/curator-tool-ws-0.0.1-SNAPSHOT-usermanager.jar create <username> <password> [role]
java -jar target/curator-tool-ws-0.0.1-SNAPSHOT-usermanager.jar list
java -jar target/curator-tool-ws-0.0.1-SNAPSHOT-usermanager.jar change-password <username> <newPassword>
java -jar target/curator-tool-ws-0.0.1-SNAPSHOT-usermanager.jar delete <username>
```

The CLI uses the same Spring configuration as the application. Make sure the required runtime configuration and local data files are available, including the H2 files under `data/` and the configured Neo4j connection if your startup path touches it. Currently only no special characters (e.g. !,%,$,&,#,@) are supported.

Make sure all configurations are changed to match the target server environment, including the port.

If another Maven project needs this module as a dependency, install it locally and depend on the `plain` classifier:

```xml
<dependency>
	<groupId>org.reactome</groupId>
	<artifactId>curator-tool-ws</artifactId>
	<version>0.0.1-SNAPSHOT</version>
	<classifier>plain</classifier>
</dependency>
```
