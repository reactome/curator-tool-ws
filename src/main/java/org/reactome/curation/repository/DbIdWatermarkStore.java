package org.reactome.curation.repository;

import org.reactome.curation.model.DbIdWatermark;
import org.reactome.curation.util.CuratorToolWSUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.persistence.EntityManagerFactory;

/**
 * Durable, monotonic high-water mark for dbId allocation, backed by H2 - a store with a
 * lifecycle completely separate from graph.db, so restoring or replacing the Neo4j store
 * cannot roll this back. See CurationRepository.reconcileDbIdWatermark().
 *
 * Lives in the repository package, not service: CurationService already depends on
 * CurationRepository (the normal service -> repository direction), so a repository depending on
 * a class in the service package would create a package-level cycle. This is purely a
 * persistence-layer helper around DbIdWatermarkRepository.
 *
 * Both spring-boot-starter-data-neo4j and spring-boot-starter-data-jpa auto-configure a
 * PlatformTransactionManager bean named "transactionManager" (Neo4jTransactionManager and
 * JpaTransactionManager respectively), each guarded by @ConditionalOnMissingBean(TransactionManager.class),
 * so only one of them ends up registered - and which one depends on Spring Boot's internal
 * auto-configuration ordering, which isn't pinned by any explicit @AutoConfigureBefore/After
 * between the two (verified empirically: today it resolves to JpaTransactionManager, but nothing
 * guarantees that survives a Spring Boot upgrade or a dependency change).
 *
 * Registering a second, explicitly-named PlatformTransactionManager *bean* to work around that
 * doesn't work either: @ConditionalOnMissingBean(TransactionManager.class) matches by type, not
 * name, so any additional TransactionManager-typed bean - regardless of its name - suppresses
 * BOTH auto-configured candidates, leaving no bean named "transactionManager" at all and breaking
 * every other plain @Transactional in the app (confirmed the hard way: this exact mistake broke
 * DiagramLockService).
 *
 * So this class builds its own JpaTransactionManager directly from the injected
 * EntityManagerFactory instead of exposing it as a bean. It is never registered in the
 * application context, so it can't participate in - or be suppressed by - anyone else's
 * @ConditionalOnMissingBean check, while still being unambiguously JPA/H2-backed regardless of
 * whatever "transactionManager" resolves to elsewhere.
 */
@Repository
public class DbIdWatermarkStore {

    private final DbIdWatermarkRepository repo;
    private final TransactionTemplate requiresNewJpaTransaction;
    private final TransactionTemplate readOnlyJpaTransaction;

    public DbIdWatermarkStore(DbIdWatermarkRepository repo, EntityManagerFactory entityManagerFactory) {
        this.repo = repo;
        JpaTransactionManager jpaTransactionManager = new JpaTransactionManager(entityManagerFactory);

        this.requiresNewJpaTransaction = new TransactionTemplate(jpaTransactionManager);
        this.requiresNewJpaTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        this.readOnlyJpaTransaction = new TransactionTemplate(jpaTransactionManager);
        this.readOnlyJpaTransaction.setReadOnly(true);
    }

    /**
     * Durably record that dbId {@code v} is now considered used. PROPAGATION_REQUIRES_NEW forces
     * this to commit to H2 on its own, independent of - and before - whatever ambient Neo4j
     * transaction the caller (e.g. CurationRepository.nextDbId(), invoked from inside
     * storeShell()) may be running. Without REQUIRES_NEW this write could enlist in that ambient
     * transaction and be rolled back together with the Neo4j failure it is meant to outlive.
     */
    public void reserve(long v) {
        requiresNewJpaTransaction.executeWithoutResult(status -> {
            if (repo.raiseTo(v, CuratorToolWSUtils.getDateTime()) == 0 && !repo.existsById(1)) {
                repo.save(new DbIdWatermark(v, CuratorToolWSUtils.getDateTime()));
            }
        });
    }

    /** Current mark, or null if never seeded. */
    public Long current() {
        return readOnlyJpaTransaction.execute(status ->
                repo.findById(1).map(DbIdWatermark::getValue).orElse(null));
    }
}
