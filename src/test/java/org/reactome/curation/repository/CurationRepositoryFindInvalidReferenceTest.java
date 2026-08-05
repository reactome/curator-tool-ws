package org.reactome.curation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactome.server.graph.domain.model.Complex;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.InstanceEdit;
import org.reactome.server.graph.domain.model.SimpleEntity;
import org.springframework.data.neo4j.core.Neo4jClient;

/**
 * Unit tests for CurationRepository.findMissingReference()/findMismatchedReference() - the pure
 * comparison logic behind findAnyInvalidValue()'s safeguard against store()'s batched
 * relationship-recreation Cypher silently wiping an object's relationships (see
 * CurationRepositoryRelationshipRecreationTest for why that Cypher shape is dangerous). These
 * don't touch a database: actualLabels stands in for whatever fetchNodeLabels() would have
 * returned. neo4jClient is only mocked deeply enough for CurationRepository's constructor (its
 * startup index-creation and max-dbId lookup) - findMissingReference()/findMismatchedReference()
 * themselves never call it; neo4jTemplate is passed as null since the constructor only stores it,
 * never calls it, and it's a final class Mockito can't mock without the inline mock maker.
 */
public class CurationRepositoryFindInvalidReferenceTest {

    private CurationRepository repository;

    @BeforeEach
    void setUp() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).fetchAs(Long.class).one()).thenReturn(Optional.of(1L));
        repository = new CurationRepository(neo4jClient, null, new CypherQueryUtilities());
    }

    @Test
    void findMissingReferenceShouldReturnNullWhenEveryDbIdIsKnown() {
        Complex complex = new Complex(200L);
        SimpleEntity entity = new SimpleEntity();
        entity.setDbId(300L);

        Map<Long, Set<String>> actualLabels = new HashMap<>();
        actualLabels.put(200L, Set.of("Complex", "PhysicalEntity", "DatabaseObject"));
        actualLabels.put(300L, Set.of("SimpleEntity", "PhysicalEntity", "DatabaseObject"));

        assertThat(repository.findMissingReference(List.of(complex, entity), actualLabels)).isNull();
    }

    @Test
    void findMissingReferenceShouldReturnTheReferenceWhoseDbIdIsUnknown() {
        Complex complex = new Complex(200L);
        SimpleEntity entity = new SimpleEntity();
        entity.setDbId(999L); // Doesn't exist at all - not present in actualLabels.

        Map<Long, Set<String>> actualLabels = new HashMap<>();
        actualLabels.put(200L, Set.of("Complex", "DatabaseObject"));

        DatabaseObject missing = repository.findMissingReference(List.of(complex, entity), actualLabels);
        assertThat(missing).isSameAs(entity);
    }

    @Test
    void findMismatchedReferenceShouldReturnNullWhenEveryClassMatches() {
        Complex complex = new Complex(200L);

        Map<Long, Set<String>> actualLabels = new HashMap<>();
        actualLabels.put(200L, Set.of("Complex", "DatabaseObject"));

        assertThat(repository.findMismatchedReference(List.of(complex), actualLabels)).isNull();
    }

    /**
     * The scenario this whole safeguard exists for: an attribute expected to hold a Complex
     * resolves (e.g. via a stale/mistyped dbId, or two new objects colliding on the same
     * client-assigned placeholder id) to a dbId that's actually stored as an InstanceEdit.
     */
    @Test
    void findMismatchedReferenceShouldDetectComplexResolvingToInstanceEdit() {
        Complex expectedComplex = new Complex(300L); // Caller expects this dbId to be a Complex...

        Map<Long, Set<String>> actualLabels = new HashMap<>();
        actualLabels.put(300L, Set.of("InstanceEdit", "DatabaseObject")); // ...but it's an InstanceEdit.

        DatabaseObject[] mismatch = repository.findMismatchedReference(List.of(expectedComplex), actualLabels);
        assertThat(mismatch).isNotNull();
        assertThat(mismatch[0]).isSameAs(expectedComplex);
    }

    @Test
    void findMismatchedReferenceShouldIgnoreReferencesMissingFromActualLabels() {
        // A missing dbId is findMissingReference()'s concern - findMismatchedReference() should
        // skip over it rather than treating an absent entry as a mismatch.
        InstanceEdit missingReference = new InstanceEdit();
        missingReference.setDbId(999L);

        assertThat(repository.findMismatchedReference(List.of(missingReference), new HashMap<>())).isNull();
    }

    /**
     * Regression test for the false positive this safeguard originally had: a brand-new
     * InstanceEdit is storeShell()'d first (minting its dbId and a bare node - dbId + Neo4j
     * label only, no "schemaClass" property yet), then the containing object (e.g. the Reaction
     * being edited) is committed BEFORE that InstanceEdit's own full store() call fills in its
     * properties. At that point fetchNodeLabels() already returns "InstanceEdit" for its dbId
     * (labels are set the moment the node is created), even though it has no other properties -
     * so this must NOT be flagged as missing or mismatched.
     */
    @Test
    void findMismatchedReferenceShouldAcceptBareShellOfNewInstanceEdit() {
        InstanceEdit newInstanceEdit = new InstanceEdit(9996616L);

        Map<Long, Set<String>> actualLabels = new HashMap<>();
        actualLabels.put(9996616L, Set.of("InstanceEdit", "DatabaseObject")); // Bare shell, but correctly labeled.

        assertThat(repository.findMissingReference(List.of(newInstanceEdit), actualLabels)).isNull();
        assertThat(repository.findMismatchedReference(List.of(newInstanceEdit), actualLabels)).isNull();
    }
}
