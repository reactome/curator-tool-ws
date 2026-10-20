package org.reactome.curation.qa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.reactome.curation.qa.model.ReactionImbalance;
import org.reactome.curation.qa.model.ReferenceImbalance;
import org.reactome.server.graph.domain.model.Reaction;

/**
 * Check if a Reaction's inputs and outputs have the same proteins. See ReactionImbalanceHelper for details.
 * To check all Reactions in a Pathway, use QAService.checkReactionImbalance().
 */
public class ReactionImbalanceCheck extends QAChecker {

    public ReactionImbalanceCheck() {
    }

    @Override
    public String getCheckName() {
        return "Reaction Imbalance Check";
    }

    @Override
    public Collection<Class<?>> getTargetClasses() {
        // Reaction only, not other ReactionLikeEvents, the same as the desktop version
        return Collections.singleton(Reaction.class);
    }

    @Override
    protected QACheckResult _performQACheck(SimpleInstance instance) {
        ReactionImbalanceHelper helper = new ReactionImbalanceHelper(getNeoj4Client());
        List<ReactionImbalance> imbalances = helper.checkReactions(Collections.singletonList(instance.getDbId()));
        if (imbalances.isEmpty())
            return getEmptyResult();
        ReactionImbalance imbalance = imbalances.get(0);
        String[] colNames = { "Reference", "Identifier", "Input", "Output" };
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
        QACheckResult result = createResult(colNames, rows);
        if (imbalance.isCleavage())
            result.setIssue("Possible cleavage reaction");
        return result;
    }

}
