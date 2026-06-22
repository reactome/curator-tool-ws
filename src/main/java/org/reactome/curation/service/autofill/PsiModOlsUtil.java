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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Helper for reading ontology terms from the EBI OLS REST API.
 */
public final class PsiModOlsUtil {
    private static final String OLS_BASE_URL = "https://www.ebi.ac.uk/ols/api/ontologies/";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private PsiModOlsUtil() {
    }

    public static String getTermById(String termId, String ontologyName) {
        JsonNode termNode = fetchTermNode(termId, ontologyName);
        if (termNode == null) {
            return "";
        }
        JsonNode label = termNode.get("label");
        return label == null ? "" : label.asText("");
    }

    public static Map<String, String> getTermXrefs(String termId, String ontologyName) {
        JsonNode termNode = fetchTermNode(termId, ontologyName);
        if (termNode == null) {
            return new LinkedHashMap<>();
        }
        return extractXrefs(termNode);
    }

    public static Map<String, String> getTermMetadata(String termId, String ontologyName) {
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

        String definition = firstTextValue(termNode.path("description"));
        if (definition != null && !definition.isBlank()) {
            meta.put("definition", definition);
        }

        List<String> synonyms = new ArrayList<>();
        collectTextLeaves(termNode.path("synonyms"), synonyms);
        collectTextLeaves(termNode.path("annotation").path("has_related_synonym"), synonyms);
        collectTextLeaves(termNode.path("annotation").path("related_synonym"), synonyms);
        collectTextLeaves(termNode.path("annotation").path("exact_synonym"), synonyms);

        int index = 0;
        for (String synonym : synonyms) {
            if (synonym == null || synonym.isBlank()) {
                continue;
            }
            meta.put("related_" + index + "_synonym", synonym);
            index++;
        }
        return meta;
    }

    static URI buildTermUri(String termId, String ontologyName) {
        return URI.create(OLS_BASE_URL + encode(ontologyName) + "/terms?obo_id=" + encode(termId));
    }

    static JsonNode fetchTermNode(String termId, String ontologyName) {
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
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
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