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
    // Used as the default since MOD is the original supclass.
    protected String ontologyName = "MOD";

    public ExternalOntologyAutoFiller() {
    }

    protected Object getRequiredAttribute(SimpleInstance instance) {
        return instance.getAttribute(ReactomeJavaConstants.identifier);
    }

    public void process(SimpleInstance instance) throws Exception {
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
//        if (term == null || term.isBlank()) {
//            return;
//        }
        instance.setDisplayName(term);
        // Name should be a list
        List<String> names = new ArrayList<>();
        names.add(term);
        instance.setAttribute(ReactomeJavaConstants.name, names);
        mapMetaToAttributes(instance, termId);
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
                                       String termId) throws Exception {
        Map<String, String> meta = olsUtil.getTermMetadata(termId, ontologyName);
        if (meta == null || meta.isEmpty())
            return;
        for (String key : meta.keySet()) {
            String value = meta.get(key);
            if (key == null)
                continue; // Sometime there is a null as a key, which may not be correct!
//            System.out.println(key + ": " + value);
            if (key.equals("definition")) {
                instance.setAttribute(ReactomeJavaConstants.definition, value);
            }
        }
        List<String> synonyms = extractSynonym(meta);
        if (!synonyms.isEmpty())
            instance.setAttribute(ReactomeJavaConstants.synonym, synonyms);
        instance.setAttribute(ReactomeJavaConstants.referenceDatabase, getReferenceDatabase());
    }

    protected List<String> extractSynonym(Map<String, String> meta) {
        // Updated on June 27, 2026 based on the returned values
        // There is a term for "synonyms"
        String synonyms = meta.get("synonyms");
        if (synonyms == null)
            return Collections.emptyList();
        return Arrays.asList(synonyms.split(",\\s*"));
    }

    private SimpleInstance getReferenceDatabase() throws Exception {
        SimpleInstance referenceDatabase = getReferenceDatabase(ontologyName);
        if (referenceDatabase != null)
            return referenceDatabase;
        SimpleInstance referenceDb = new SimpleInstance();
        referenceDb.setSchemaClassName(ReactomeJavaConstants.ReferenceDatabase);
        referenceDb.setDisplayName(ontologyName);
        referenceDb.setAttribute(ReactomeJavaConstants.name, ontologyName);
        return referenceDb;
    }

}