This project is used to provide a RESTful API for the Web-based Curator Tool developed using Angular. The backend of this project is a Neo4j graph database, which is used for curation.

To build a standalone springboot application for test at aws_curator, run the following at the terminal:

```
mvn clean package -DskipTests
```

To run the built app, use the standard java application (Make sure java 11 is used):

```
java -jar curator-tool-ws-0.0.1-SNAPSHOT.jar 2>&1 >out.txt &
```

Make sure all configurations are changed to the server, including the port.