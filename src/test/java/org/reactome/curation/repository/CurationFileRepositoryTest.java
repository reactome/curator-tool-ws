package org.reactome.curation.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.reactome.curation.model.UserInstances;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * No Spring context or database is needed: the repository only works with files.
 */
class CurationFileRepositoryTest {

    @TempDir
    Path tempDir;

    /**
     * The front-end may send many saves for the same user at once (e.g. retrying queued saves
     * after a token refresh). None of them should fail, and the number of backups should stay
     * within the configured maximum.
     */
    @Test
    void testConcurrentSavesForSameUser() throws Exception {
        int maxBackupCount = 5;
        CurationFileRepository repository = new CurationFileRepository();
        ReflectionTestUtils.setField(repository, "maxBackupCount", maxBackupCount);
        String fileName = tempDir.resolve("jdoe").resolve("jdoe.json").toString();
        repository.persist(new UserInstances(), fileName); // So that every save below makes a backup

        int saves = 20;
        ExecutorService executor = Executors.newFixedThreadPool(saves);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < saves; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                repository.persist(new UserInstances(), fileName);
                return null;
            }));
        }
        start.countDown(); // Release all saves at the same moment
        for (Future<?> future : futures)
            future.get(30, TimeUnit.SECONDS); // Throws if any save failed
        executor.shutdown();

        assertNotNull(repository.load(fileName));
        assertTrue(new File(fileName).isFile());
        int backups = repository.listBackups(fileName).size();
        assertTrue(backups > 0 && backups <= maxBackupCount, "Backups: " + backups);
        for (var backup : repository.listBackups(fileName))
            assertNotNull(repository.loadBackup(fileName, backup.getFileName()));
        assertEquals(maxBackupCount, backups);
    }

}
