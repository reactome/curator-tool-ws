package org.reactome.curation.qa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.reactome.curation.qa.model.QAReport;
import org.reactome.curation.qa.model.ReactionImbalance;
import org.reactome.curation.qa.model.ReactionImbalanceReport;
import org.reactome.curation.qa.model.ReferenceImbalance;
import org.reactome.curation.service.CurationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;

// An in-memory H2 so that this test can run while the server holds the user database file
@SpringBootTest(properties = {
        "logging.dir=target/test-logs",
        "spring.datasource.url=jdbc:h2:mem:reaction-imbalance-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ReactionImbalanceCheckTest {

    @Autowired
    private QAService qaService;
    @Autowired
    private CurationService curationService;
    @Autowired
    private Neo4jClient neo4jClient;

    private ReactionImbalanceReport checkEvent(Long dbId) {
        return new ReactionImbalanceHelper(neo4jClient).checkEvent(dbId);
    }

    // Note: This is needed.
    @Test
    void contextLoads() {
    }

    @Test
    public void testBalancedReaction() throws Exception {
        // Inputs and outputs have the same proteins, including an input with stoichiometry 4
        ReactionImbalanceReport report = checkEvent(5218891L);
        assertEquals(1, report.getCheckedReactions());
        assertTrue(report.getReactions().isEmpty());
    }

    @Test
    public void testCleavageReaction() throws Exception {
        // Pro-EGF is cleaved to form mature EGF: one EGF in each side so it is not imbalanced
        assertTrue(checkEvent(177946L).getReactions().isEmpty());
        // Autoproteolytic cleavage of Hh precursors: each of DHH, IHH and SHH goes from 1 to 2
        Long dbId = 5358340L;
        ReactionImbalanceReport report = checkEvent(dbId);
        printReport(report);
        assertEquals(1, report.getReactions().size());
        ReactionImbalance reaction = report.getReactions().get(0);
        assertTrue(reaction.isCleavage());
        assertEquals(3, reaction.getImbalances().size());
        for (ReferenceImbalance ref : reaction.getImbalances()) {
            assertEquals(1, ref.getInputCount());
            assertEquals(2, ref.getOutputCount());
        }
        QACheckUtilities.performQACheck(dbId, curationService, qaService);
    }

    @Test
    public void testPolyubiquitinModification() throws Exception {
        // APC is deubiquitinated: proteins in a K63 polyubiquitin modification (minUnitCount = 3) are counted
        Long dbId = 9666616L;
        ReactionImbalanceReport report = checkEvent(dbId);
        printReport(report);
        assertEquals(1, report.getReactions().size());
        ReactionImbalance reaction = report.getReactions().get(0);
        assertFalse(reaction.isCleavage());
        assertEquals(4, reaction.getImbalances().size());
        QACheckUtilities.performQACheck(dbId, curationService, qaService);
    }

    @Test
    public void testPathway() throws Exception {
        // Signal Transduction
        long time = System.currentTimeMillis();
        ReactionImbalanceReport report = checkEvent(162582L);
        time = System.currentTimeMillis() - time;
        System.out.println("Time: " + time + " ms");
        long cleavage = report.getReactions().stream().filter(ReactionImbalance::isCleavage).count();
        System.out.println(report.getEventDisplayName() + ": checked " + report.getCheckedReactions() +
                           ", imbalanced " + report.getReactions().size() + ", cleavage " + cleavage);
        report.getReactions().stream().limit(5).forEach(r -> printReaction(r));
    }

    /**
     * A single Reaction keeps its own columns in the QA report.
     */
    @Test
    public void testReactionInQAReport() throws Exception {
        // Autoproteolytic cleavage of Hh precursors (see testCleavageReaction)
        QACheckResult result = imbalanceResult(5358340L, "Reaction");
        assertArrayEquals(new String[] {"Reference", "Identifier", "Input", "Output"}, result.getColumns());
        assertEquals(3, result.getRows().size());
        assertEquals("Possible cleavage reaction", result.getIssue());
        // A balanced reaction passes
        assertTrue(imbalanceResult(5218891L, "Reaction").isPassed());
    }

    /**
     * A Pathway gets one row per imbalanced protein of each imbalanced Reaction it contains, the same results
     * as ReactionImbalanceHelper.checkEvent().
     */
    @Test
    public void testPathwayInQAReport() throws Exception {
        Long dbId = 162582L; // Signal Transduction
        long time = System.currentTimeMillis();
        QACheckResult result = imbalanceResult(dbId, "Pathway");
        time = System.currentTimeMillis() - time;
        System.out.println("QA report time for Signal Transduction: " + time + " ms");
        System.out.println("Issue: " + result.getIssue());
        assertEquals("Reaction_DBID", result.getColumns()[0]);
        ReactionImbalanceReport report = checkEvent(dbId);
        int expectedRows = report.getReactions().stream().mapToInt(r -> r.getImbalances().size()).sum();
        assertEquals(expectedRows, result.getRows().size());
        assertTrue(result.getIssue().startsWith(report.getReactions().size() + " of " +
                                                report.getCheckedReactions() + " reactions imbalanced"));
        result.getRows().stream().limit(5).forEach(row -> System.out.println(String.join("\t", row)));
    }

    private QACheckResult imbalanceResult(Long dbId, String schemaClass) {
        SimpleInstance instance = new SimpleInstance();
        instance.setDbId(dbId);
        instance.setSchemaClassName(schemaClass);
        QAReport report = qaService.performQACheck(instance);
        return report.getQaResults().stream()
                .filter(r -> r.getCheckName().equals("Reaction Imbalance Check"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Reaction Imbalance Check result for " + dbId));
    }

    private void printReport(ReactionImbalanceReport report) {
        System.out.println(report.getEventDisplayName() + ": checked " + report.getCheckedReactions() +
                           ", imbalanced " + report.getReactions().size());
        report.getReactions().forEach(r -> printReaction(r));
    }

    private void printReaction(ReactionImbalance reaction) {
        System.out.println(reaction.getDbId() + "\t" + reaction.getDisplayName() + "\tcleavage: " +
                           reaction.isCleavage() + "\tlast author: " + reaction.getLastAuthor());
        for (ReferenceImbalance ref : reaction.getImbalances())
            System.out.println("\t" + ref.getDisplayName() + "\t" + ref.getInputCount() + " -> " + ref.getOutputCount());
    }

}
