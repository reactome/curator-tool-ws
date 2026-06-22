package org.reactome.curation.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PsiModOlsUtilTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void buildsOLSQueryUri() {
        URI uri = PsiModOlsUtil.buildTermUri("MOD:00696", "MOD");
        assertEquals("https://www.ebi.ac.uk/ols/api/ontologies/MOD/terms?obo_id=MOD%3A00696", uri.toString());
    }

    @Test
    void extractsMetadataAndXrefsFromTermJson() throws Exception {
        String json = "{" +
                "\"description\":[\"phosphorylated residue\"]," +
                "\"synonyms\":[\"phospho residue\"]," +
                "\"annotation\":{" +
                "  \"has_related_synonym\":[\"phosphorylation site\"]," +
                "  \"database_cross_reference\":{\"UniProtKB\":[\"P12345\",\"Q99999\"]}" +
                "}" +
                "}";
        JsonNode termNode = mapper.readTree(json);

        Map<String, String> meta = PsiModOlsUtil.extractMetadata(termNode);
        assertEquals("phosphorylated residue", meta.get("definition"));
        assertTrue(meta.containsValue("phospho residue"));
        assertTrue(meta.containsValue("phosphorylation site"));

        Map<String, String> xrefs = PsiModOlsUtil.extractXrefs(termNode);
        assertEquals(2, xrefs.size());
        assertTrue(xrefs.containsKey("P12345"));
        assertTrue(xrefs.containsKey("Q99999"));
    }
}

