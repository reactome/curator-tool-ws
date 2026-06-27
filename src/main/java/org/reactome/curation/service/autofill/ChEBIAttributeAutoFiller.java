package org.reactome.curation.service.autofill;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.InstanceList;
import org.reactome.curation.model.ListOperand;
import org.reactome.curation.model.SimpleInstance;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills attributes for ReferenceMolecule instances backed by the EBI ChEBI ontology.
 */
@Component
public class ChEBIAttributeAutoFiller extends ExternalOntologyAutoFiller {
    private final String CHEBI = "CHEBI";

    public ChEBIAttributeAutoFiller() {
    }

    public void process(SimpleInstance instance) throws Exception {
        process(instance, CHEBI);
    }

    @Override
    protected void mapMetaToAttributes(SimpleInstance instance, String termId, String ontologyName) throws Exception {
        SimpleInstance dbRef = getReferenceDatabase(CHEBI);
        if (dbRef != null)
            instance.setAttribute(ReactomeJavaConstants.referenceDatabase, dbRef);
        Map<String, String> meta = olsUtil.getTermMetadata(termId, ontologyName);
        if (meta == null || meta.isEmpty())
            return;
        List<String> names = (List<String>) instance.getAttribute(ReactomeJavaConstants.name);
        if (names == null) {
            names = new ArrayList<>();
            instance.setAttribute(ReactomeJavaConstants.name, names);
        }
        List<String> synonyms = extractSynonym(meta);
        for (String synonym : synonyms) {
            if (!names.contains(synonym))
                names.add(synonym);
        }
        // Set formula
        String formula = meta.get("FORMULA_synonym");
        if (formula != null && !formula.isEmpty())
            instance.setAttribute(ReactomeJavaConstants.formula, formula);
    }

    @Override
    protected void mapCrossReference(SimpleInstance instance, String termId) {
        Map<String, String> xrefs = olsUtil.getTermXrefs(termId, CHEBI);
        if (xrefs == null || xrefs.isEmpty())
            return;
        // Only need to cross-reference to KEGG COMPOUND here. All other cross-references
        // will be handled during release.
        Pattern pattern = Pattern.compile("KEGG COMPOUND:(C(\\d){5})");
        List<SimpleInstance> crossRefs = new ArrayList<>();
        for (String value : xrefs.values()) {
            if (value == null || value.isEmpty())
                continue;
            Matcher matcher = pattern.matcher(value);
            if (matcher.find()) {
                String keggId = matcher.group(1);
                try {
                    SimpleInstance xrefInstance = getCompoundInstance(keggId);
                    if (xrefInstance != null)
                        crossRefs.add(xrefInstance);
                } catch (Exception e) {
                    // skip if lookup fails
                }
            }
        }
        if (!crossRefs.isEmpty())
            instance.setAttribute(ReactomeJavaConstants.crossReference, crossRefs);
    }

    private SimpleInstance getCompoundInstance(String id) throws Exception {
        String displayName = "COMPOUND:" + id;
        InstanceList list = curationRepository.listInstances(
                ReactomeJavaConstants.DatabaseIdentifier, 0, 10,
                Collections.singletonList("identifier"),
                Collections.singletonList("string"),
                Collections.singletonList(ListOperand.EQUAL),
                Collections.singletonList(id));
        if (list != null && !list.isEmpty()) {
            for (SimpleInstance inst : list.getInstances()) {
                if (displayName.equals(inst.getDisplayName()))
                    return inst;
            }
        }
        // Create a new shell instance if not found in the database
        SimpleInstance compound = new SimpleInstance();
        compound.setSchemaClassName(ReactomeJavaConstants.DatabaseIdentifier);
        compound.setDisplayName(displayName);
        compound.setAttribute(ReactomeJavaConstants.identifier, id);
        SimpleInstance refDb = getReferenceDatabase("COMPOUND");
        if (refDb != null)
            compound.setAttribute(ReactomeJavaConstants.referenceDatabase, refDb);
        return compound;
    }
}
