/*
 * Created on Aug 4, 2005
 *
 */
package org.reactome.curation.service.autofill;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.gk.model.ReactomeJavaConstants;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.Text;
import org.jdom.input.SAXBuilder;
import org.jdom.xpath.XPath;
import org.reactome.curation.model.InstanceList;
import org.reactome.curation.model.SimpleInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

/**
 * This class is used to fetch detailed information from UniProt database by specifying
 * a database identity. The XML format from the web site is downloaded and parsed to
 * extract information for attributes in ReferencePeptideSequence.
 *
 * @author guanming
 */
@org.springframework.stereotype.Component
public class ReferenceGeneProductAutoFiller extends AbstractAttributeAutoFiller {

    private static final Logger logger = LoggerFactory.getLogger(ReferenceGeneProductAutoFiller.class);

    @Value("${uniprotDownloadUrl:https://www.uniprot.org/uniprot/}")
    private String uniprotDownloadUrl;

    @Value("${uniprotXmlFormat:.xml}")
    private String uniprotXmlFormat;

    @Value("${uniprotFlatFormat:.txt}")
    private String uniprotFlatFormat;

    public ReferenceGeneProductAutoFiller() {
    }

    /**
     * Fetch detailed information from UniProt for the given instance using its identifier attribute.
     *
     * @param instance Instance for which to fetch UniProt information
     * @throws Exception if the fetch or parse fails
     */
    @Override
    public void process(@NonNull SimpleInstance instance) throws Exception {
        String identifier = (String) instance.getAttribute(ReactomeJavaConstants.identifier);
        if (identifier == null) {
            return;
        }
        // Coarse check: 6–10 upper-case alphanumeric characters
        if (!identifier.matches("([A-Z]|\\d){6,10}")) {
            logger.warn("Invalid UniProt identifier: {}", identifier);
            return;
        }
        String url = uniprotDownloadUrl + identifier + uniprotXmlFormat;
        SAXBuilder builder = new SAXBuilder();
        Document document = builder.build(new URL(url));
        Element rootElm = document.getRootElement();
        // Use entry only
        Element entry = rootElm.getChild("entry", rootElm.getNamespace());

        String path = "*[local-name()='name']/text()";
        Text nameNode = (Text) XPath.selectSingleNode(entry, path);
        if (nameNode != null)
            addToListAttribute(instance, "secondaryIdentifier", nameNode.getText());

        path = "*[local-name()='protein']/*[local-name()='recommendedName']/*[local-name()='fullName']/text()";
        List<?> proteinNameNodes = XPath.selectNodes(entry, path);
        for (Object obj : proteinNameNodes) {
            Text text = (Text) obj;
            addToListAttribute(instance, "name", text.getText());
        }

        path = "*[local-name()='gene']/*[local-name()='name']/text()";
        List<?> geneNameNodes = XPath.selectNodes(entry, path);
        for (Object obj : geneNameNodes) {
            Text text = (Text) obj;
            addToListAttribute(instance, "geneName", text.getText());
        }

        path = "*[local-name()='keyword']/text()";
        List<?> keywordNodes = XPath.selectNodes(entry, path);
        for (Object obj : keywordNodes) {
            Text text = (Text) obj;
            addToListAttribute(instance, "keyword", text.getText());
        }

        path = "*[local-name()='accession']/text()";
        List<?> accessionNodes = XPath.selectNodes(entry, path);
        for (Object obj : accessionNodes) {
            Text text = (Text) obj;
            if (!text.getText().equals(identifier))
                addToListAttribute(instance, "secondaryIdentifier", text.getText());
        }

        // Get the species
        path = "*[local-name()='organism']/*[local-name()='name'][@type='scientific']/text()";
        Text speciesNode = (Text) XPath.selectSingleNode(entry, path);
        // In some rare cases, full name might be used for organism (e.g. Q8LI45).
        if (speciesNode == null) {
            path = "*[local-name()='organism']/*[local-name()='name'][@type='full']/text()";
            speciesNode = (Text) XPath.selectSingleNode(entry, path);
        }
        if (speciesNode != null) {
            SimpleInstance species = getSpecies(speciesNode.getText());
            if (species != null)
                instance.setAttribute("species", species);
        }

        processComment(identifier, instance);

        // Add the database
        SimpleInstance uniProt = getUniProtInstance();
        if (uniProt != null)
            instance.setAttribute("referenceDatabase", uniProt);
        // Get the comments for alternative products
        path = "*[local-name()='comment'][@type='alternative products']//*[local-name()='isoform']/*[local-name()='id']/text()";

        List isoformIds = XPath.selectNodes(entry, path);
        // Only need for more than on isoform
        if (isoformIds != null && isoformIds.size() > 1) {
            generateReferenceIsoforms(isoformIds, instance);
        }
    }

