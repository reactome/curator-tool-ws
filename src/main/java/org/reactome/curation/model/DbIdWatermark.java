package org.reactome.curation.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * Durable, monotonic high-water mark for dbId allocation.
 *
 * Deliberately stored in H2 (outside graph.db) so that restoring or replacing the Neo4j store
 * cannot roll it back - see CurationRepository.reconcileDbIdWatermark() for the incident this
 * guards against.
 *
 * Single row, id == 1.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "db_id_watermark")
public class DbIdWatermark {
    @Id
    private Integer id;             // always 1 - no @GeneratedValue

    // Named "mark_value" in the column, not "value" - VALUE is a reserved word in H2 and breaks
    // both DDL and DML. The Java property stays "value"; JPQL (DbIdWatermarkRepository.raiseTo())
    // refers to that property name, not the column name, so it is unaffected.
    @Column(name = "mark_value", nullable = false)
    private Long value;             // highest dbId ever handed out

    @Column(nullable = false)
    private String updatedAt;

    public DbIdWatermark(Long value, String updatedAt) {
        this.id = 1;
        this.value = value;
        this.updatedAt = updatedAt;
    }
}
