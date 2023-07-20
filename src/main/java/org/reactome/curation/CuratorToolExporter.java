package org.reactome.curation;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.gk.model.Instance;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.Schema;
import org.gk.schema.SchemaAttribute;
import org.gk.schema.SchemaClass;
import org.reactome.curation.model.CurationAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonFactoryBuilder;
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
     * Dump curation specific attributes
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
                curationAtt.setName(attName);
                curationAtt.setCategory(CurationAttribute.Category.getCategory(att.getCategory()));
                curationAtt.setDefiningType(CurationAttribute.DefiningType.getDefiningType(att.getDefiningType()));
                curationAttributes.add(curationAtt);
            }
            clsName2attributes.put(cls.getName(), curationAttributes);
        }
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(new File(fileName), clsName2attributes);
    }
    
    /**
     * Some names are changes in the production graph database. Use the updated names.
     * @return
     */
    private Map<String, String> mapAttNames() {
        Map<String, String> old2new = Map.of(
                "DB_ID", "dbId",
                "stableIdentifier", "stId",
                "_displayName", "displayName"
                );
        return old2new;
    }

}
