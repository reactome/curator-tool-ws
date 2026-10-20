package org.reactome.curation.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.reactome.curation.qa.model.ReactionImbalance;
import org.reactome.curation.qa.model.ReactionImbalanceReport;
import org.reactome.curation.qa.model.ReferenceImbalance;
import org.reactome.curation.service.CurationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ReactionImbalanceCheckTest {

    @Autowired
    private QAService qaService;
    @Autowired
    private CurationService curationService;

    // Note: This is needed.
    @Test
    void contextLoads() {
    }

    @Test
    public void testBalancedReaction() throws Exception {
        // Inputs and outputs have the same proteins, including an input with stoichiometry 4
        ReactionImbalanceReport report = qaService.checkReactionImbalance(5218891L);
        assertEquals(1, report.getCheckedReactions());
        assertTrue(report.getReactions().isEmpty());
    }

    @Test
    public void testCleavageReaction() throws Exception {
        // Pro-EGF is cleaved to form mature EGF: one EGF in each side so it is not imbalanced
        assertTrue(qaService.checkReactionImbalance(177946L).getReactions().isEmpty());
        // Autoproteolytic cleavage of Hh precursors: each of DHH, IHH and SHH goes from 1 to 2
        Long dbId = 5358340L;
        ReactionImbalanceReport report = qaService.checkReactionImbalance(dbId);
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
        ReactionImbalanceReport report = qaService.checkReactionImbalance(dbId);
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
        ReactionImbalanceReport report = qaService.checkReactionImbalance(162582L);
        time = System.currentTimeMillis() - time;
        System.out.println("Time: " + time + " ms");
        long cleavage = report.getReactions().stream().filter(ReactionImbalance::isCleavage).count();
        System.out.println(report.getEventDisplayName() + ": checked " + report.getCheckedReactions() +
                           ", imbalanced " + report.getReactions().size() + ", cleavage " + cleavage);
        report.getReactions().stream().limit(5).forEach(r -> printReaction(r));
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
