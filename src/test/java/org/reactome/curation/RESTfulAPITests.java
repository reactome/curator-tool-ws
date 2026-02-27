package org.reactome.curation;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.gk.model.ReactomeJavaConstants;
import org.junit.jupiter.api.Test;
import org.reactome.curation.controller.DatabaseObjectInstanceConverter;
import org.reactome.curation.model.InstanceList;
import org.reactome.curation.model.ListOperand;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.user.model.User;
import org.reactome.server.graph.domain.model.Complex;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.ModifiedResidue;
import org.reactome.server.graph.domain.model.Reaction;
import org.reactome.server.graph.domain.model.ReferenceGeneProduct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

@SpringBootTest
@AutoConfigureMockMvc
class RESTfulAPITests {
    private static final Logger logger = LoggerFactory.getLogger(RESTfulAPITests.class);
    
    private final String BASE_URL = "/api/curation/";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DatabaseObjectInstanceConverter converter;

    @Test
    void contextLoads() {
    }
    
    @Test
    public void testListInstancesWithAuthentication() throws Exception {
        User request = new User("test", "password", "id");
        ObjectMapper mapper = CurationWSTestHelper.createObjectMapper();
        String jsonObj = mapper.writeValueAsString(request);
        System.out.println(jsonObj);
        
        String url = "/api/authenticate";
        String jwt = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(jsonObj))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        jwt = jwt.substring(1, jwt.length() - 1); // Get rid of quotation marker
        System.out.println(jwt);
        
        String className = "ProteinDrug";
        className = "ReactionType";
        url = BASE_URL + "listInstances/" + className + "/0/10";
        String json = mockMvc.perform(get(url).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        outputInstanceList(json);
    }
    
    private String getJWT() throws Exception {
        User request = new User("test", "password", "id");
        ObjectMapper mapper = CurationWSTestHelper.createObjectMapper();
        String jsonObj = mapper.writeValueAsString(request);
        System.out.println(jsonObj);
        
        String url = "/api/authenticate";
        String jwt = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(jsonObj))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        jwt = jwt.substring(1, jwt.length() - 1); // Get rid of quotation marker
        System.out.println(jwt);
        return jwt;
    }
    
    @Test
    public void testAuthenticate() throws Exception {
        User request = new User("test", "password", "id");
        ObjectMapper mapper = CurationWSTestHelper.createObjectMapper();
        String jsonObj = mapper.writeValueAsString(request);
        System.out.println(jsonObj);
        
        String url = "/api/authenticate";
        String json = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(jsonObj))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        System.out.println(formatJSON(json));
        
        // This user should not be validated
        request = new User("test", "wrong_password", "id");
        jsonObj = mapper.writeValueAsString(request);
        System.out.println(jsonObj);
        
        json = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(jsonObj))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        System.out.println(formatJSON(json));
    }
    
    @Test
    public void testGetEventTree() throws Exception {
        assertNotNull(mockMvc);
        String speciesName = "Homo sapiens";
        String url = BASE_URL + "getEventTree/" + speciesName;
        String json = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        System.out.println(formatJSON(json));
    }
    
    private String formatJSON(String json) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Object jsonObj = mapper.readValue(json, Object.class);
        ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
        return writer.writeValueAsString(jsonObj);
    }
    
    
    @Test
    public void testPathwayDiagram() throws Exception {
        assertNotNull(mockMvc);
        // Load diagram json
        Long dbId = 9615710L; // Late endosomal microautophage
        String diagramUrl = BASE_URL + "diagram/" + dbId + ".json";
        String diagramJson = mockMvc.perform(get(diagramUrl))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        System.out.println(diagramJson);
        
        // load diagram graph json
        String graphUrl = BASE_URL + "diagram/" + dbId + ".graph.json";
        String graphJson = mockMvc.perform(get(graphUrl))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        System.out.println(graphJson);
        
        
    }
    
    @Test
    public void testPersistAndLoadInstances() throws Exception {
        assertNotNull(mockMvc);
        
        ObjectMapper mapper = CurationWSTestHelper.createObjectMapper();
        
        Complex complex = CurationWSTestHelper.createComplexWithNewComplexAndSubunit();
        SimpleInstance instance = converter.convert(complex);
        
        Reaction reaction = CurationWSTestHelper.createReaction();
        SimpleInstance reationInstance = converter.convert(reaction);
        
        List<SimpleInstance> instances = new ArrayList<>();
        instances.add(instance);
        instances.add(reationInstance);
        
        String instancesJSON = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(instances);
        logger.info("Instances in JSON:\n" + instancesJSON);
        
        // Persist
        String url = BASE_URL + "persistInstances/test";
        String dbId = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                           .content(instancesJSON))
                           .andExpect(status().isOk())
                           .andReturn()
                           .getResponse()
                           .getContentAsString();
        logger.info("Done saving: " + dbId);
        
        // Load back
        url = BASE_URL + "loadInstances/test";
        String json = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        System.out.println(json);
    }
    
    
    @Test
    public void testGetSchemaClassTree() throws Exception {
        assertNotNull(mockMvc);
        String url = BASE_URL + "getSchemaClassTree";
        String json = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        System.out.println(json);
    }
    
    /**
     * Note: This test may not work if the instance has been deleted already. Try another instance
     * that can be deleted without impacting anything else.
     * @throws Exception
     */
    @Test
    public void testDeleteInstance() throws Exception {
        assertNotNull(mockMvc);
        SimpleInstance simpleInstance = new SimpleInstance();
        simpleInstance.setDbId(12241211L);
        simpleInstance.setSchemaClassName("Reaction");
        
        ObjectMapper mapper = CurationWSTestHelper.createObjectMapper();
        String reactionJSON = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(simpleInstance);
        logger.info("Reaction in JSON:\n" + reactionJSON);
        
        String url = BASE_URL + "delete";
        String rtn = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                           .content(reactionJSON))
                           .andExpect(status().isOk())
                           .andReturn()
                           .getResponse()
                           .getContentAsString();
        logger.info("Deleting an instance: " + rtn);
