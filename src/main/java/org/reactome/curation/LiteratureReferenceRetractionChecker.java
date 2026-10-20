package org.reactome.curation;

import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Check if any PubMed ids used by LiteratureReferences in the curation graph database have been retracted.
 * This is a port of org.gk.scripts.LiteratureReferenceRetractionChecker#checkLiteratureReferences() in the
 * CuratorTool project, using raw Cypher against the graph database instead of MySQLAdaptor.
 *
 * The retraction list is the Retraction Watch dataset distributed by Crossref
 * (https://gitlab.com/crossref/retraction-watch-data, or https://api.labs.crossref.org/data/retractionwatch?name@email.org),
 * which should be downloaded locally first. Its OriginalPaperPubMedID column is used.
 *
 * This class is read-only: all queries are run in READ transactions so the database will reject any write.
 * It does not boot the Spring context, so it can be run while the web service itself is running.
 *
 * Usage: LiteratureReferenceRetractionChecker --retraction-file=FILE --output-file=FILE
 *                                             [--neo4j-uri=URI] [--neo4j-user=USER] [--neo4j-password=PWD]
 *                                             [--neo4j-database=NAME] [--developer-dbids=ID1,ID2,...]
 *   --retraction-file=FILE  The Retraction Watch csv file.
 *   --output-file=FILE      The tab-delimited report to write.
 *   --neo4j-*               Graph database connection. Defaults come from application.properties
 *                           (spring.neo4j.uri, spring.neo4j.authentication.*, spring.data.neo4j.database).
 *   --developer-dbids=IDS   Person dbIds whose InstanceEdits are skipped when picking the first/last curator
 *                           IEs (the same as QACheckProperties.getDeveloperDbIds() in CuratorTool).
 */
public class LiteratureReferenceRetractionChecker {
    private static final Logger logger = LoggerFactory.getLogger(LiteratureReferenceRetractionChecker.class);

    // Referrers of the retracted LiteratureReferences. Relationships stored as pointing to the LiteratureReference
    // that are actually its own attributes (created/modified/modifiedList from InstanceEdit and author from Person)
    // are excluded.
    private static final String REFERRER_QUERY = ""
            + "MATCH (lr:LiteratureReference) WHERE lr.pubMedIdentifier IN $pmids "
            + "MATCH (r:DatabaseObject)-[rel]->(lr) "
            + "WHERE NOT r:InstanceEdit AND NOT (r:Person AND type(rel) = 'author') "
            + "WITH DISTINCT lr, r "
            + "OPTIONAL MATCH (r)-[:stableIdentifier]->(st:StableIdentifier) "
            + "OPTIONAL MATCH (r)-[:literatureReference]->(other:LiteratureReference) "
            + "WHERE other.pubMedIdentifier IS NOT NULL AND other.pubMedIdentifier <> lr.pubMedIdentifier "
            + "RETURN lr.dbId AS lrDbId, lr.displayName AS lrDisplayName, lr.pubMedIdentifier AS pmid, "
            + "r.dbId AS dbId, r.displayName AS displayName, r.schemaClass AS schemaClass, "
            + "st.released AS released, collect(DISTINCT other.pubMedIdentifier) AS otherPmids "
            + "ORDER BY lrDbId, dbId";

    // InstanceEdits for the referrers: created, all modified in order, and the single latest modified as a
    // fallback in case modifiedList has not been populated.
    private static final String IE_QUERY = ""
            + "UNWIND $dbIds AS id "
            + "MATCH (r:DatabaseObject {dbId: id}) "
            + "OPTIONAL MATCH (r)<-[:created]-(c:InstanceEdit) "
            + "WITH r, c "
            + "OPTIONAL MATCH (r)<-[ml:modifiedList]-(m:InstanceEdit) "
            + "OPTIONAL MATCH (m)<-[:author]-(a:Person) "
            + "WITH r, c, ml, m, collect(a.dbId) AS authorIds "
            + "ORDER BY ml.order "
            + "WITH r, c, collect(CASE WHEN m IS NULL THEN NULL "
            + "    ELSE {displayName: m.displayName, authorIds: authorIds} END) AS modIEs "
            + "OPTIONAL MATCH (r)<-[:modified]-(lm:InstanceEdit) "
            + "OPTIONAL MATCH (lm)<-[:author]-(la:Person) "
            + "RETURN r.dbId AS dbId, c.displayName AS createdIE, modIEs, "
            + "lm.displayName AS modifiedIE, collect(la.dbId) AS modifiedAuthorIds";

    private final Driver driver;
    private final String database;
    private final Set<Long> developerDbIds;

    public LiteratureReferenceRetractionChecker(Driver driver, String database, Set<Long> developerDbIds) {
        this.driver = driver;
        this.database = database;
        this.developerDbIds = developerDbIds;
    }

    public static void main(String[] args) {
        Properties props = loadApplicationProperties();
        String uri = props.getProperty("spring.neo4j.uri", "bolt://localhost:7687");
        String user = props.getProperty("spring.neo4j.authentication.username", "neo4j");
        String password = props.getProperty("spring.neo4j.authentication.password");
        String database = props.getProperty("spring.data.neo4j.database");
        String retractionFile = null;
        String outputFile = null;
        Set<Long> developerDbIds = new HashSet<>();
        for (String arg : args) {
            if (arg.startsWith("--neo4j-uri="))
                uri = arg.substring("--neo4j-uri=".length());
            else if (arg.startsWith("--neo4j-user="))
                user = arg.substring("--neo4j-user=".length());
            else if (arg.startsWith("--neo4j-password="))
                password = arg.substring("--neo4j-password=".length());
            else if (arg.startsWith("--neo4j-database="))
                database = arg.substring("--neo4j-database=".length());
            else if (arg.startsWith("--retraction-file="))
                retractionFile = arg.substring("--retraction-file=".length());
            else if (arg.startsWith("--output-file="))
                outputFile = arg.substring("--output-file=".length());
            else if (arg.startsWith("--developer-dbids=")) {
                Arrays.stream(arg.substring("--developer-dbids=".length()).split(","))
                      .map(String::trim)
                      .filter(s -> !s.isEmpty())
                      .forEach(s -> developerDbIds.add(Long.parseLong(s)));
            }
        }
        if (retractionFile == null || outputFile == null) {
            System.err.println("Usage: LiteratureReferenceRetractionChecker --retraction-file=FILE --output-file=FILE "
                    + "[--neo4j-uri=URI] [--neo4j-user=USER] [--neo4j-password=PWD] [--neo4j-database=NAME] "
                    + "[--developer-dbids=ID1,ID2,...]");
            System.exit(1);
        }
        int exitCode = 0;
        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password))) {
            LiteratureReferenceRetractionChecker checker = new LiteratureReferenceRetractionChecker(driver,
                    database,
                    developerDbIds);
            checker.checkLiteratureReferences(retractionFile, outputFile);
        }
        catch (Exception e) {
            logger.error("Error in LiteratureReferenceRetractionChecker.main(): " + e, e);
            exitCode = 1;
        }
        if (exitCode != 0)
            System.exit(exitCode);
    }

    private static Properties loadApplicationProperties() {
        Properties props = new Properties();
        try (InputStream is = LiteratureReferenceRetractionChecker.class.getResourceAsStream("/application.properties")) {
            if (is != null)
                props.load(is);
        }
        catch (IOException e) {
            logger.warn("Cannot load application.properties: " + e);
        }
        return props;
    }

    /**
     * Load the PubMed ids of the original papers that have been retracted.
     */
    public Set<Long> loadRetractedPubmedIds(String retractionFile) throws IOException {
        Set<Long> pubmedIds = new HashSet<>();
        try (Reader reader = new FileReader(retractionFile)) {
            Iterable<CSVRecord> records = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .build()
                    .parse(reader);
            for (CSVRecord record : records) {
                String pubmedId = record.get("OriginalPaperPubMedID").trim();
                if (pubmedId.isEmpty())
                    continue;
                try {
                    long id = Long.parseLong(pubmedId);
                    if (id > 0) // 0 (or variants like 00) is used as a placeholder for no PubMed id
                        pubmedIds.add(id);
                }
                catch (NumberFormatException e) {
                    logger.warn("Invalid OriginalPaperPubMedID at record " + record.getRecordNumber() + ": " + pubmedId);
                }
            }
        }
        logger.info("Total retracted pubmed ids: " + pubmedIds.size());
        return pubmedIds;
    }

    public void checkLiteratureReferences(String retractionFile, String outputFile) throws IOException {
        Set<Long> retractedPubmedIds = loadRetractedPubmedIds(retractionFile);
        SessionConfig.Builder configBuilder = SessionConfig.builder().withDefaultAccessMode(AccessMode.READ);
        if (database != null && !database.isEmpty())
            configBuilder.withDatabase(database);
        try (Session session = driver.session(configBuilder.build());
             PrintWriter writer = new PrintWriter(outputFile)) {
            List<Record> referrers = session.readTransaction(tx ->
                    tx.run(REFERRER_QUERY, Map.of("pmids", new ArrayList<>(retractedPubmedIds))).list());
            Set<Long> retractedLitRefs = referrers.stream().map(r -> r.get("lrDbId").asLong()).collect(Collectors.toSet());
            logger.info("Retracted LiteratureReferences in use: " + retractedLitRefs.size() +
                        " with " + referrers.size() + " referrers");
            List<Long> referrerDbIds = referrers.stream()
                    .map(r -> r.get("dbId").asLong())
                    .distinct()
                    .collect(Collectors.toList());
            Map<Long, String[]> dbId2IEs = new HashMap<>();
            session.readTransaction(tx -> tx.run(IE_QUERY, Map.of("dbIds", referrerDbIds)).list())
                   .forEach(r -> dbId2IEs.put(r.get("dbId").asLong(), getFirstAndLastCuratorIEs(r)));

            writer.println("LitRef_DBID\tLitRef_DisplayName\tPubMedId\tReferrer_DBID"
                    + "\tReferrer_DisplayName\tReferrer_SchemaClass\tReferer_FirstIE"
                    + "\tReferrer_LastIE\tReferrer_Release_Status\tReferrer_Other_PMIDs");
            for (Record referrer : referrers) {
                long dbId = referrer.get("dbId").asLong();
                String[] ies = dbId2IEs.getOrDefault(dbId, new String[]{"", ""});
                writer.println(referrer.get("lrDbId").asLong() + "\t" +
                        asString(referrer.get("lrDisplayName")) + "\t" +
                        referrer.get("pmid").asLong() + "\t" +
                        dbId + "\t" +
                        asString(referrer.get("displayName")) + "\t" +
                        asString(referrer.get("schemaClass")) + "\t" +
                        ies[0] + "\t" +
                        ies[1] + "\t" +
                        asString(referrer.get("released")) + "\t" +
                        getOtherPMIDs(referrer.get("otherPmids")));
            }
        }
        logger.info("Done writing: " + outputFile);
    }

    /**
     * Follow QACheckUtilities in CuratorTool: the first IE is the created IE, or the first modified IE by a
     * non-developer if there is no created. The last IE is the latest modified IE by a non-developer,
     * or the created IE if there is no such modified IE.
     */
    private String[] getFirstAndLastCuratorIEs(Record record) {
        String createdIE = record.get("createdIE").isNull() ? null : record.get("createdIE").asString();
        List<Map<String, Object>> modIEs = new ArrayList<>(record.get("modIEs").asList(Value::asMap));
        // Fall back to the single modified IE if modifiedList is empty
        if (modIEs.isEmpty() && !record.get("modifiedIE").isNull()) {
            modIEs.add(Map.of("displayName", record.get("modifiedIE").asString(),
                              "authorIds", record.get("modifiedAuthorIds").asList(Value::asObject)));
        }
        List<String> curatorIEs = modIEs.stream()
                .filter(this::isCuratorIE)
                .map(ie -> (String) ie.get("displayName"))
                .collect(Collectors.toList());
        String firstIE = createdIE != null ? createdIE : (curatorIEs.isEmpty() ? null : curatorIEs.get(0));
        String lastIE = curatorIEs.isEmpty() ? createdIE : curatorIEs.get(curatorIEs.size() - 1);
        return new String[]{getIEText(firstIE), getIEText(lastIE)};
    }

    private boolean isCuratorIE(Map<String, Object> ie) {
        List<?> authorIds = (List<?>) ie.get("authorIds");
        if (authorIds == null || authorIds.isEmpty())
            return false;
        return authorIds.stream().anyMatch(id -> !developerDbIds.contains(((Number) id).longValue()));
    }

    private String getIEText(String displayName) {
        if (displayName == null)
            return "";
        int index = displayName.indexOf("]");
        return displayName.substring(index + 1).trim();
    }

    private String getOtherPMIDs(Value otherPmids) {
        List<Long> pmids = otherPmids.asList(Value::asLong);
        if (pmids.isEmpty())
            return "";
        pmids = new ArrayList<>(pmids);
        Collections.sort(pmids);
        return pmids.stream().map(String::valueOf).collect(Collectors.joining(";"));
    }

    private String asString(Value value) {
        if (value == null || value.isNull())
            return "";
        return value.asObject().toString();
    }

}
