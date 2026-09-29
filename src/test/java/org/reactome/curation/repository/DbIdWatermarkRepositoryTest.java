package org.reactome.curation.repository;

import org.junit.jupiter.api.Test;
import org.reactome.curation.model.DbIdWatermark;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice test for the JPA/H2 side of the dbId watermark feature only - no Neo4j involved, so this
 * runs without a live graph database. @DataJpaTest replaces the configured H2 file datasource with
 * an isolated embedded one, so it never touches the real dev/prod reactome_h2.mv.db.
 */
@DataJpaTest
class DbIdWatermarkRepositoryTest {

    @Autowired
    private DbIdWatermarkRepository repository;

    @Test
    void raiseToCreatesNoRowByItself() {
        // raiseTo() only UPDATEs an existing row (see its "where w.id = 1" clause) - seeding a
        // brand-new row is DbIdWatermarkStore's job, not the repository's.
        int updated = repository.raiseTo(100L, "2026-01-01 00:00:00");
        assertEquals(0, updated);
        assertTrue(repository.findById(1).isEmpty());
    }

    @Test
    void raiseToRaisesTheMark() {
        repository.save(new DbIdWatermark(100L, "2026-01-01 00:00:00"));

        int updated = repository.raiseTo(150L, "2026-01-02 00:00:00");

        assertEquals(1, updated);
        assertEquals(150L, repository.findById(1).get().getValue());
    }

    @Test
    void raiseToIsANoOpWhenTheCandidateIsNotHigher() {
        repository.save(new DbIdWatermark(150L, "2026-01-02 00:00:00"));

        int updatedEqual = repository.raiseTo(150L, "2026-01-03 00:00:00");
        int updatedLower = repository.raiseTo(100L, "2026-01-03 00:00:00");

        assertEquals(0, updatedEqual);
        assertEquals(0, updatedLower);
        assertEquals(150L, repository.findById(1).get().getValue(),
                "the stored mark must never be lowered, even by an explicit call");
    }
}