//        
    }
    
    @Test
    public void testListInstances() throws Exception {
        assertNotNull(mockMvc);
        String className = "ProteinDrug";
        className = "ReactionType";
        String url = BASE_URL + "listInstances/" + className + "/0/10";
        String json = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        outputInstanceList(json);
        
        // Try reactions
        className = "Reaction";
        url = BASE_URL + "listInstances/" + className + "/100/10?query=EGFR";
        json = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        outputInstanceList(json);
        
        // Try reactions with space
        className = "Reaction";
        url = BASE_URL + "listInstances/" + className + "/0/10?query=R5C dephosp";
        json = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        outputInstanceList(json);
    }
    
    private void outputInstanceList(String json) throws Exception {
        ObjectMapper mapper = CurationWSTestHelper.createObjectMapper();
        InstanceList instanceList = mapper.readValue(json, InstanceList.class);
        
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(instanceList));
    }
    
    @Test
    public void testSearchInstances() throws Exception {
        assertNotNull(mockMvc);
        var className = "Reaction";
        List<String> attributes = new ArrayList<>(List.of("displayName", "compartment"));
        List<String> operands = new ArrayList<>(List.of("Contains", "Equal"));
        List<String> searchKeys = new ArrayList<>(List.of("phosphorylates MDM2", "nucleoplasm"));
        _testSearchInstances(className, attributes, operands, searchKeys);

        // Limit the result for dbId to check different attribute type
        // Expect to limit to a smaller number of the above result
        logger.info("Added a new property check...");
        attributes.add("dbId");
        operands.add(ListOperand.CONTAINS.toString());
        searchKeys.add("7"); // Expect 2 instances
        _testSearchInstances(className, attributes, operands, searchKeys);
        
        // Further limit by adding another relationship condition
        logger.info("Added another relationship check...");
        attributes.add("input");
        operands.add(ListOperand.CONTAINS.toString());
        searchKeys.add("H2O"); // Expect 1 instance
        _testSearchInstances(className, attributes, operands, searchKeys);
        
        logger.info("Add a not null check for name. Same results...");
        attributes.add("name");
        operands.add(ListOperand.IS_NOT_NULL.toString());
        searchKeys.add(null); // Expect 1 instance
        _testSearchInstances(className, attributes, operands, searchKeys);
        
        logger.info("Add a null check for regulatedBy. Same results...");
        attributes.add("regulatedBy");
        operands.add(ListOperand.IS_NULL.toString());
        searchKeys.add(null); // Still same result
        _testSearchInstances(className, attributes, operands, searchKeys);
        
        // Further limit by adding another relationship condition using is not null
        logger.info("Added another relationship check...");
        attributes.add("output");
        operands.add(ListOperand.IS_NOT_NULL.toString());
        searchKeys.add(null); // Expect 1 instance
        _testSearchInstances(className, attributes, operands, searchKeys);
    }

    private void _testSearchInstances(String className,
                                      List<String> attributes,
                                      List<String> operands,
                                      List<String> searchKeys)
            throws UnsupportedEncodingException, Exception {
        String query = "attributes=" + String.join(",", attributes) + 
          "&operands=" + String.join(",", operands) + 
          "&searchKeys=" + String.join(",", searchKeys);
        String url = BASE_URL + "searchInstances/" + className + "/0/5?" + query;
        System.out.println("URL: " + url);
        String json = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        outputInstanceList(json);
    }
    
    @Test
    public void testFindByDisplayName() throws Exception {
        logger.info("Test findByDisplayName...");
        assertNotNull(mockMvc);
        String displayName = "homo sapiens";
        String clsNames = "PhysicalEntity,Species,Pathway";
        String url = BASE_URL + "findByDisplayName?displayName=" + displayName + "&classNames=" + clsNames;
        logger.info("URL: " + url);
        String json = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        System.out.println(json);
    }
    
    @Test
    public void testCountInstances() throws Exception {
        String className = "Reaction";
        String url = BASE_URL + "countInstances/" + className;
        String json = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        System.out.println(json);
        // Query for some text
        System.out.println("Count instances for some text: EGFR");
        url = BASE_URL + "countInstances/" + className + "?query=EGFR";
        json = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        System.out.println(json);
    }
    
    @Test
    public void testFindByIds() throws Exception {
        assertNotNull(mockMvc);
        
        String jwt = getJWT();
        
        List<Long> dbIds = List.of(73894L, 9612973L, 162582L);
        
//        List<Long> dbIds = Arrays.asList(
//                400710L, 613449L, 5691543L, 8987656L, 9917590L, 2L, 9926675L, 8992654L,
//                423310L, 1995863L, 1042053L, 1300696L, 2265580L, 9715482L, 1551960L, 435478L
//            );
        
        // The URL should start with "/" to make it true
        String url = BASE_URL + "findByDbIds/";
        
        ObjectMapper mapper = CurationWSTestHelper.createObjectMapper();
        
        String queryText = mapper.writeValueAsString(dbIds);
        logger.info("Query text: " + queryText);
        
        String rtn = mockMvc.perform(post(url).header("Authorization", "Bearer " + jwt)
                           .contentType(MediaType.APPLICATION_JSON)
                           .content(queryText))
                           .andExpect(status().isOk())
                           .andReturn()
                           .getResponse()
                           .getContentAsString();
        logger.info("Found instances:\n" + rtn);
    }
    

    @Test
    public void testFindById() throws Exception {
        assertNotNull(mockMvc);
        
        String jwt = getJWT();
        
        Long[] dbIds = {
                //TODO: The following query is used:
                // MATCH (n:DatabaseObject{dbId:$dbId}) OPTIONAL MATCH (n)-[r]-(m) WITH n, r, m ORDER BY TYPE(r) ASC, r.order ASC RETURN n, COLLECT(r), COLLECT(m) LIMIT $limit
                // This query is slow for homo sapiens because of the undirection relationship, which will pull out many results. 
                // Need to consider to add the direction here to increase the performance.
//                48887L, // Homo sapiens. The query is quite slow!
                109581L, // Pathway
//                72810L, // NCBI Taxonomy
//                9707103L, // A figure
//                72811L, // InstanceEdit
        };
        // The URL should start with "/" to make it true
        String url = BASE_URL + "findDatabaseObjectByDbId/";
        for (Long dbId : dbIds) {
            String json = mockMvc.perform(get(url + dbId).header("Authorization", "Bearer " + jwt))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            System.out.println(json);
//            DatabaseObject obj = curationService.findById(dbId);
//            System.out.println(obj);
        }
    }
    
    @Test
    public void testFetchPathwayDiagram() throws Exception {
        assertNotNull(mockMvc);
        
        String jwt = getJWT();
        
        Long[] dbIds = {
                9615710L, // Late endosomal microautophagy
                
        };
        // The URL should start with "/" to make it true
        String url = BASE_URL + "fetchPathwayDiagramForPathway/";
        for (Long dbId : dbIds) {
            String json = mockMvc.perform(get(url + dbId).header("Authorization", "Bearer " + jwt))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            System.out.println(json);
        }
    }
    
    @Test
    public void testJSONDeserization() throws Exception {
        assertNotNull(mockMvc);
        Long[] dbIds = {
                141412L, // An EWAS
//                141429L, // A reaction has the same instance appearing in two slots.
//                109581L, // Pathway
//                72810L, // NCBI Taxonomy
//                9707103L, // A figure
//                72811L, // InstanceEdit
        };
        // The URL should start with "/" to make it true
        String url = BASE_URL + "findByDbId/";
        String jwt = getJWT();
        ObjectMapper mapper = CurationWSTestHelper.createObjectMapper();
        for (Long dbId : dbIds) {
            String json = mockMvc.perform(get(url + dbId).header("Authorization", "Bearer " + jwt))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            System.out.println(json);
            SimpleInstance instance = mapper.readValue(json, SimpleInstance.class);
            System.out.println("SimpleInstance from JSON:\n" + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(instance));
//            DatabaseObject obj = curationService.findById(dbId);
//            System.out.println(obj);
        }
    }
    
    @Test
    public void testFindByIdInInstance() throws Exception {
        assertNotNull(mockMvc);
        Long[] dbIds = {
                9776999L, // UpdateTracker
                141412L, // An EWAS
                141429L, // A reaction has the same instance appearing in two slots.
                109581L, // Pathway
                72810L, // NCBI Taxonomy
                9707103L, // A figure
                72811L, // InstanceEdit
        };
        // The URL should start with "/" to make it true
        String url = BASE_URL + "findByDbId/";
        String jwt = getJWT();
        for (Long dbId : dbIds) {
            String json = mockMvc.perform(get(url + dbId).header("Authorization", "Bearer " + jwt))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            System.out.println(json);
//            DatabaseObject obj = curationService.findById(dbId);
//            System.out.println(obj);
        }
    }
    
    @Test
    public void testGetAttributes() throws Exception {
        assertNotNull(mockMvc);
        String[] clsNames = {
//                ReactomeJavaConstants.EntityWithAccessionedSequence,
                ReactomeJavaConstants.Pathway,
//                ReactomeJavaConstants.Reaction,
//                ReactomeJavaConstants.ReferenceGeneProduct,
//                ReactomeJavaConstants.Species
//                "ReviewStatus" // A new class
        };
        String url = BASE_URL + "getAttributes/";
        for (String clsName : clsNames) {
            String json = mockMvc.perform(get(url + clsName).header("Authorization", "Bearer " + getJWT()))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            System.out.println(clsName + ":\n" + json);
        }
    }
    
    /**
     * Test the store method using SimpleInstance.
     * @throws Exception
     */
    @Test
    public void testStoreComplexAndReactionInInstance() throws Exception {
        assertNotNull(mockMvc);
        
        String url = BASE_URL + "store";
        logger.info("URL: " + url);
        
        ObjectMapper mapper = CurationWSTestHelper.createObjectMapper();
        
        Complex complex = CurationWSTestHelper.createComplexWithNewComplexAndSubunit();
        SimpleInstance instance = converter.convert(complex);
        String complexJSON = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(instance);
        logger.info("Complex in JSON:\n" + complexJSON);
        
        Reaction reaction = CurationWSTestHelper.createReaction();
        SimpleInstance reationInstance = converter.convert(reaction);
        String reactionJSON = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(reationInstance);
        logger.info("Reaction in JSON:\n" + reactionJSON);
        
        // Store
        String dbId = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                           .content(complexJSON))
                           .andExpect(status().isOk())
                           .andReturn()
                           .getResponse()
                           .getContentAsString();
        logger.info("Done saving a new Complex: " + dbId);
        
        dbId = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(reactionJSON))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        logger.info("Done saving a new Reaction: " + dbId);
    }
    
    @Test
    public void testStoreComplexWithNewValue() throws Exception {
        assertNotNull(mockMvc);
        Complex complex = CurationWSTestHelper.createComplexWithNewComplexAndSubunit();
        SimpleInstance instance = converter.convert(complex);
        instance.setDefaultPersonId(140537L);
        ObjectMapper mapper = CurationWSTestHelper.createObjectMapper();
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(instance);
        logger.info("Complex in JSON:\n" + json);
        String url = BASE_URL + "commit";
        logger.info("URL: " + url);
        String jwt = getJWT();
        String dbId = mockMvc.perform(post(url).header("Authorization", "Bearer " + jwt).contentType(MediaType.APPLICATION_JSON)
                           .content(json))
                           .andExpect(status().isOk())
                           .andReturn()
                           .getResponse()
                           .getContentAsString();
        logger.info("Done saving a new Complex: " + dbId);
    }
    
    @Test
    public void testUpdateInstance() throws Exception {
        assertNotNull(mockMvc);
        
        Complex complex = CurationWSTestHelper.createComplexWithNewComplexAndSubunit();
        SimpleInstance instance = converter.convert(complex);
        instance.setDefaultPersonId(140537L);
        ObjectMapper mapper = CurationWSTestHelper.createObjectMapper();
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(instance);
        logger.info("Complex in JSON:\n" + json);
        String url = BASE_URL + "commit";
        logger.info("URL: " + url);
        String jwt = getJWT();
        String dbId = mockMvc.perform(post(url).header("Authorization", "Bearer " + jwt).contentType(MediaType.APPLICATION_JSON)
                           .content(json))
                           .andExpect(status().isOk())
                           .andReturn()
                           .getResponse()
                           .getContentAsString();
        logger.info("Done updating a Complex: " + dbId);
    }
    
    @Test
    public void testUpdateModifiedResidue() throws Exception {
        assertNotNull(mockMvc);
        
        ModifiedResidue modifiedResidue = new ModifiedResidue();
        modifiedResidue.setDbId(140630L); // This is a known modified residue in the database.
        ReferenceGeneProduct rpg = new ReferenceGeneProduct();
        rpg.setDbId(140617L); // This is a known ReferenceGeneProduct in the database.
        modifiedResidue.setReferenceSequence(rpg);
        
        SimpleInstance instance = converter.convert(modifiedResidue);
        System.out.println("Found modified residue: " + instance);
        
        DatabaseObject object = converter.convert(instance);
        System.out.println("Converted to DatabaseObject: " + object);
    }
    
    @Test
    public void testStoreReactionWithNewValues() throws Exception {
        assertNotNull(mockMvc);
        
        String url = BASE_URL + "store";
        logger.info("URL: " + url);
        
        ObjectMapper mapper = CurationWSTestHelper.createObjectMapper();
        
        Reaction reaction = CurationWSTestHelper.createReaction();
        String reactionJSON = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(reaction);
        logger.info("Reaction in JSON:\n" + reactionJSON);
        
        // Store
        String dbId = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(reactionJSON))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        logger.info("Done saving a new Reaction: " + dbId);
    }
    
    @Test
    public void testGetReferrers() throws Exception {
        assertNotNull(mockMvc);
        
        Long dbId = 9684445L; // ModifiedNucleotide
        dbId = 9686417L; // InstanceEdit used to modify the above instance
        String url = BASE_URL + "getReferrers/" + dbId;
        logger.info("URL: " + url);
        
        // Store
        String rtn = mockMvc.perform(get(url).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        System.out.println(rtn);
    }
    
    @Test
    public void testFillReference() throws Exception {
        assertNotNull(mockMvc);
        
        String url = BASE_URL + "fillReference";
        logger.info("URL: " + url);
        
        ObjectMapper mapper = CurationWSTestHelper.createObjectMapper();
        
        SimpleInstance reference = new SimpleInstance();
        reference.setDbId(-1L);
        reference.setSchemaClassName(ReactomeJavaConstants.LiteratureReference);
        // There is a collectiveName here
        reference.setAttribute(ReactomeJavaConstants.pubMedIdentifier, 21794845);
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(reference);
        logger.info("Reaction in JSON:\n" + json);
        
        // Store
        String rtn = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        logger.info("Done filling a reference: " + rtn);
    }
    
    @Test
    public void testFetchReactionWithParticipants() throws Exception {
        assertNotNull(mockMvc);
        // This reaction has a catalyst
        Long dbId = 5679205L;
        String url = BASE_URL + "fetchReactionWithParticipants/" + dbId;
        String json = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        System.out.println("Reaction with dbId: " + dbId + "\n" + json);
        
        // This reaction has two activators
        dbId = 9631065L;
        url = BASE_URL + "fetchReactionWithParticipants/" + dbId;
        json = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        System.out.println("Reaction with dbId: " + dbId + "\n" + json);
        
        // Something is not ordinary here
        dbId = 9834070L;
        url = BASE_URL + "fetchReactionWithParticipants/" + dbId;
        json = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        System.out.println("Reaction with dbId: " + dbId + "\n" + json);
    }

}