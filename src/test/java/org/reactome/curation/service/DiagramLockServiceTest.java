package org.reactome.curation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.reactome.curation.model.DiagramLock;

import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DiagramLockService
 * Tests the core functionality of diagram locking and unlocking
 */
@DisplayName("DiagramLockService Tests")
class DiagramLockServiceTest {

    private DiagramLockService service;

    @BeforeEach
    void setUp() {
        service = new DiagramLockService();
    }

    @Test
    @DisplayName("Should lock a diagram successfully")
    void testLockDiagramSuccess() {
        Long diagramId = 12345L;
        String username = "curator1";

        DiagramLock lock = service.lockDiagram(diagramId, username);

        assertNotNull(lock);
        assertEquals(diagramId, lock.getDiagramDbId());
        assertEquals(username, lock.getUsername());
        assertEquals("LOCKED", lock.getStatus());
        assertNotNull(lock.getLockedAt());
    }

    @Test
    @DisplayName("Should prevent locking already locked diagram by different user")
    void testLockLockedDiagramByDifferentUser() {
        Long diagramId = 12345L;
        String user1 = "curator1";
        String user2 = "curator2";

        service.lockDiagram(diagramId, user1);

        assertThrows(IllegalStateException.class, () -> {
            service.lockDiagram(diagramId, user2);
        });
    }

    @Test
    @DisplayName("Should allow same user to re-lock their diagram (idempotent)")
    void testReLockBysamUser() {
        Long diagramId = 12345L;
        String username = "curator1";

        DiagramLock lock1 = service.lockDiagram(diagramId, username);
        DiagramLock lock2 = service.lockDiagram(diagramId, username);

        assertNotNull(lock1);
        assertNotNull(lock2);
        assertEquals(lock1.getDiagramDbId(), lock2.getDiagramDbId());
    }

    @Test
    @DisplayName("Should throw exception for null diagram dbId")
    void testLockWithNullDiagramId() {
        assertThrows(IllegalArgumentException.class, () -> {
            service.lockDiagram(null, "curator1");
        });
    }

    @Test
    @DisplayName("Should throw exception for invalid (negative) diagram dbId")
    void testLockWithNegativeDiagramId() {
        assertThrows(IllegalArgumentException.class, () -> {
            service.lockDiagram(-1L, "curator1");
        });
    }

    @Test
    @DisplayName("Should throw exception for null username")
    void testLockWithNullUsername() {
        assertThrows(IllegalArgumentException.class, () -> {
            service.lockDiagram(12345L, null);
        });
    }

    @Test
    @DisplayName("Should throw exception for empty username")
    void testLockWithEmptyUsername() {
        assertThrows(IllegalArgumentException.class, () -> {
            service.lockDiagram(12345L, "");
        });
    }

//    @Test
//    @DisplayName("Should unlock a diagram successfully")
//    void testUnlockDiagramSuccess() {
//        Long diagramId = 12345L;
//        String username = "curator1";
//
//        service.lockDiagram(diagramId, username);
//        boolean result = service.unlockDiagram(diagramId, username);
//
//        assertTrue(result);
//        assertNull(service.getLock(diagramId));
//    }
//
//    @Test
//    @DisplayName("Should return false when unlocking by different user")
//    void testUnlockByDifferentUser() {
//        Long diagramId = 12345L;
//        String user1 = "curator1";
//        String user2 = "curator2";
//
//        service.lockDiagram(diagramId, user1);
//        boolean result = service.unlockDiagram(diagramId, user2);
//
//        assertFalse(result);
//        assertNotNull(service.getLock(diagramId));
//    }
//
//    @Test
//    @DisplayName("Should return false when unlocking unlocked diagram")
//    void testUnlockUnlockedDiagram() {
//        Long diagramId = 12345L;
//        String username = "curator1";
//
//        boolean result = service.unlockDiagram(diagramId, username);
//
//        assertFalse(result);
//    }
//
//    @Test
//    @DisplayName("Should check if diagram is locked")
//    void testIsLocked() {
//        Long diagramId = 12345L;
//        String username = "curator1";
//
//        assertFalse(service.isLocked(diagramId));
//
//        service.lockDiagram(diagramId, username);
//        assertTrue(service.isLocked(diagramId));
//
//        service.unlockDiagram(diagramId, username);
//        assertFalse(service.isLocked(diagramId));
//    }

    @Test
    @DisplayName("Should get lock information")
    void testGetLock() {
        Long diagramId = 12345L;
        String username = "curator1";

        assertNull(service.getLock(diagramId));

        DiagramLock lock = service.lockDiagram(diagramId, username);
        DiagramLock retrieved = service.getLock(diagramId);

        assertNotNull(retrieved);
        assertEquals(lock.getDiagramDbId(), retrieved.getDiagramDbId());
        assertEquals(lock.getUsername(), retrieved.getUsername());
    }


    @Test
    @DisplayName("Should get all locked diagrams")
    void testGetAllLockedDiagrams() {
        String user1 = "curator1";
        String user2 = "curator2";

        service.lockDiagram(12345L, user1);
        service.lockDiagram(12346L, user1);
        service.lockDiagram(12347L, user2);

        Map<Long, DiagramLock> allLocked = service.getAllLockedDiagrams();

        assertEquals(3, allLocked.size());
        assertTrue(allLocked.containsKey(12345L));
        assertTrue(allLocked.containsKey(12346L));
        assertTrue(allLocked.containsKey(12347L));
    }


    @Test
    @DisplayName("Should handle thread safety for concurrent locks")
    void testThreadSafety() throws InterruptedException {
        String user1 = "curator1";
        String user2 = "curator2";

        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                service.lockDiagram((long)(10000 + i), user1);
            }
        });

        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                service.lockDiagram((long)(20000 + i), user2);
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        Map<Long, DiagramLock> allLocked = service.getAllLockedDiagrams();
        assertEquals(200, allLocked.size());
    }


    @Test
    @DisplayName("Should return empty map when no diagrams are locked")
    void testGetAllLockedDiagramsEmpty() {
        Map<Long, DiagramLock> allLocked = service.getAllLockedDiagrams();
        assertTrue(allLocked.isEmpty());
    }
}
