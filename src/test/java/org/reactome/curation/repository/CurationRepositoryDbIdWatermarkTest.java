package org.reactome.curation.repository;

import org.junit.jupiter.api.Test;
import org.reactome.curation.exceptions.DbRollbackDetectedException;
import org.reactome.server.graph.domain.model.SimpleEntity;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the dbId watermark feature wired into CurationRepository: the constructor's
 * reconcileDbIdWatermark() and the writesDisabled guard in nextDbId()/storeShell(). No database:
 * neo4jClient is mocked deeply enough for the constructor's index creation and MAX(dbId) lookup
 * (same setup as CurationRepositoryFindInvalidReferenceTest); watermark is a full mock so each test
 * controls exactly what the durable H2 mark "was" before this repository started up.
 */
class CurationRepositoryDbIdWatermarkTest {

    private CurationRepository buildRepository(Long graphMax, Long storedMark, DbIdWatermarkStore watermark) {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).fetchAs(Long.class).one()).thenReturn(Optional.ofNullable(graphMax));
        when(watermark.current()).thenReturn(storedMark);
        return new CurationRepository(neo4jClient, null, new CypherQueryUtilities(), watermark);
    }

    @Test
    void firstBootSeedsTheWatermarkFromGraphMax() {
        DbIdWatermarkStore watermark = mock(DbIdWatermarkStore.class);
        buildRepository(10002922L, null, watermark);

        verify(watermark).reserve(10002922L);
    }

    @Test
    void nextDbIdRaisesTheMarkBeforeReturning() {
        DbIdWatermarkStore watermark = mock(DbIdWatermarkStore.class);
        CurationRepository repository = buildRepository(10002922L, 10002922L, watermark);
        reset(watermark); // only care about calls made by nextDbId() itself, not the constructor's

        Long first = repository.nextDbId();
        Long second = repository.nextDbId();

        assertEquals(10002923L, first);
        assertEquals(10002924L, second);
        verify(watermark).reserve(10002923L);
        verify(watermark).reserve(10002924L);
    }

    @Test
    void noRewindAfterRestart_seededMarkWinsOverALowerGraphMax() {
        // The exact shape of the 2026-09-24 incident: graph.db was restored to an older dump, so
        // MAX(n.dbId) is 50 lower than the watermark this app already durably recorded.
        DbIdWatermarkStore watermark = mock(DbIdWatermarkStore.class);
        CurationRepository repository = buildRepository(10002803L - 50, 10002803L, watermark);

        // Math.max(graphMax, mark) in reconcileDbIdWatermark() should have already raised maxDbId
        // to the watermark - so the very first dbId issued is one past the mark, not one past the
        // stale, lower graph max.
        assertTrue(repository.isWritesDisabled(), "a graph behind the watermark must block writes");
        assertThrows(DbRollbackDetectedException.class, repository::nextDbId,
                "must refuse to allocate at all once a rollback is detected, not just clamp the value");
    }

    @Test
    void rollbackDetectionAlsoBlocksStoreShellWithACallerSuppliedDbId() throws Exception {
        DbIdWatermarkStore watermark = mock(DbIdWatermarkStore.class);
        CurationRepository repository = buildRepository(10002760L, 10002803L, watermark);

        SimpleEntity obj = new SimpleEntity();
        obj.setDbId(10002761L); // a dbId the caller supplies directly, bypassing nextDbId()

        assertThrows(DbRollbackDetectedException.class, () -> repository.storeShell(obj));
    }

    @Test
    void noRollbackWhenGraphIsAheadOfOrEqualToTheWatermark() {
        DbIdWatermarkStore watermark = mock(DbIdWatermarkStore.class);
        CurationRepository repository = buildRepository(10002803L, 10002803L, watermark);

        assertFalse(repository.isWritesDisabled());
        assertDoesNotThrow(repository::nextDbId);
    }

    @Test
    void orderingSurvivesADownstreamNeo4jFailure_theMarkStaysRaised() {
        // watermark.reserve() runs in its own REQUIRES_NEW transaction and commits to H2 before
        // nextDbId() returns - so even if the Neo4j write that follows throws and storeShell()'s
        // ambient transaction rolls back, the dbId is a burned gap, never reissued. This test
        // documents that guarantee at the unit level: reserve() must be called, and therefore
        // (per DbIdWatermarkStore's own contract) committed, before nextDbId() can return the
        // value to a caller that might go on to fail.
        DbIdWatermarkStore watermark = mock(DbIdWatermarkStore.class);
        CurationRepository repository = buildRepository(10002922L, 10002922L, watermark);
        reset(watermark);

        doThrow(new RuntimeException("simulated Neo4j failure")).when(watermark).reserve(anyLong());

        assertThrows(RuntimeException.class, repository::nextDbId);
        verify(watermark).reserve(10002923L);
    }
}
