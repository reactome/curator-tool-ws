//package org.reactome.curation.service;
//
//import org.neo4j.driver.*;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.stereotype.Service;
//
//import java.util.ArrayList;
//import java.util.List;
//
//@Service
//public class DynamicNeo4jService {
//
//    String username = "neo4j";
//    String password = "outside-edgar-derby-oscar-plasma-4182";
//
//
//    @Bean
//    public Driver neo4jDriver() {
//        return GraphDatabase.driver(
//                "bolt://localhost:7687",
//                AuthTokens.basic(username, password)
//        );
//    }
//
//    public List<String> runSimpleQuery(String username, String password) {
//        if(!username.isEmpty()){
//            this.username = username;
//            this.password = password;
//        }
//        try (Driver driver = neo4jDriver();
//             Session session = driver.session()) {
//
//            Result result = session.run("MATCH (n) RETURN n LIMIT 5");
//
//            List<String> nodeSummaries = new ArrayList<>();
//            while (result.hasNext()) {
//                Record record = result.next();
//                nodeSummaries.add(record.get("n").toString());
//            }
//
//            return nodeSummaries;
//        }
//    }
//
//}