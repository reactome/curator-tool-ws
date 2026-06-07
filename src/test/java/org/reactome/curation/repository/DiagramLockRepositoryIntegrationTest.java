
package org.reactome.curation.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.reactome.curation.model.DiagramLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DiagramLockRepositoryIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DiagramLockRepository diagramLockRepository;

    @AfterEach
    void cleanUp() {
        diagramLockRepository.deleteAll();
    }

    @Test
    void dataSourceShouldUseH2() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            assertNotNull(url, "JDBC URL should not be null");
            assertTrue(url.contains("jdbc:h2:"), "Expected H2 JDBC URL but got: " + url);
        }
    }

    @Test
    void saveAndFindByDiagramDbIdShouldRoundTrip() {
        Long diagramDbId = 990001L;
        DiagramLock lock = new DiagramLock(diagramDbId, "integration_tester", "2026-06-07 12:00:00");

        DiagramLock saved = diagramLockRepository.save(lock);
        assertNotNull(saved.getId(), "Saved lock should have generated id");

        Optional<DiagramLock> loaded = diagramLockRepository.findByDiagramDbId(diagramDbId);
        assertTrue(loaded.isPresent(), "Expected persisted lock for diagramDbId " + diagramDbId);
        assertEquals(diagramDbId, loaded.get().getDiagramDbId());
        assertEquals("integration_tester", loaded.get().getUsername());
    }
}