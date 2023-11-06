package org.reactome.curation;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.gk.model.ReactomeJavaConstants;
import org.junit.jupiter.api.Test;
import org.reactome.curation.controller.DatabaseObjectInstanceConverter;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.server.graph.domain.model.Complex;
import org.reactome.server.graph.domain.model.Reaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

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
        String url = BASE_URL + "listInstances/" + className + "/0/20";
        String json = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        System.out.println(json);
        // Try reactions
        className = "Reaction";
        url = BASE_URL + "listInstances/" + className + "/100/20?query=EGFR";
        json = mockMvc.perform(get(url))
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
    public void testFindById() throws Exception {
        assertNotNull(mockMvc);
        Long[] dbIds = {
                //TODO: The following query is used:
                // MATCH (n:DatabaseObject{dbId:$dbId}) OPTIONAL MATCH (n)-[r]-(m) WITH n, r, m ORDER BY TYPE(r) ASC, r.order ASC RETURN n, COLLECT(r), COLLECT(m) LIMIT $limit
                // This query is slow for homo sapiens because of the undirection relationship, which will pull out many results. 
                // Need to consider to add the direction here to increase the performance.
                48887L, // Homo sapiens. The query is quite slow!
//                109581L, // Pathway
//                72810L, // NCBI Taxonomy
//                9707103L, // A figure
//                72811L, // InstanceEdit
        };
        // The URL should start with "/" to make it true
        String url = BASE_URL + "findDatabaseObjectByDbId/";
        for (Long dbId : dbIds) {
            String json = mockMvc.perform(get(url + dbId))
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
    public void testFindByIdInInstance() throws Exception {
        assertNotNull(mockMvc);
        Long[] dbIds = {
                141412L, // An EWAS
                141429L, // A reaction has the same instance appearing in two slots.
                109581L, // Pathway
                72810L, // NCBI Taxonomy
                9707103L, // A figure
                72811L, // InstanceEdit
        };
        // The URL should start with "/" to make it true
        String url = BASE_URL + "findByDbId/";
        for (Long dbId : dbIds) {
            String json = mockMvc.perform(get(url + dbId))
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
        };
        String url = BASE_URL + "getAttributes/";
        for (String clsName : clsNames) {
            String json = mockMvc.perform(get(url + clsName))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            System.out.println(clsName + ":\n" + json);
        }
    }
    
    /**
     * Store a new complex with new references and a new reaction with new inputs/outputs.
     * These two new instances have some overlapped negative DB_IDs. This check is to make sure
     * these overlapped DB_IDs are not shared during deserialization. Newly stored instances 
     * should have their own set of positive DB_IDs in the database.
     * @throws Exception
     */
    @Test
    public void testStoreComplexAndReaction() throws Exception {
        assertNotNull(mockMvc);
        
        String url = BASE_URL + "storeDatabaseObject";
        logger.info("URL: " + url);
        
        ObjectMapper mapper = CurationWSTestHelper.createObjectMapper();
        
        Complex complex = CurationWSTestHelper.createComplexWithNewComplexAndSubunit();
        String complexJSON = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(complex);
        logger.info("Complex in JSON:\n" + complexJSON);
        
        Reaction reaction = CurationWSTestHelper.createReaction();
        String reactionJSON = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(reaction);
        logger.info("Reaction in JSON:\n" + reactionJSON);
        
//        // Store
//        String dbId = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
//                           .content(complexJSON))
//                           .andExpect(status().isOk())
//                           .andReturn()
//                           .getResponse()
//                           .getContentAsString();
//        logger.info("Done saving a new Complex: " + dbId);
//        
//        dbId = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
//                .content(reactionJSON))
//                .andExpect(status().isOk())
//                .andReturn()
//                .getResponse()
//                .getContentAsString();
//        logger.info("Done saving a new Reaction: " + dbId);
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
        ObjectMapper mapper = CurationWSTestHelper.createObjectMapper();
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(complex);
        logger.info("Complex in JSON:\n" + json);
        String url = BASE_URL + "store";
        logger.info("URL: " + url);
        String dbId = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                           .content(json))
                           .andExpect(status().isOk())
                           .andReturn()
                           .getResponse()
                           .getContentAsString();
        logger.info("Done saving a new Complex: " + dbId);
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

}
