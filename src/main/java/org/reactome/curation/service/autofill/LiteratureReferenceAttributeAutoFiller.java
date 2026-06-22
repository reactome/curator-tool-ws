/*
 * Created on Aug 3, 2005
 *
 */
package org.reactome.curation.service.autofill;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gk.model.Person;
import org.gk.model.ReactomeJavaConstants;
import org.gk.model.Reference;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.input.SAXBuilder;
import org.jdom.output.XMLOutputter;
import org.reactome.curation.model.InstanceList;
import org.reactome.curation.model.SimpleInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * This class is ported from the curator tool java version directly.
 * See the original code at: https://github.com/reactome/CuratorTool/blob/master/src/org/gk/database/util/LiteratureReferenceAttributeAutoFiller.java.
 *
 * Includes merged functionality from PMIDXMLInfoFetcher2 for fetching PubMed article information.
 */
@Component
public class LiteratureReferenceAttributeAutoFiller extends AbstractAttributeAutoFiller{

    @Value("${pubmedUrl1}")
    private String pubmedUrl1;

    @Value("${pubmedUrl2}")
    private String pubmedUrl2;

    public LiteratureReferenceAttributeAutoFiller() {
    }

    public void process(SimpleInstance instance) throws Exception {
        Integer pmid = (Integer) instance.getAttribute("pubMedIdentifier");
        if (pmid == null)
            return; // Cannot do anything
        Reference ref = fetchInfo(Long.valueOf(pmid));
        if (ref == null)
            return;
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
                SimpleInstance authorInstance = queryPerson(person);
                authors.add(authorInstance);
            }
            instance.setAttribute(ReactomeJavaConstants.author, authors);
        }
    }
    
    /**
     * Query a SimpleInstance for a Person object. If it cannot be found, a new simple instance
     * will be created from scratch.
     * @param person the person to query for
     * @param autoCreatedInstances list to collect auto-created instances
     * @return a SimpleInstance representing the person
     * @throws Exception if query fails
     */
    private SimpleInstance queryPerson(Person person) throws Exception {
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
        return authorInstance;
    }

    public Reference fetchInfo(Long pmid) throws Exception {
        String url = pubmedUrl1 + pmid + pubmedUrl2;
        URL pubmed = new URL(url);
        URLConnection connection = pubmed.openConnection();
        InputStream is = connection.getInputStream();
        StringBuilder builder = new StringBuilder();
        InputStreamReader isr = new InputStreamReader(is);
        BufferedReader br = new BufferedReader(isr);
        String line = null;
        while ((line = br.readLine()) != null) {
            if (line.startsWith("<!DOCTYPE html"))
                continue; // This line should not be in XML, which is wrong!
            builder.append(line).append("\n");
        }
        br.close();
        isr.close();
        is.close();
        String text = builder.toString();
        // The following code should not be used. The escaped XML is well taken care of
        // by JDOM.
//        text = StringEscapeUtils.unescapeXml(text);
        StringReader sr = new StringReader(text);
        SAXBuilder saxBuilder = new SAXBuilder();
        Document document = saxBuilder.build(sr);
        // The root element is <pre> from URL.
        Element root = document.getRootElement();
        List<?> children = root.getChildren();
        for (Object obj : children) {
            Element elm = (Element) obj;
            // For journal article
            if (elm.getName().equals("PubmedArticle")) {
                elm = elm.getChild("MedlineCitation");
                if (elm != null) {
                    Element articleElm = elm.getChild("Article");
                    return parseArticle(articleElm);
                }
            }
            // For book chapter
            else if (elm.getName().equals("PubmedBookArticle")) {
                elm = elm.getChild("BookDocument");
                if (elm != null) {
                    return parseBookDocument(elm);
                }
            }
        }
        return null;
    }

    private Reference parseBookDocument(Element elm) {
        Reference reference = new Reference();
        List<?> children = elm.getChildren();
        for (Object obj : children) {
            Element child = (Element) obj;
            String name = child.getName();
            if (name.equals("ArticleTitle")) {
                String text = child.getTextNormalize();
                if (text != null && text.length() > 0)
                    reference.setTitle(text);
            }
            else if (name.equals("AuthorList")) {
                parseAuthorList(child, reference);
            }
            else if (name.equals("ContributionDate")) {
                Element yearElm = child.getChild("Year");
                if (yearElm != null) {
                    String text = yearElm.getTextNormalize();
                    if (text != null && text.length() > 0) {
                        try {
                            reference.setYear(Integer.parseInt(text));
                        }
                        catch(NumberFormatException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
        return reference;
    }

    private String parseTitle(Element titleElm) {
        // Need to handle case like this (PMID: 29146722):
        // <ArticleTitle>The<i>NOTCH4</i>-<i>HEY1</i>Pathway Induces Epithelial-Mesenchymal Transition in Head and Neck Squamous Cell Carcinoma.</ArticleTitle>
        String text = new XMLOutputter().outputString(titleElm);
        // Get rid of the element tag
        int index1 = text.indexOf(">");
        int index2 = text.lastIndexOf("<");
        text = text.substring(index1 + 1, index2).trim();
        return text;
    }

    private Reference parseArticle(Element elm) {
        Reference reference = new Reference();
        List<?> list = elm.getChildren();
        for (Object obj : list) {
            Element childElm = (Element) obj;
            String name = childElm.getName();
            if (name.equals("Journal")) {
                parseJournal(childElm, reference);
            }
            else if (name.equals("ArticleTitle")) {
//                String text = childElm.getTextNormalize();
                String text = parseTitle(childElm);
                if (text != null) {
                    if (text.endsWith("."))
                        text = text.substring(0, text.length() - 1); // Get rid the last dot
                    reference.setTitle(text);
                }
            }
            else if (name.equals("Pagination")) {
                String pages = childElm.getChildTextNormalize("MedlinePgn");
                if (pages != null && pages.length() > 0)
                    reference.setPage(pages);
            }
            else if (name.equals("AuthorList")) {
                parseAuthorList(childElm, reference);
            }
        }
        return reference;
    }

    private void parseAuthorList(Element elm, Reference reference) {
        List<?> list = elm.getChildren("Author");
        for (Object obj : list) {
            Element child = (Element) obj;
            Person person = createPerson(child);
            reference.addAuthor(person);
        }
    }

    private Person createPerson(Element author) {
        Person person = new Person();
        List<?> list = author.getChildren();
        for (Object obj : list) {
            Element elm = (Element) obj;
            String name = elm.getName();
            String text = elm.getTextNormalize();
            if (text.length() == 0)
                continue;
            if (name.equals("LastName")) {
                person.setLastName(text);
            }
            else if (name.equals("ForeName")) {
                person.setFirstName(text);
            }
            else if (name.equals("Initials")) {
                person.setInitial(text);
            }
            else if (name.equals("CollectiveName")) {
                //TODO: Need to introduce a new type of Person having a collective name.
                person.setLastName(text); // Temporarily put it as last name (e.g. PMID: 21794845).
            }
        }
//        // Make sure the first name is useful
//        if (person.getFirstName() != null) {
//            String firstName = person.getFirstName();
//            String[] tokens = firstName.split(" ");
//            // Want to have the first token only
//            if (tokens[0].length() > 1)
//                person.setFirstName(tokens[0]);
//            else
//                person.setFirstName(null); // Nothing interesting there
//        }
        return person;
    }

    private void parseJournal(Element elm, Reference reference) {
        List<?> list = elm.getChildren();
        for (Object obj : list) {
            Element childElm = (Element) obj;
            String name = childElm.getName();
            if (name.equals("ISOAbbreviation")) {
                reference.setJournal(childElm.getTextNormalize());
            }
            else if (name.equals("JournalIssue")) {
                reference.setVolume(childElm.getChildTextNormalize("Volume"));
                Element dateElm = childElm.getChild("PubDate");
                if (dateElm != null) {
                    String year = dateElm.getChildTextNormalize("Year");
                    if (year != null && year.length() > 0)
                        reference.setYear(Integer.parseInt(year));
                    else { // For some old medline based entries (e.g. PMID: 7997270), the format
                        // is like this: <MedlineDate>1994 Dec 22-29</MedlineDate>. However,
                        // this may not be fixed and should test more.
                        String medlineDate = dateElm.getChildTextNormalize("MedlineDate");
                        if (medlineDate != null && medlineDate.length() > 0) {
                            String tmp = medlineDate.split(" ")[0].trim();
                            if (tmp.matches("(\\d){4}")) { // If it is matched as four digits
                                reference.setYear(Integer.parseInt(tmp));
                            }
                        }
                    }
                }
            }
        }
    }

//    @Test
//    public void testFetchInfo() throws Exception {
//        Long pmid = 23356980L;
////        pmid = 29146722L; // A title has "<i>" in
//        Reference reference = fetchInfo(pmid);
//        System.out.println("\"" + reference.getTitle() + "\" in \"" + reference.getJournal() + "\"");
//    }
 
}
