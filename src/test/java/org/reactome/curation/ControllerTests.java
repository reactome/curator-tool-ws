package org.reactome.curation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.reactome.curation.controller.CurationController;
import org.reactome.curation.service.CurationService;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.Figure;
import org.reactome.server.graph.domain.model.InstanceEdit;
import org.reactome.server.graph.domain.model.Pathway;
import org.reactome.server.graph.domain.model.ReferenceDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.core.JsonProcessingException;

@SpringBootTest
class ControllerTests {

    @Autowired
    private CurationController controller;

    @Test
    void contextLoads() {
    }

    @Test
    public void testFindById() {
        assertNotNull(controller);
        Long[] dbIds = {
                109581L, // Pathway
                72810L, // NCBI Taxonomy
                9707103L, // A figure
                72811L, // InstanceEdit
        };
        Class<?>[] classes = {
                Pathway.class,
                ReferenceDatabase.class,
                Figure.class,
                InstanceEdit.class
        };
        for (int i = 0; i < dbIds.length; i++) {
            DatabaseObject obj = controller.findByDdId(dbIds[i]);
            assertEquals(obj.getClass(), classes[i]);
        }
    }

}
