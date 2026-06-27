package org.reactome.curation.service.autofill;

import java.util.*;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;
import org.springframework.beans.factory.annotation.Autowired;


/**
 * This class is used to fetch attributes for PsiMOD instances directly from the EBI ontology web service.
 * @author wgm
 *
 */
@org.springframework.stereotype.Component
public class ExternalOntologyAutoFiller extends AbstractAttributeAutoFiller {

    @Autowired
    protected OLSUtil olsUtil;
    // Configuration to map a class to its Ontology
    // We may externalize this configuration to a properties file in the future.
    private Map<String, String> clsToOntologyMap;

    public ExternalOntologyAutoFiller() {
        clsToOntologyMap = new HashMap<>();
        clsToOntologyMap.put("PsiMod", "MOD");
        clsToOntologyMap.put("Anatomy", "UBERON");
        clsToOntologyMap.put("CellType", "CL");
        clsToOntologyMap.put("Disease", "DOID");
        clsToOntologyMap.put("EvidenceType", "ECO");
        clsToOntologyMap.put("SequenceOntology", "SO");
    }

    protected Object getRequiredAttribute(SimpleInstance instance) {
        return instance.getAttribute(ReactomeJavaConstants.identifier);
    }

    public void process(SimpleInstance instance) throws Exception {
        String className = instance.getSchemaClassName();
        String ontologyName = clsToOntologyMap.get(className);
        if (ontologyName == null) {
            return;
        }
        process(instance, ontologyName);
    }

    protected void process(SimpleInstance instance, String ontologyName) throws Exception {
        String identifier = (String) getRequiredAttribute(instance);
        if (identifier == null || identifier.isBlank()) {
            return;
        }
        if (identifier.startsWith(ontologyName)) {
            int index = identifier.indexOf(":");
            if (index >= 0) {
                identifier = identifier.substring(index + 1).trim();
                instance.setAttribute(getIdentifierAttributeName(), identifier);
            }
        }
        String termId = ontologyName + ":" + identifier;
        String term = olsUtil.getTermById(termId, ontologyName);
        instance.setDisplayName(term);
        // Name should be a list
        List<String> names = new ArrayList<>();
        names.add(term);
        instance.setAttribute(ReactomeJavaConstants.name, names);
        mapMetaToAttributes(instance, termId, ontologyName);
        mapCrossReference(instance, termId);
    }

    /**
     * Get the name used for the identifier attribute. This may be different for different class.
     * @return
     */
    protected String getIdentifierAttributeName() {
        return ReactomeJavaConstants.identifier;
    }

    /**
     * Default implementation used as a template.
     */
    protected void mapCrossReference(SimpleInstance instance,
                                     String termId) {
    }


    protected void mapMetaToAttributes(SimpleInstance instance,
                                       String termId,
                                       String ontologyName) throws Exception {
        Map<String, String> meta = olsUtil.getTermMetadata(termId, ontologyName);
        if (meta == null || meta.isEmpty())
            return;
        String definition = meta.get("definition");
        if (definition != null && !definition.isBlank()) {
            instance.setAttribute(ReactomeJavaConstants.definition, definition);
        }
        // Call a method since this method is used in other places too.
        List<String> synonyms = extractSynonym(meta);
        if (!synonyms.isEmpty())
            instance.setAttribute(ReactomeJavaConstants.synonym, synonyms);
        instance.setAttribute(ReactomeJavaConstants.referenceDatabase, getReferenceDatabase(ontologyName));
    }

    protected List<String> extractSynonym(Map<String, String> meta) {
        // Updated on June 27, 2026 based on the returned values
        // There is a term for "synonyms"
        String synonyms = meta.get("synonyms");
        if (synonyms == null)
            return Collections.emptyList();
        return Arrays.asList(synonyms.split(",\\s*"));
    }

}