    private void generateReferenceIsoforms(List isoformIds,
                                           SimpleInstance refGeneProduct) throws Exception {
        // Create instances for other alternative
        List<SimpleInstance> isoforms = new ArrayList<>();
        for (int i = 0; i < isoformIds.size(); i++) {
            String isoformID = ((Text) isoformIds.get(i)).getText();
            // Make sure isoformId and identifier should be the same in a RefereneIsoform instance
            // Make sure this should be handled first to ensure the correct parent and child relationship
            int index = isoformID.indexOf("-");
            String childId = isoformID.substring(0, index);
            String parentId = (String) refGeneProduct.getAttribute(ReactomeJavaConstants.identifier);
            SimpleInstance isoform = refGeneProduct.cloneInstance();
            isoform.setSchemaClassName(ReactomeJavaConstants.ReferenceIsoform);
            isoform.setAttribute(ReactomeJavaConstants.variantIdentifier, isoformID);
            isoform.setAttribute(ReactomeJavaConstants.isoformParent, refGeneProduct);
            // Leave display name and dbId empty for the front-end app to handle
            // But we need to attach it to the parent so that the front-end can get it
            isoforms.add(isoform);
        }
        if (isoforms.size() > 0) {
            refGeneProduct.setAttribute("isoforms", isoforms);
        }
    }

    /**
     * Add a value to a list-valued attribute of a SimpleInstance.
     * Creates the list if it does not yet exist.
     */
    @SuppressWarnings("unchecked")
    private void addToListAttribute(SimpleInstance instance, String attrName, Object value) {
        List<Object> list = (List<Object>) instance.getAttribute(attrName);
        if (list == null) {
            list = new ArrayList<>();
            instance.setAttribute(attrName, list);
        }
        list.add(value);
    }

    /**
     * Fetch chain start/end coordinates for an identifier from the UniProt flat file.
     *
     * @param identifier UniProt identifier
     * @return two-element int array [start, end], or null if not found
     * @throws Exception if the URL cannot be opened or read
     */
    public int[] fetchCoordinates(String identifier) throws Exception {
        String urlName = uniprotDownloadUrl + identifier + uniprotFlatFormat;
        URL url = new URL(urlName);
        InputStream is = url.openStream();
        InputStreamReader isr = new InputStreamReader(is);
        BufferedReader reader = new BufferedReader(isr);
        String line;
        String chainLine = null;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("FT   CHAIN")) {
                chainLine = line;
                break;
            }
        }
        reader.close();
        isr.close();
        is.close();
        if (chainLine == null)
            return null;
        int[] coordinates = new int[2];
        coordinates[0] = coordinates[1] = -1;
        Pattern pattern = Pattern.compile("(\\d)+");
        Matcher matcher = pattern.matcher(chainLine);
        int index = 0;
        int start = 0;
        while (matcher.find(start)) {
            coordinates[index] = Integer.parseInt(matcher.group());
            start = matcher.end();
            index++;
            if (index > 1)
                break;
        }
        return coordinates;
    }

    private SimpleInstance getUniProtInstance() throws Exception {
        return getReferenceDatabase("UniProt");
    }

    private SimpleInstance getSpecies(String speciesName) throws Exception {
        // Strip parenthetical qualifiers, e.g. "Influenza A virus (strain ...)"
        int index = speciesName.indexOf("(");
        if (index > 0)
            speciesName = speciesName.substring(0, index).trim();
        InstanceList list = curationRepository.listInstances("Species", 0, 10, speciesName);
        if (list != null && !list.isEmpty())
            return list.getInstances().get(0);
        return null;
    }

    /**
     * Parse CC comment lines from the UniProt flat file and add them to the instance.
     *
     * @param identifier UniProt identifier used to build the flat-file URL
     * @param instance   the SimpleInstance to populate with comments
     * @throws Exception if reading from UniProt fails
     */
    private void processComment(String identifier, SimpleInstance instance) throws Exception {
        URL url = new URL(uniprotDownloadUrl + identifier + uniprotFlatFormat);
        InputStream input = url.openStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input));
        String line;
        List<String> commentLines = new ArrayList<>();
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("CC")) {
                line = line.substring(2).trim();
                if (line.startsWith("-------"))
                    break;
                commentLines.add(line);
            }
        }
        reader.close();
        input.close();
        // Reassemble multi-line comment blocks
        List<String> comments = new ArrayList<>();
        StringBuilder buffer = null;
        for (String cLine : commentLines) {
            if (cLine.startsWith("-!-")) {
                if (buffer != null)
                    comments.add(buffer.toString());
                buffer = new StringBuilder();
                buffer.append(cLine.substring(4));
            } else if (buffer != null) {
                buffer.append(" ").append(cLine);
            }
        }
        if (buffer != null)
            comments.add(buffer.toString());
        for (String comment : comments) {
            addToListAttribute(instance, "comment", comment);
        }
    }
}
