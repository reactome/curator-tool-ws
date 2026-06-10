package org.reactome.curation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reactome.curation.model.DiagramLock;
import org.reactome.curation.repository.DiagramLockRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DiagramLockService
 * Tests the core functionality of diagram locking and unlocking
 */
@DisplayName("DiagramLockService Tests")
class DiagramLockServiceTest {

    private DiagramLockRepository mockRepository;
    private Map<Long, DiagramLock> storage;
    private DiagramLockService service;

    @BeforeEach
    void setUp() {
        storage = new HashMap<>();
        mockRepository = mock(DiagramLockRepository.class);

        when(mockRepository.load(anyLong())).thenAnswer(invocation -> {
            Long diagramDbId = invocation.getArgument(0);
            return Optional.ofNullable(storage.get(diagramDbId));
        });
        when(mockRepository.load()).thenAnswer(invocation -> new ArrayList<>(storage.values()));
        when(mockRepository.findByUsername(any(String.class))).thenAnswer(invocation -> {
            String username = invocation.getArgument(0);
            List<DiagramLock> locks = new ArrayList<>();
            for (DiagramLock lock : storage.values()) {
                if (username.equals(lock.getUsername())) {
                    locks.add(lock);
                }
            }
            return locks;
        });
        when(mockRepository.existsByDiagramDbId(anyLong())).thenAnswer(invocation -> {
            Long diagramDbId = invocation.getArgument(0);
            return storage.containsKey(diagramDbId);
        });
        when(mockRepository.count()).thenAnswer(invocation -> (long) storage.size());
        when(mockRepository.add(any(DiagramLock.class))).thenAnswer(invocation -> {
            DiagramLock lock = invocation.getArgument(0);
            if (storage.containsKey(lock.getDiagramDbId())) {
                throw new DataIntegrityViolationException("Duplicate diagram lock for " + lock.getDiagramDbId());
            }
            storage.put(lock.getDiagramDbId(), lock);
            return lock;
        });
        doAnswer(invocation -> {
            Long diagramDbId = invocation.getArgument(0);
            storage.remove(diagramDbId);
            return null;
        }).when(mockRepository).delete(any(Long.class));
        doAnswer(invocation -> {
            storage.clear();
            return null;
        }).when(mockRepository).deleteAll();

        service = new DiagramLockService(mockRepository);
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
        assertNotNull(lock.getLockedAt());
    }

    @Test
    @DisplayName("Should return existing lock when diagram is already locked by different user")
    void testLockLockedDiagramByDifferentUser() {
        Long diagramId = 12345L;
        String user1 = "curator1";
        String user2 = "curator2";

        DiagramLock firstLock = service.lockDiagram(diagramId, user1);
        DiagramLock returnedLock = service.lockDiagram(diagramId, user2);

        assertNotNull(firstLock);
        assertNotNull(returnedLock);
        assertEquals(user1, returnedLock.getUsername());
        assertEquals(firstLock.getLockId(), returnedLock.getLockId());
        assertEquals(firstLock.getDiagramDbId(), returnedLock.getDiagramDbId());
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
        assertEquals(lock1.getLockId(), lock2.getLockId());
    }

    @Test
    @DisplayName("Should keep exactly one lock holder for concurrent lock attempts on same diagram")
    void testConcurrentLockSameDiagramMutualExclusion() throws InterruptedException {
        Long diagramId = 12345L;

        Thread t1 = new Thread(() -> service.lockDiagram(diagramId, "curator1"));
        Thread t2 = new Thread(() -> service.lockDiagram(diagramId, "curator2"));

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        DiagramLock lock = service.getLock(diagramId);
        assertNotNull(lock);
        assertTrue("curator1".equals(lock.getUsername()) || "curator2".equals(lock.getUsername()));
        assertEquals(1, service.getAllLockedDiagrams().size());
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

    @Test
    @DisplayName("Should keep DiagramLockService transactional for delete operations")
    void testDiagramLockServiceIsTransactional() {
        assertNotNull(DiagramLockService.class.getAnnotation(Transactional.class));
    }

    @Test
    @DisplayName("Should keep repository deleteByDiagramDbId transactional")
    void testDeleteByDiagramDbIdIsTransactional() throws NoSuchMethodException {
        Method method = DiagramLockRepository.class.getMethod("deleteByDiagramDbId", Long.class);
        assertNotNull(method.getAnnotation(Transactional.class));
    }
}
