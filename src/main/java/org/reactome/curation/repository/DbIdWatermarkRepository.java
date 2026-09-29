package org.reactome.curation.repository;

import org.reactome.curation.model.DbIdWatermark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DbIdWatermarkRepository extends JpaRepository<DbIdWatermark, Integer> {

    /**
     * Monotonic raise. The "and w.value < :v" clause makes it impossible to lower the mark,
     * even if a caller passes a stale value.
     *
     * clearAutomatically evicts the persistence context after the bulk update - without it, a
     * DbIdWatermark instance already loaded/saved earlier in the same transaction would keep
     * showing its pre-update value to a later find()/findById() call, since a bulk JPQL update
     * bypasses Hibernate's first-level cache.
     *
     * @return rows updated (0 == the stored mark was already >= v, or the row doesn't exist yet)
     */
    @Modifying(clearAutomatically = true)
    @Query("update DbIdWatermark w set w.value = :v, w.updatedAt = :ts where w.id = 1 and w.value < :v")
    int raiseTo(@Param("v") long v, @Param("ts") String ts);
}
