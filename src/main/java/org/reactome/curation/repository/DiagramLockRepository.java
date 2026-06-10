package org.reactome.curation.repository;

import org.reactome.curation.model.DiagramLock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface DiagramLockRepository extends JpaRepository<DiagramLock, Long> {
    Optional<DiagramLock> findByDiagramDbId(Long diagramDbId);

    List<DiagramLock> findByUsername(String username);

    @Transactional
    void deleteByDiagramDbId(Long diagramDbId);

    boolean existsByDiagramDbId(Long diagramDbId);

    default DiagramLock add(DiagramLock diagramLock) {
        return save(diagramLock);
    }

    default Optional<DiagramLock> load(Long diagramDbId) {
        return findByDiagramDbId(diagramDbId);
    }

    default List<DiagramLock> load() {
        return findAll();
    }

    default void delete(Long diagramDbId) {
        deleteByDiagramDbId(diagramDbId);
    }
}

