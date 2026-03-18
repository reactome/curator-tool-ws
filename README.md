This project is used to provide a RESTful API for the Web-based Curator Tool developed using Angular. The backend of this project is a Neo4j graph database, which is used for curation.

To build standalone executable jars after making the needed configuration changes (for example `application.properties` and `services.properties`), run:

```bash
./mvnw clean package -DskipTests
```

This build now produces two runnable jars in `target/`:

- `curator-tool-ws-0.0.1-SNAPSHOT.jar` - the main web application
- `curator-tool-ws-0.0.1-SNAPSHOT-usermanager.jar` - the CLI for `UserManager`

To run the web application (make sure Java 11 is used and any previous instance has been stopped):

```bash
java -jar target/curator-tool-ws-0.0.1-SNAPSHOT.jar 2>&1 > out.txt &
```

To run `UserManager` from the command line:

```bash
java -jar target/curator-tool-ws-0.0.1-SNAPSHOT-usermanager.jar help
java -jar target/curator-tool-ws-0.0.1-SNAPSHOT-usermanager.jar create <username> <password> [role]
java -jar target/curator-tool-ws-0.0.1-SNAPSHOT-usermanager.jar change-password <username> <newPassword>
java -jar target/curator-tool-ws-0.0.1-SNAPSHOT-usermanager.jar delete <username>
```

The CLI uses the same Spring configuration as the application. Make sure the required runtime configuration and local data files are available, including the H2 files under `data/` and the configured Neo4j connection if your startup path touches it.

Make sure all configurations are changed to match the target server environment, including the port.