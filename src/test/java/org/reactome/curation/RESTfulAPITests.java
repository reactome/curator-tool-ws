package org.reactome.curation;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.System.LoggerFinder;
import java.net.URLEncoder;

import org.gk.model.ReactomeJavaConstants;
import org.junit.jupiter.api.Test;
import org.reactome.server.graph.domain.model.Complex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class RESTfulAPITests {
    private static final Logger logger = LoggerFactory.getLogger(RESTfulAPITests.class);
    
    private final String BASE_URL = "/api/curation/";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
    }

    @Test
    public void testFindById() throws Exception {
        assertNotNull(mockMvc);
        Long[] dbIds = {
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
                ReactomeJavaConstants.EntityWithAccessionedSequence,
                ReactomeJavaConstants.Pathway,
                ReactomeJavaConstants.Reaction,
                ReactomeJavaConstants.ReferenceGeneProduct
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

}
