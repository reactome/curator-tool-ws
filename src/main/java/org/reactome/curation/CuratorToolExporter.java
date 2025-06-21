package org.reactome.curation;

import java.io.File;
import java.io.FileInputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.Schema;
import org.gk.schema.SchemaAttribute;
import org.gk.schema.SchemaClass;
import org.junit.Test;
import org.reactome.curation.model.CurationAttribute;
import org.reactome.curation.model.SimpleSchemaClass;
import org.reactome.server.graph.domain.model.TopLevelPathway;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * This class is used to dump some related information from the Java standalone application, e.g. some attribute
 * related information (e.g. categories).
 * @author wug
 *
 */
public class CuratorToolExporter {
    private final static Logger logger = LoggerFactory.getLogger(CuratorToolExporter.class);

    public CuratorToolExporter() {
    }

    public static void main(String[] args) {
        CuratorToolExporter exporter = new CuratorToolExporter();
        String fileName = "src/main/resources/curation_schema_attributes.json";
        try {
            exporter.dumpAttributes(fileName);
        }
        catch(Exception e) {
            logger.error("Error in CuratorToolExporter.main(): " + e, e);
        }
    }
    
    /**
     * Dump the data model classes in a hierarchy into a JSON text file for the web front.
     * The data model right now is pulled out from the graph data model classes directly.
     * This may be changed in the future to use an external configuration file (e.g. A 
     * YAML or LinkML file).
     * To grep the graph model classes, the refelections package is used.
     * Interaction is removed manually in the final json text.
     * @param fileName
     * @throws Exception
     */
    @Test
    public void dumpSchemaClassTree() throws Exception {
        String packageName = "org.reactome.server.graph.domain.model";
        Class<?> root = Class.forName(packageName + ".DatabaseObject");
        Reflections reflections = new Reflections(packageName, new SubTypesScanner());
        Set<Class<?>> graphClasses = reflections.getSubTypesOf(root).stream().collect(Collectors.toSet());
        graphClasses.add(root);
        System.out.println("Total graph classes: " + graphClasses.size());
        Map<String, SimpleSchemaClass> name2simpleClass = new HashMap<>();
        for (Class<?> graphClass : graphClasses) {
            // Remove any class that has been deprecated (only two, Ontology and RegulationType)
            boolean isDeprecated = false;
            Annotation[] annotations = graphClass.getAnnotations();
            for (Annotation annotation : annotations) {
                if (annotation instanceof java.lang.Deprecated) {
                    isDeprecated = true;
                    break;
                }
            }
            if (isDeprecated)
                continue;
            // This class should not be exported
            if (graphClass.getSimpleName().equals(ReactomeJavaConstants.DrugType))
                continue;
            SimpleSchemaClass simpleClass = new SimpleSchemaClass();
            simpleClass.setName(graphClass.getSimpleName());
            simpleClass.setAbstract(Modifier.isAbstract(graphClass.getModifiers()));
            name2simpleClass.put(graphClass.getSimpleName(), simpleClass);
        }
        // Now it is time to figure out parent to use built-in Java reflection
        for (Class<?> graphClass : graphClasses) {
            // This should be the root, don't check it
            if (graphClass == root)
                continue;
            // Escaped
            if (!name2simpleClass.containsKey(graphClass.getSimpleName()))
                continue;
            Class<?> superClass = graphClass.getSuperclass();
            SimpleSchemaClass simpleSuperClass = name2simpleClass.get(superClass.getSimpleName());
            SimpleSchemaClass simpleClass = name2simpleClass.get(graphClass.getSimpleName());
            simpleClass.setSuperClass(simpleSuperClass);
        }
        // It is much easier to have relationships from top to bottom to build a tree
        // The following is to convert bottom->top to top->bottom
        for (SimpleSchemaClass simpleClass : name2simpleClass.values()) {
            if (simpleClass.getSuperClass() == null)
                continue;
            SimpleSchemaClass superSimpleClass = simpleClass.getSuperClass();
            List<SimpleSchemaClass> children = superSimpleClass.getChildren();
            if (children == null) {
                children = new ArrayList<>();
                superSimpleClass.setChildren(children);
            }
            children.add(simpleClass);
        }
        // Since we don't want the top->bottom relationship, do some cleanup here 
        // also sort the children
        for (SimpleSchemaClass simpleClass : name2simpleClass.values()) {
            System.out.println(simpleClass.getName());
            simpleClass.setSuperClass(null);
            List<SimpleSchemaClass> children = simpleClass.getChildren();
            if (children == null)
                continue;
            children.sort((c1, c2) -> c1.getName().compareTo(c2.getName()));
        }
        // Get the root and then dump
        SimpleSchemaClass simpleRoot = name2simpleClass.get(root.getSimpleName());
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        String text = mapper.writeValueAsString(simpleRoot);
        System.out.println(text);
        // Dump out to a file
        String fileName = "src/main/resources/schema_classes_tree.json";
        mapper.writeValue(new File(fileName), simpleRoot);
    }
    
