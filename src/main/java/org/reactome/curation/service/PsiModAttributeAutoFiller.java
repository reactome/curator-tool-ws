package org.reactome.curation.service;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.util.PsiModOlsUtil;


/**
 * This class is used to fetch attributes for PsiMOD instances directly from the EBI ontology web service.
 * @author wgm
 *
 */
@org.springframework.stereotype.Component
public class PsiModAttributeAutoFiller {

    protected String ONTOLOGY_NAME = "MOD";
    protected String displayOntologyName = "PsiMod";

    public PsiModAttributeAutoFiller() {

    }

    public void setOntologyName(String name) {
        this.ONTOLOGY_NAME = name;
    }

    public void setDisplayOntologyName(String name) {
        this.displayOntologyName = name;
    }


    protected Object getRequiredAttribute(SimpleInstance instance) {
        return instance.getAttribute(ReactomeJavaConstants.identifier);
    }

    public void process(SimpleInstance instance) throws Exception {
        String identifier = (String) getRequiredAttribute(instance);
        if (identifier == null || identifier.isBlank()) {
            return;
        }
        if (identifier.startsWith(ONTOLOGY_NAME)) {
            int index = identifier.indexOf(":");
            if (index >= 0) {
                identifier = identifier.substring(index + 1).trim();
                instance.setAttribute(getIdentifierAttributeName(), identifier);
            }
        }
        String termId = ONTOLOGY_NAME + ":" + identifier;
        String term = PsiModOlsUtil.getTermById(termId,ONTOLOGY_NAME);
//        if (term == null || term.isBlank()) {
//            return;
//        }
        instance.setDisplayName(term);
        instance.setAttribute(ReactomeJavaConstants.name, term);
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
        Map<String, String> meta = PsiModOlsUtil.getTermMetadata(termId, ONTOLOGY_NAME);
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
        instance.setAttribute(ReactomeJavaConstants.referenceDatabase, getPsiModInstance());
    }

    protected List<String> extractSynonym(Map<String, String> meta) {
        List<String> synonyms = new ArrayList<>();
        for (String key : meta.keySet()) {
            String value = meta.get(key);
            if (key == null)
                continue; // Sometime there is a null as a key, which may not be correct!
            if (key.endsWith("_synonym")) { // Based on the format used in the web service. I guess this may not be true in the future.
                synonyms.add(value);
            }
        }
        return synonyms;
    }

    private SimpleInstance getPsiModInstance() {
        SimpleInstance referenceDb = new SimpleInstance();
        referenceDb.setSchemaClassName(ReactomeJavaConstants.ReferenceDatabase);
        referenceDb.setDisplayName(displayOntologyName);
        referenceDb.setAttribute(ReactomeJavaConstants.name, displayOntologyName);
        return referenceDb;
    }

}