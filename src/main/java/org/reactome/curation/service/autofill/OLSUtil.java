package org.reactome.curation.service.autofill;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.net.http.HttpClient.Version;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Helper for reading ontology terms from the EBI OLS REST API.
 */
@Component
public class OLSUtil {

    @Value("${ols.base-url:http://www.ebi.ac.uk/ols/api/ontologies/}")
    private String olsBaseUrl;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public String getTermById(String termId, String ontologyName) {
        JsonNode termNode = fetchTermNode(termId, ontologyName);
        if (termNode == null) {
            return "";
        }
        JsonNode label = termNode.get("label");
        return label == null ? "" : label.asText("");
    }

    public Map<String, String> getTermXrefs(String termId, String ontologyName) {
        JsonNode termNode = fetchTermNode(termId, ontologyName);
        if (termNode == null) {
            return new LinkedHashMap<>();
        }
        return extractXrefs(termNode);
    }

    public Map<String, String> getTermMetadata(String termId, String ontologyName) {
        JsonNode termNode = fetchTermNode(termId, ontologyName);
        if (termNode == null) {
            return new LinkedHashMap<>();
        }
        return extractMetadata(termNode);
    }

    static Map<String, String> extractXrefs(JsonNode termNode) {
        JsonNode xrefNode = termNode.path("annotation").path("database_cross_reference");
        List<String> xrefs = new ArrayList<>();
        collectTextLeaves(xrefNode, xrefs);
        Map<String, String> rtn = new LinkedHashMap<>();
        for (String xref : xrefs) {
            if (xref != null && !xref.isBlank()) {
                rtn.put(xref, xref);
            }
        }
        return rtn;
    }

    static Map<String, String> extractMetadata(JsonNode termNode) {
        Map<String, String> meta = new LinkedHashMap<>();

        // Extract formula if present
        JsonNode oboSynonymNode = termNode.path("obo_synonym");
        if (oboSynonymNode.isArray()) {
            for (JsonNode syn : oboSynonymNode) {
                if (syn.path("type").asText("").equals("FORMULA")) {
                    String formula = syn.path("name").asText();
                    if (formula != null && !formula.isBlank()) {
                        meta.put("FORMULA_synonym", formula);
                        break;
                    }
                }
            }
        }

        // Extract definition
        String definition = firstTextValue(termNode.path("description"));
        if (definition != null && !definition.isBlank()) {
            meta.put("definition", definition);
        }

        // Extract synonyms from multiple sources
        List<String> synonyms = new ArrayList<>();
        String label = termNode.path("label").asText();

        collectTextLeaves(termNode.path("synonyms"), synonyms);
        collectTextLeaves(termNode.path("annotation").path("has_related_synonym"), synonyms);
        collectTextLeaves(termNode.path("annotation").path("related_synonym"), synonyms);
        collectTextLeaves(termNode.path("annotation").path("exact_synonym"), synonyms);

        // Remove duplicates and the label itself
        Set<String> uniqueSynonyms = new java.util.LinkedHashSet<>(synonyms);
        uniqueSynonyms.remove(label);
        uniqueSynonyms.removeIf(s -> s == null || s.isBlank());

        // Store synonyms with multiple key formats for backward compatibility
        int i = 0;
        for (String synonym : uniqueSynonyms) {
            meta.put(i + "_related_synonym", synonym);
            meta.put("related_synonym_" + i, synonym);
            meta.put("exact_synonym_" + i, synonym);
            i++;
        }

        return meta;
    }

    URI buildTermUri(String termId, String ontologyName) {
        return URI.create(olsBaseUrl + encode(ontologyName) + "/terms?obo_id=" + encode(termId));
    }

    JsonNode fetchTermNode(String termId, String ontologyName) {
        try {
            HttpRequest request = HttpRequest.newBuilder(buildTermUri(termId, ontologyName))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode terms = root.path("_embedded").path("terms");
            if (!terms.isArray() || terms.isEmpty()) {
                return null;
            }
            return terms.get(0);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        catch (IOException e) {
            // Handle connection errors including GOAWAY, connection reset, etc.
            if (e.getMessage() != null && (e.getMessage().contains("GOAWAY") ||
                    e.getMessage().contains("Connection reset") ||
                    e.getMessage().contains("connection reset by peer"))) {
                return null;
            }
            return null;
        }
        catch (Exception e) {
            // Catch any other unexpected exceptions
            return null;
        }
    }

    static void collectTextLeaves(JsonNode node, List<String> values) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            values.add(node.asText());
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectTextLeaves(child, values);
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectTextLeaves(entry.getValue(), values));
        }
    }

    private static String firstTextValue(JsonNode node) {
        List<String> values = new ArrayList<>();
        collectTextLeaves(node, values);
        return values.isEmpty() ? null : values.get(0);
    }

    private static String encode(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}