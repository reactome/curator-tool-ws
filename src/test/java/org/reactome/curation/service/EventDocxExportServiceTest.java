package org.reactome.curation.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.reactome.server.graph.domain.model.InstanceEdit;
import org.reactome.server.graph.domain.model.LiteratureReference;
import org.reactome.server.graph.domain.model.Pathway;
import org.reactome.server.graph.domain.model.Person;
import org.reactome.server.graph.domain.model.Publication;
import org.reactome.server.graph.domain.model.Reaction;
import org.reactome.server.graph.domain.model.Species;
import org.reactome.server.graph.domain.model.StableIdentifier;
import org.reactome.server.graph.domain.model.Summation;

public class EventDocxExportServiceTest {

    private final EventDocxExportService service = new EventDocxExportService();

    @Test
    void exportEventDocxShouldGenerateNonEmptyDocx() throws Exception {
        Pathway event = new Pathway(109582L);
        event.setDisplayName("Apoptosis");

        byte[] docxBytes = service.exportEventDocx(event);

        assertThat(docxBytes).isNotEmpty();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            String text = doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .collect(Collectors.joining("\n"));
            assertThat(text).contains("Reactome Event Export");
            assertThat(text).contains("Apoptosis");
            // Metadata fields
            String tableText = doc.getTables().stream()
                    .flatMap(t -> t.getRows().stream())
                    .flatMap(r -> r.getTableCells().stream())
                    .map(c -> c.getText())
                    .collect(Collectors.joining(" "));
            assertThat(tableText).contains("dbId").contains("stableId").contains("species").contains("compartment");
        }
    }

    @Test
    void buildFileNameShouldSanitizeDisplayName() {
        Reaction event = new Reaction(12345L);
        event.setDisplayName("R-HSA-12345: apoptosis / cell death");

        String fileName = service.buildFileName(event);

        assertThat(fileName).startsWith("event_12345_");
        assertThat(fileName).endsWith(".docx");
        assertThat(fileName).doesNotContain(" ").doesNotContain(":").doesNotContain("/");
    }

    @Test
    void addParagraphShouldApplyFormattingAndMarkup() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            Map<String, Object> formatting = new HashMap<>();
            formatting.put("font", "Courier New");
            formatting.put("font_size", 13);
            formatting.put("bold", true);
            formatting.put("justify", "center");

            XWPFParagraph paragraph = service.addParagraph(document,
                    "start <i>italic</i> and <font color=red>red</font>",
                    formatting);

            assertThat(paragraph.getParagraphText()).contains("start").contains("italic").contains("red");
            assertThat(paragraph.getAlignment().name()).isEqualTo("CENTER");
            assertThat(paragraph.getRuns()).isNotEmpty();
            boolean hasRedRun = paragraph.getRuns().stream().map(XWPFRun::getColor).anyMatch("FF0000"::equals);
            assertThat(hasRedRun).isTrue();
        }
    }

    @Test
    void helperMethodsShouldAddParagraphsAndPageBreak() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            int initialCount = document.getParagraphs().size();
            service.addHeader(document, 2, "Section");
            service.addBulletText(document, "Bullet 1");
            service.addNumberedText(document, "Item", 1);
            service.addHyperlink(document, "Reactome", "https://reactome.org");
            service.addPageBreak(document);

            assertThat(document.getParagraphs().size()).isEqualTo(initialCount + 5);
            assertThat(document.getParagraphs().get(document.getParagraphs().size() - 1).isPageBreak()).isTrue();
        }
    }

    /**
     * A raw '\n' embedded inside a single &lt;w:t&gt; (e.g. from a curator's multi-line
     * InstanceEdit note, rendered via the "Authored"/"Edited"/"Reviewed" bullet lines) isn't
     * valid line-break markup - Word renders it leniently, but Google Docs' stricter DOCX
     * importer doesn't honor it. Each line must land in its own &lt;w:t&gt;, separated by
     * explicit &lt;w:br/&gt; elements.
     */
    @Test
    void addBulletTextShouldConvertEmbeddedNewlinesToExplicitBreaks() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = service.addBulletText(document, "Line one\nLine two\nLine three");

            List<XWPFRun> runs = paragraph.getRuns();
            assertThat(runs).hasSize(1);
            XWPFRun run = runs.get(0);

            List<String> texts = new ArrayList<>();
            for (var t : run.getCTR().getTList())
                texts.add(t.getStringValue());
            assertThat(texts).containsExactly("Line one", "Line two", "Line three");
            assertThat(texts).noneMatch(t -> t.contains("\n"));
            assertThat(run.getCTR().getBrList()).hasSize(2);
        }
    }

    /**
     * The same raw-'\n'-survives-in-a-single-&lt;w:t&gt; problem also affects the HTML-markup-
     * aware path (addParagraph()/appendMarkupAwareText()) used for Summation text and other rich
     * content: Jsoup's TextNode.text() normalizes a curator's plain-text line/paragraph breaks
     * (never wrapped in real &lt;br&gt;/&lt;p&gt; tags) into a single space, silently losing
     * them. A single '\n' should become a real &lt;w:br/&gt; within the same paragraph; a blank
     * line should start a genuinely new &lt;w:p&gt; - exactly as if the source had used
     * &lt;br&gt;/&lt;p&gt; tags directly.
     */
    @Test
    void addParagraphShouldConvertPlainTextLineAndParagraphBreaks() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            int before = document.getParagraphs().size();
            service.addParagraph(document, "Line one\nLine two\n\nSecond paragraph.", new HashMap<>());

            List<XWPFParagraph> added = document.getParagraphs().subList(before, document.getParagraphs().size());
            assertThat(added).hasSize(2);

            XWPFParagraph firstParagraph = added.get(0);
            // POI's getText() renders a <w:br/> back as "\n" for convenience - the assertions
            // below on the underlying CTR XML are what actually confirm no raw '\n' survives
            // inside a <w:t>.
            assertThat(firstParagraph.getText()).isEqualTo("Line one\nLine two");
            List<String> firstParagraphTexts = new ArrayList<>();
            int breakCount = 0;
            for (XWPFRun run : firstParagraph.getRuns()) {
                for (var t : run.getCTR().getTList())
                    firstParagraphTexts.add(t.getStringValue());
                breakCount += run.getCTR().getBrList().size();
            }
            assertThat(firstParagraphTexts).containsExactly("Line one", "Line two");
            assertThat(firstParagraphTexts).noneMatch(t -> t.contains("\n"));
            assertThat(breakCount).isEqualTo(1);

            XWPFParagraph secondParagraph = added.get(1);
            assertThat(secondParagraph.getText()).isEqualTo("Second paragraph.");
        }
    }

    /**
     * An Event and its Summation may cite overlapping and distinct references. The export should
     * merge both sets into one Literature References list, de-duplicating by dbId rather than
     * printing the shared reference twice.
     */
    @Test
    void literatureReferencesShouldMergeEventAndSummationReferences() throws Exception {
        Pathway event = new Pathway(200001L);
        event.setDisplayName("Merge Test Pathway");

        LiteratureReference eventRef = new LiteratureReference();
        eventRef.setDbId(1L);
        eventRef.setTitle("Event-level reference");
        eventRef.setJournal("Journal A");
        eventRef.setYear(2001);
        eventRef.setPubMedIdentifier(1111111);
        event.setLiteratureReference(new ArrayList<>(List.of(eventRef)));

        // Same dbId as eventRef - should be de-duplicated, not listed twice.
        LiteratureReference duplicateOfEventRef = new LiteratureReference();
        duplicateOfEventRef.setDbId(1L);
        duplicateOfEventRef.setTitle("Event-level reference");
        duplicateOfEventRef.setJournal("Journal A");
        duplicateOfEventRef.setYear(2001);
        duplicateOfEventRef.setPubMedIdentifier(1111111);

        LiteratureReference summationOnlyRef = new LiteratureReference();
        summationOnlyRef.setDbId(2L);
        summationOnlyRef.setTitle("Summation-only reference");
        summationOnlyRef.setJournal("Journal B");
        summationOnlyRef.setYear(2002);
        summationOnlyRef.setPubMedIdentifier(2222222);

        Summation summation = new Summation();
        summation.setText("Some summation text citing its own references.");
        summation.setLiteratureReference(new ArrayList<>(List.of(duplicateOfEventRef, summationOnlyRef)));
        event.setSummation(new ArrayList<>(List.of(summation)));

        byte[] docxBytes = service.exportEventDocx(event);
        assertThat(docxBytes).isNotEmpty();

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            String text = doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .collect(Collectors.joining("\n"));

            assertThat(text).contains("Literature References");
            assertThat(text).contains("Event-level reference");
            assertThat(text).contains("Summation-only reference");

            long occurrences = text.lines().filter(line -> line.contains("Event-level reference")).count();
            assertThat(occurrences).isEqualTo(1);
        }
    }

    /**
     * When a Publication has authorName set (the PubMed-style strings set by
     * LiteratureReferenceAttributeAutoFiller), the export should use those verbatim instead of
     * formatting the "author" Person relationship, even when both are present.
     */
    @Test
    void formatPublicationAuthorsShouldPreferAuthorNameOverAuthorList() throws Exception {
        Pathway event = new Pathway(300001L);
        event.setDisplayName("AuthorName Preference Test");

        LiteratureReference ref = new LiteratureReference();
        ref.setDbId(10L);
        ref.setTitle("A paper with authorName set");
        ref.setJournal("Journal C");
        ref.setYear(2010);
        ref.setPubMedIdentifier(3333333);
        ref.setAuthorName(List.of("Smith JA", "Doe RK"));

        Person person = new Person();
        person.setSurname("ShouldNotAppear");
        person.setFirstname("Nobody");
        ref.setAuthor(List.of(person));

        event.setLiteratureReference(new ArrayList<>(List.of(ref)));

        byte[] docxBytes = service.exportEventDocx(event);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            String text = doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .collect(Collectors.joining("\n"));
            assertThat(text).contains("Smith JA, Doe RK");
            assertThat(text).doesNotContain("ShouldNotAppear");
        }
    }

    /**
     * When authorName isn't populated (e.g. an older LiteratureReference), the export should fall
     * back to formatting the "author" Person relationship, as before.
     */
    @Test
    void formatPublicationAuthorsShouldFallBackToAuthorListWhenAuthorNameMissing() throws Exception {
        Pathway event = new Pathway(300002L);
        event.setDisplayName("Author List Fallback Test");

        LiteratureReference ref = new LiteratureReference();
        ref.setDbId(11L);
        ref.setTitle("A paper without authorName");
        ref.setJournal("Journal D");
        ref.setYear(2011);
        ref.setPubMedIdentifier(4444444);

        Person person = new Person();
        person.setSurname("Fallback");
        person.setFirstname("Author");
        ref.setAuthor(List.of(person));

        event.setLiteratureReference(new ArrayList<>(List.of(ref)));

        byte[] docxBytes = service.exportEventDocx(event);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            String text = doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .collect(Collectors.joining("\n"));
            assertThat(text).contains("Fallback");
        }
    }

    /**
     * Creates a fully-populated Pathway event and writes it to
     * target/test-event-export.docx so the output can be inspected visually.
     * Also asserts that the file has non-zero size and that the expected
     * sections (title, summation, authored, literature references) are present.
     */
    @Test
    void exportEventDocxShouldWriteFileWithRichContent() throws Exception {
        // --- Build a richly-populated Pathway ---
        Pathway event = new Pathway(109582L);
        event.setDisplayName("Apoptosis");

        StableIdentifier stId = new StableIdentifier();
        stId.setIdentifier("R-HSA-109582");
        event.setStableIdentifier(stId);

        Species hs = new Species();
        hs.setDisplayName("Homo sapiens");
        event.setSpecies(List.of(hs));

        Summation summation = new Summation();
        summation.setText("Apoptosis is a <b>programmed</b> cell death mechanism. "
                + "It plays a critical role in <i>development</i> and tissue homeostasis.");
        event.setSummation(List.of(summation));

        Person author = new Person();
        author.setSurname("Croft");
        author.setFirstname("David");
        InstanceEdit ie = new InstanceEdit();
        ie.setDateTime("2005-01-01 00:00:00");
        ie.setAuthor(List.of(author));
        event.setAuthored(List.of(ie));

        LiteratureReference ref = new LiteratureReference();
        ref.setTitle("The biochemistry of apoptosis");
        ref.setJournal("Nature");
        ref.setYear(2000);
        ref.setVolume(407);
        ref.setPages("770-776");
        ref.setPubMedIdentifier(11048727);
        List<Publication> pubs = new ArrayList<>();
        pubs.add(ref);
        event.setLiteratureReference(pubs);

        // --- Export to bytes ---
        byte[] docxBytes = service.exportEventDocx(event);
        assertThat(docxBytes).isNotEmpty();

        // --- Write to target/ so it can be opened manually ---
        Path outputPath = Path.of("target", "test-event-export.docx");
        Files.createDirectories(outputPath.getParent());
        Files.write(outputPath, docxBytes);
        assertThat(Files.size(outputPath)).isGreaterThan(0L);
        System.out.println("DOCX written to: " + outputPath.toAbsolutePath());

        // --- Assert document content ---
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            String paragraphText = doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .collect(Collectors.joining("\n"));

            // Title and event name
            assertThat(paragraphText).contains("Reactome Event Export");
            assertThat(paragraphText).contains("Apoptosis");

            // Section headers
            assertThat(paragraphText).contains("Summation");
            assertThat(paragraphText).contains("Authored");
            assertThat(paragraphText).contains("Literature References");

            // Summation body text (markup stripped by DOCX runs)
            assertThat(paragraphText).contains("programmed");
            assertThat(paragraphText).contains("development");

            // Author bullet
            assertThat(paragraphText).contains("Croft");

            // Reference text (numbered or hyperlinked)
            assertThat(paragraphText).contains("biochemistry of apoptosis");

            // Metadata table
            String tableText = doc.getTables().stream()
                    .flatMap(t -> t.getRows().stream())
                    .flatMap(r -> r.getTableCells().stream())
                    .map(c -> c.getText())
                    .collect(Collectors.joining(" "));
            assertThat(tableText).contains("dbId").contains("stableId").contains("R-HSA-109582")
                    .contains("species").contains("Homo sapiens");
        }
    }
}


