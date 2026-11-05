package org.reactome.curation.qa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.reactome.curation.qa.model.ReactionImbalance;
import org.reactome.curation.qa.model.ReactionImbalanceReport;
import org.reactome.curation.qa.model.ReferenceImbalance;
import org.reactome.server.graph.domain.model.Pathway;
import org.reactome.server.graph.domain.model.Reaction;

/**
 * Check if a Reaction's inputs and outputs have the same proteins. See ReactionImbalanceHelper for details.
 * For a Pathway, all Reactions it contains, including those in sub-pathways, are checked.
 * The results are returned in the QA report (POST qaReport).
 */
public class ReactionImbalanceCheck extends QAChecker {

    // Columns for a single Reaction
    private static final String[] REACTION_COLUMNS = { "Reference", "Identifier", "Input", "Output" };
    // Columns for a Pathway: one row per imbalanced protein of each imbalanced Reaction
    private static final String[] PATHWAY_COLUMNS = {
            "Reaction_DBID", "Reaction", "Reference", "Identifier", "Input", "Output", "Possible_Cleavage", "Last_Author"
    };

    public ReactionImbalanceCheck() {
    }

    @Override
    public String getCheckName() {
        return "Reaction Imbalance Check";
    }

    @Override
    public Collection<Class<?>> getTargetClasses() {
        // Reaction only, not other ReactionLikeEvents, the same as the desktop version.
        // Pathways are checked through the Reactions they contain.
        return Stream.of(Reaction.class, Pathway.class).collect(Collectors.toList());
    }

    @Override
    protected QACheckResult _performQACheck(SimpleInstance instance) {
        ReactionImbalanceHelper helper = new ReactionImbalanceHelper(getNeoj4Client());
        if (Pathway.class.isAssignableFrom(instance.getGraphModelClass()))
            return checkPathway(helper, instance);
        return checkReaction(helper, instance);
    }

    private QACheckResult checkReaction(ReactionImbalanceHelper helper, SimpleInstance instance) {
        List<ReactionImbalance> imbalances = helper.checkReactions(Collections.singletonList(instance.getDbId()));
        if (imbalances.isEmpty())
            return getEmptyResult();
        ReactionImbalance imbalance = imbalances.get(0);
        List<String[]> rows = new ArrayList<>();
        for (ReferenceImbalance ref : imbalance.getImbalances()) {
            String[] row = {
                    ref.getDisplayName(),
                    ref.getIdentifier(),
                    ref.getInputCount() + "",
                    ref.getOutputCount() + ""
            };
            rows.add(row);
        }
        QACheckResult result = createResult(REACTION_COLUMNS, rows);
        if (imbalance.isCleavage())
            result.setIssue("Possible cleavage reaction");
        return result;
    }

    private QACheckResult checkPathway(ReactionImbalanceHelper helper, SimpleInstance instance) {
        ReactionImbalanceReport report = helper.checkEvent(instance.getDbId());
        List<ReactionImbalance> imbalances = report.getReactions();
        if (imbalances == null || imbalances.isEmpty())
            return getEmptyResult();
        List<String[]> rows = new ArrayList<>();
        int cleavages = 0;
        for (ReactionImbalance reaction : imbalances) {
            if (reaction.isCleavage())
                cleavages++;
            for (ReferenceImbalance ref : reaction.getImbalances()) {
                String[] row = {
                        reaction.getDbId() + "",
                        reaction.getDisplayName(),
                        ref.getDisplayName(),
                        ref.getIdentifier(),
                        ref.getInputCount() + "",
                        ref.getOutputCount() + "",
                        reaction.isCleavage() ? "Yes" : "No",
                        reaction.getLastAuthor() == null ? "" : reaction.getLastAuthor()
                };
                rows.add(row);
            }
        }
        QACheckResult result = createResult(PATHWAY_COLUMNS, rows);
        String issue = imbalances.size() + " of " + report.getCheckedReactions() + " reactions imbalanced";
        if (cleavages > 0)
            issue += " (" + cleavages + " possible cleavage" + (cleavages > 1 ? "s" : "") + ")";
        result.setIssue(issue);
        return result;
    }

}
