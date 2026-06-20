/*
 * Created on Aug 3, 2005
 *
 */
package org.reactome.curation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gk.model.Person;
import org.gk.model.ReactomeJavaConstants;
import org.gk.model.Reference;
import org.reactome.curation.model.InstanceList;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.repository.CurationRepository;
import org.reactome.server.graph.repository.AdvancedDatabaseObjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * This class is ported from the curator tool java version directly. See the original code
 * at: https://github.com/reactome/CuratorTool/blob/master/src/org/gk/database/util/LiteratureReferenceAttributeAutoFiller.java.
 */
@Component
public class LiteratureReferenceAttributeAutoFiller {
    
    @Autowired
    private PMIDXMLInfoFetcher2 fetcher;
    @Autowired
    private CurationRepository curationRepository;
    @Autowired
    private AdvancedDatabaseObjectRepository objectRepository;
    
    public LiteratureReferenceAttributeAutoFiller() {
    }

    public void process(SimpleInstance instance) throws Exception {
        Integer pmid = (Integer) instance.getAttribute("pubMedIdentifier");
        if (pmid == null)
            return; // Cannot do anything
        Reference ref = fetcher.fetchInfo(Long.valueOf(pmid));
        if (ref == null)
            return;
        List<SimpleInstance> autoCreatedInstances = new ArrayList<>();
        instance.setAttribute("title", ref.getTitle());
        // Get the digital from string
        String vol = ref.getVolume();
        if (vol != null) {
            Pattern pattern = Pattern.compile("\\d+");
            Matcher matcher = pattern.matcher(vol);
            if (matcher.find()) {
                String sub = matcher.group();
                instance.setAttribute("volume", Integer.valueOf(sub));
            }
        }
        instance.setAttribute("year", Integer.valueOf(ref.getYear()));
        instance.setAttribute("pages", ref.getPage());
        instance.setAttribute("journal", ref.getJournal());
        // Have to use Instances for authors
        @SuppressWarnings("unchecked")
        List<SimpleInstance> authors = (List<SimpleInstance>) instance.getAttribute("author");
        if (authors != null)
            authors.clear();
        else
            authors = new ArrayList<>();
        List<Person> persons = ref.getAuthors();
        if (persons != null && persons.size() > 0) {
            for (Person person : persons) {
                SimpleInstance authorInstance = queryPerson(person, autoCreatedInstances);
                authors.add(authorInstance);
            }
            instance.setAttribute(ReactomeJavaConstants.author, authors);
        }
    }
    
    /**
     * Query a SimpleInstance for a Person object. If it cannot be found, a new simple instance
     * will be created from scratch.
     * @param adaptor
     * @param person
     * @param autoCreatedInstances
     * @return
     * @throws Exception
     */
    private SimpleInstance queryPerson(Person person,
                                       List<SimpleInstance> autoCreatedInstances) throws Exception {
        // For the time being, we do a display name based search. But the final logic should be based on 
        // the original Java desktop version implementation (see the code there).
        String displayName = person.getLastName() == null ? "" : person.getLastName();
        if (person.getFirstName() != null)
            displayName += ", " + person.getFirstName();
        else if (person.getInitial() != null)
            displayName += ", " + person.getInitial();
        if (displayName != null && displayName.trim().length() > 0) {
            InstanceList personInsts = curationRepository.listInstances(ReactomeJavaConstants.Person,
                    0, 
                    100, // TODO: This is arbitrary and needs to be updated. 
                    displayName);
            if (personInsts != null && !personInsts.isEmpty()) {
                // Make sure all three matched
                for (SimpleInstance inst : personInsts.getInstances()) {
                    org.reactome.server.graph.domain.model.Person dbInst = (org.reactome.server.graph.domain.model.Person) objectRepository.findById(inst.getDbId(), 1);
                    if (dbInst != null) {
                        String dbFirstName = dbInst.getFirstname() == null ? "" : dbInst.getFirstname();
                        String firstName = person.getFirstName() == null ? "" : person.getFirstName();
                        String dbLastName = dbInst.getSurname() == null ? "" : dbInst.getSurname();
                        String lastName = person.getLastName() == null ? "" : person.getLastName();
                        String dbInitial = dbInst.getInitial() == null ? "" : dbInst.getInitial();
                        String initial = person.getInitial() == null ? "" : person.getInitial();
                        if (dbFirstName.equals(firstName) &&
                            dbLastName.equals(lastName) &&
                            dbInitial.equals(initial))
                            return inst;
                    }
                }
            }
        }
        SimpleInstance authorInstance = new SimpleInstance();
        authorInstance.setSchemaClassName(ReactomeJavaConstants.Person);
        authorInstance.setAttribute(ReactomeJavaConstants.surname,
                                    person.getLastName());
        authorInstance.setAttribute(ReactomeJavaConstants.initial,
                                    person.getInitial());
        authorInstance.setAttribute(ReactomeJavaConstants.firstname,
                                    person.getFirstName());
        // Leave display name and dbId empty and have the front-end to handle these two slots.
        if (autoCreatedInstances != null)
            autoCreatedInstances.add(authorInstance);
        return authorInstance;
    }
 
}
