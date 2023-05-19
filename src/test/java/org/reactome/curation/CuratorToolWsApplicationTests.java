package org.reactome.curation;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.reactome.curation.service.CurationService;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.core.JsonProcessingException;

@SpringBootTest
class CuratorToolWsApplicationTests {

    @Autowired
    private CurationService curationService;

    @Test
    void contextLoads() {
    }

    @Test
    public void testFindById() throws JsonProcessingException {
        assertNotNull(curationService);
        Long[] dbIds = {
                109581L, // Pathway
                72810L, // NCBI Taxonomy
                9707103L, // A figure
                72811L, // InstanceEdit
        };
        for (Long dbId : dbIds) {
            DatabaseObject obj = curationService.findById(dbId);
            System.out.println(obj);
        }
    }

}
