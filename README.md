This project is used to provide a RESTful API for the Web-based Curator Tool developed using Angular. The backend of this project is a Neo4j graph database, which is used for curation.

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