    /**
     * Dump curation specific attributes. They are all flattened without hierarchical relationships for easy
     * handling.
     */
    @SuppressWarnings("unchecked")
    public void dumpAttributes(String fileName) throws Exception {
        Properties prop = new Properties();
        String configFileName = "src/main/resources/services.properties";
        File configFile = new File(configFileName);
        prop.load(new FileInputStream(configFile));
        MySQLAdaptor dba = new MySQLAdaptor(prop.getProperty("mysql.host"),
                                            prop.getProperty("mysql.database"),
                                            prop.getProperty("mysql.user"),
                                            prop.getProperty("mysql.password"),
                                            Integer.parseInt(prop.getProperty("mysql.port")));
        Map<String, List<CurationAttribute>> clsName2attributes = new HashMap<>();
        Schema schema = dba.fetchSchema();
        Collection<SchemaClass> classes = schema.getClasses();
        logger.info("Total classes: " + classes.size());
        Map<String, String> old2new = mapAttNames();
        for (SchemaClass cls : classes) {
            Collection<SchemaAttribute> attributes = cls.getAttributes();
            List<CurationAttribute> curationAttributes = new ArrayList<>();
            for (SchemaAttribute att : attributes) {
                CurationAttribute curationAtt = new CurationAttribute();
                String attName = att.getName();
                if (old2new.keySet().contains(attName))
                    attName = old2new.get(attName);
                else if ((cls.getName().startsWith("GO_") || cls.getName().equals(ReactomeJavaConstants.Compartment)) 
                        && attName.equals(ReactomeJavaConstants.accession)) {
                    // This is a special case for Interaction classes
                    attName = "identifier";
                }
                else if (attName.equals(ReactomeJavaConstants.referenceEntity)) {
                    // This is a special case for ReferenceEntity
                    attName = "referenceEntityList";
                }
                curationAtt.setName(attName);
                curationAtt.setCategory(CurationAttribute.Category.getCategory(att.getCategory()));
                curationAtt.setDefiningType(CurationAttribute.DefiningType.getDefiningType(att.getDefiningType()));
                curationAttributes.add(curationAtt);
                // Need to duplicate the modified to the modfiedList
                if (att.getName().equals(ReactomeJavaConstants.modified)) {
                    CurationAttribute modifiedAtt = new CurationAttribute();
                    modifiedAtt.setName("modifiedList");
                    modifiedAtt.setCategory(curationAtt.getCategory());
                    modifiedAtt.setDefiningType(curationAtt.getDefiningType());
                    curationAttributes.add(modifiedAtt);
                }          
                else if (att.getName().equals(ReactomeJavaConstants.stableIdentifier)) { // Duplicated
                    CurationAttribute stIdAtt = new CurationAttribute();
                    stIdAtt.setName("stId");
                    stIdAtt.setCategory(curationAtt.getCategory());
                    stIdAtt.setDefiningType(curationAtt.getDefiningType());
                    curationAttributes.add(stIdAtt);
                }
            }
            clsName2attributes.put(cls.getName(), curationAttributes);
        }
        // Hacking this so that we have a top level pathway
        clsName2attributes.put(TopLevelPathway.class.getSimpleName(), 
                clsName2attributes.get(ReactomeJavaConstants.Pathway));
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(new File(fileName), clsName2attributes);
        System.out.println("Done dumping: " + fileName);
    }
    
    /**
     * Some names are changes in the production graph database. Use the updated names.
     * @return
     */
    private Map<String, String> mapAttNames() {
        Map<String, String> old2new = Map.of(
                "DB_ID", "dbId",
                "_doRelease", "doRelease",
//                "stableIdentifier", "stId",
                "_displayName", "displayName"
                );
        return old2new;
    }

}
