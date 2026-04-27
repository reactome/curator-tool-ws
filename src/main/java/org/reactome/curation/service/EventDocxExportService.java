package org.reactome.curation.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.VerticalAlign;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.reactome.server.graph.domain.model.Compartment;
import org.reactome.server.graph.domain.model.Event;
import org.reactome.server.graph.domain.model.InstanceEdit;
import org.reactome.server.graph.domain.model.LiteratureReference;
import org.reactome.server.graph.domain.model.Person;
import org.reactome.server.graph.domain.model.Publication;
import org.reactome.server.graph.domain.model.Species;
import org.reactome.server.graph.domain.model.StableIdentifier;
import org.reactome.server.graph.domain.model.Summation;
import org.springframework.stereotype.Service;

@Service
public class EventDocxExportService {

    private static final String DOCX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern MARKUP_PATTERN = Pattern.compile("(?i)<(/?)(font|b|i|sup|sub)(?:\\s+color=red)?>|<img\\s+src=\"([^\"]+)\"[^>]*>");

    /**
     * Export an Event as a rich DOCX document, following the same structure as the
     * Perl GenerateTextRTF.pm:
     *
     *  1. Document title  (generate_prolog / header)
     *  2. Metadata table  (dbId, stable id, schema class, species, compartments)
     *  3. Summation(s)    (generate_paragraph with markup)
     *  4. Authorship      (generate_header + paragraph)
     *  5. Literature references (generate_hyperlink style)
     */
    public byte[] exportEventDocx(Event event) throws IOException {
        if (event == null) {
            throw new IllegalArgumentException("Event cannot be null.");
        }

        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            // --- 1. Title (Perl: generate_prolog + generate_header depth=0) ---
            addHeader(document, 0, "Reactome Event Export");
            addHeader(document, 1, valueOrNA(event.getDisplayName()));

            // --- 2. Metadata table ---
            createMetadataTable(document, event);

            // --- 3. Summations (Perl: generate_paragraph with markup interpretation) ---
            try {
                List<Summation> summations = event.getSummation();
                if (summations != null && !summations.isEmpty()) {
                    addHeader(document, 2, "Summation");
                    for (Summation summation : summations) {
                        String text = summation.getText();
                        if (text != null && !text.isBlank()) {
                            Map<String, Object> fmt = Map.of("font", "Courier New", "font_size", 11);
                            addParagraph(document, text, fmt);
                        }
                    }
                }
            } catch (Exception ignored) { }

            // --- 4. Authorship ---
            try {
                List<InstanceEdit> authored = event.getAuthored();
                if (authored != null && !authored.isEmpty()) {
                    addHeader(document, 2, "Authored");
                    for (InstanceEdit ie : authored) {
                        String authorLine = formatInstanceEditLine(ie);
                        if (authorLine != null) addBulletText(document, authorLine);
                    }
                }
            } catch (Exception ignored) { }

            try {
                List<InstanceEdit> edited = event.getEdited();
                if (edited != null && !edited.isEmpty()) {
                    addHeader(document, 2, "Edited");
                    for (InstanceEdit ie : edited) {
                        String authorLine = formatInstanceEditLine(ie);
                        if (authorLine != null) addBulletText(document, authorLine);
                    }
                }
            } catch (Exception ignored) { }

            try {
                List<InstanceEdit> reviewed = event.getReviewed();
                if (reviewed != null && !reviewed.isEmpty()) {
                    addHeader(document, 2, "Reviewed");
                    for (InstanceEdit ie : reviewed) {
                        String authorLine = formatInstanceEditLine(ie);
                        if (authorLine != null) addBulletText(document, authorLine);
                    }
                }
            } catch (Exception ignored) { }

            // --- 5. Literature References (Perl: generate_hyperlink) ---
            try {
                List<Publication> refs = event.getLiteratureReference();
                if (refs != null && !refs.isEmpty()) {
                    addHeader(document, 2, "Literature References");
                    int num = 1;
                    for (Publication pub : refs) {
                        String citation = formatPublication(pub);
                        String url = resolvePublicationUrl(pub);
                        if (url != null) {
                            addHyperlink(document, citation, url);
                        } else {
                            addNumberedText(document, citation, num);
                        }
                        num++;
                    }
                }
            } catch (Exception ignored) { }

            document.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    // -------------------------------------------------------------------------
    // Private document section helpers
    // -------------------------------------------------------------------------

    private String formatInstanceEditLine(InstanceEdit ie) {
        if (ie == null) return null;
        StringBuilder sb = new StringBuilder();
        List<Person> authors = ie.getAuthor();
        if (authors != null && !authors.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Person p : authors) {
                String name = formatPersonName(p);
                if (name != null) names.add(name);
            }
            sb.append(String.join(", ", names));
        }
        String date = ie.getDateTime();
        if (date != null && !date.isBlank()) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("[").append(date.substring(0, Math.min(10, date.length()))).append("]");
        }
        String note = ie.getNote();
        if (note != null && !note.isBlank()) {
            if (sb.length() > 0) sb.append(": ");
            sb.append(note);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private String formatPersonName(Person p) {
        if (p == null) return null;
        String surname = p.getSurname();
        String firstname = p.getFirstname();
        String initial = p.getInitial();
        if (surname == null && firstname == null) return null;
        StringBuilder name = new StringBuilder();
        if (surname != null) name.append(surname);
        if (firstname != null) {
            if (name.length() > 0) name.append(", ");
            name.append(firstname);
        } else if (initial != null) {
            if (name.length() > 0) name.append(" ");
            name.append(initial).append(".");
        }
        return name.toString();
    }

    private String formatPublication(Publication pub) {
        if (pub == null) return "N/A";
        StringBuilder sb = new StringBuilder();
        String title = pub.getTitle();
        if (title != null) sb.append(title);
        if (pub instanceof LiteratureReference) {
            LiteratureReference lr = (LiteratureReference) pub;
            if (lr.getJournal() != null) sb.append(". ").append(lr.getJournal());
            if (lr.getYear() != null)    sb.append(" (").append(lr.getYear()).append(")");
            if (lr.getVolume() != null)  sb.append("; ").append(lr.getVolume());
            if (lr.getPages() != null)   sb.append(":").append(lr.getPages());
        }
        return sb.length() > 0 ? sb.toString() : "N/A";
    }

    private String resolvePublicationUrl(Publication pub) {
        if (pub instanceof LiteratureReference) {
            LiteratureReference lr = (LiteratureReference) pub;
            if (lr.getUrl() != null && !lr.getUrl().isBlank()) return lr.getUrl();
            if (lr.getPubMedIdentifier() != null)
                return "https://pubmed.ncbi.nlm.nih.gov/" + lr.getPubMedIdentifier();
        }
        return null;
    }

    /**
     * Perl generate_page_break equivalent for DOCX.
     */
    public void addPageBreak(XWPFDocument document) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setPageBreak(true);
    }

    /**
     * Perl generate_paragraph + translate_formatting equivalent for DOCX.
     *
     * Supported keys: font, font_size, bold, italic, underline, left_indent,
     * right_indent, first_line_indent, justify, bind_next_para, voodoo.
     */
    public XWPFParagraph addParagraph(XWPFDocument document, String text, Map<String, Object> formatting) {
        XWPFParagraph paragraph = document.createParagraph();
        applyParagraphFormatting(paragraph, formatting);
        appendMarkupAwareText(paragraph, valueOrNA(text), toRunStyle(formatting));
        return paragraph;
    }

    /**
     * Perl generate_bullet_text equivalent for DOCX.
     */
    public XWPFParagraph addBulletText(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setNumID(getOrCreateBulletNumId(document));
        XWPFRun run = paragraph.createRun();
        run.setFontFamily("Courier New");
        run.setText(valueOrNA(text));
        return paragraph;
    }

    /**
     * Perl generate_numbered_text equivalent for DOCX.
     */
    public XWPFParagraph addNumberedText(XWPFDocument document, String text, int number) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setFontFamily("Courier New");
        run.setText(String.format("%d. %s", number, valueOrNA(text)));
        return paragraph;
    }

    /**
     * Perl generate_hyperlink equivalent for DOCX. The URL is emitted inline for compatibility.
     */
    public XWPFParagraph addHyperlink(XWPFDocument document, String text, String url) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setColor("0000FF");
        run.setUnderline(UnderlinePatterns.SINGLE);
        run.setText(valueOrNA(text) + " (" + valueOrNA(url) + ")");
        return paragraph;
    }

    /**
     * Perl generate_header equivalent for DOCX.
     */
    public XWPFParagraph addHeader(XWPFDocument document, int depth, String text) {
        int headerSize = Math.max(12, 20 - Math.max(depth, 0));
        XWPFParagraph paragraph = document.createParagraph();
        RunStyle style = new RunStyle();
        style.bold = true;
        style.fontSize = headerSize;
        appendMarkupAwareText(paragraph, valueOrNA(text), style);
        return paragraph;
    }

    /**
     * Perl generate_image_from_file_basic equivalent for DOCX.
     */
    public void addImageFromFileBasic(XWPFDocument document, Path imagePath) throws IOException {
        addImageFromFile(document, imagePath, 400, 220);
    }

    /**
     * Perl generate_image equivalent for DOCX with bounded scaling.
     */
    public void addImageFromFile(XWPFDocument document, Path imagePath, int maxWidthPx, int maxHeightPx) throws IOException {
        if (imagePath == null || !Files.exists(imagePath)) {
            return;
        }
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        int type = resolvePictureType(imagePath);
        int widthPx = Math.max(1, maxWidthPx);
        int heightPx = Math.max(1, maxHeightPx);
        try (InputStream inputStream = Files.newInputStream(imagePath)) {
            run.addPicture(inputStream, type, imagePath.getFileName().toString(), Units.toEMU(widthPx), Units.toEMU(heightPx));
        } catch (POIXMLException e) {
            // Unsupported image format for current runtime: skip silently.
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Cannot add image to docx: " + e.getMessage(), e);
        }
    }

    public String buildFileName(Event event) {
        Long dbId = event != null ? event.getDbId() : null;
        String displayName = event != null ? event.getDisplayName() : null;
        String safeName = displayName == null || displayName.isBlank() ? "event" : sanitizeFileName(displayName);
        if (safeName.length() > 60) {
            safeName = safeName.substring(0, 60);
        }
        return String.format("event_%s_%s.docx", dbId == null ? "unknown" : dbId, safeName);
    }

    public String getDocxContentType() {
        return DOCX_CONTENT_TYPE;
    }

    private void createMetadataTable(XWPFDocument document, Event event) {
        // Stable identifier — safe; getStableIdentifier may return null on non-DB instance
        String stIdValue = "N/A";
        try {
            StableIdentifier stId = event.getStableIdentifier();
            if (stId != null && stId.getIdentifier() != null) {
                stIdValue = stId.getIdentifier();
            }
        } catch (Exception ignored) { }

        // schemaClass — getSchemaClass() may NPE on a plain domain object not backed by graph
        String schemaClassName = "N/A";
        try {
            schemaClassName = valueOrNA(event.getSchemaClass());
        } catch (Exception ignored) { }

        // Species
        String speciesValue = "N/A";
        try {
            List<Species> speciesList = event.getSpecies();
            if (speciesList != null && !speciesList.isEmpty()) {
                speciesValue = speciesList.stream()
                        .map(s -> s.getDisplayName() != null ? s.getDisplayName() : "?")
                        .collect(java.util.stream.Collectors.joining(", "));
            }
        } catch (Exception ignored) { }

        // Compartments
        String compartmentValue = "N/A";
        try {
            List<Compartment> compartments = event.getCompartment();
            if (compartments != null && !compartments.isEmpty()) {
                compartmentValue = compartments.stream()
                        .map(c -> c.getDisplayName() != null ? c.getDisplayName() : "?")
                        .collect(java.util.stream.Collectors.joining(", "));
            }
        } catch (Exception ignored) { }

        // releaseDate
        String releaseDate = "N/A";
        try { releaseDate = valueOrNA(event.getReleaseDate()); } catch (Exception ignored) { }

        XWPFTable table = document.createTable(8, 2);
        setRow(table, 0, "dbId",            String.valueOf(event.getDbId()));
        setRow(table, 1, "stableId",        stIdValue);
        setRow(table, 2, "displayName",     valueOrNA(event.getDisplayName()));
        setRow(table, 3, "schemaClassName", schemaClassName);
        setRow(table, 4, "species",         speciesValue);
        setRow(table, 5, "compartment",     compartmentValue);
        setRow(table, 6, "releaseDate",     releaseDate);
        setRow(table, 7, "exportedAt",      LocalDateTime.now().format(DATE_TIME_FORMATTER));
    }

    private void setRow(XWPFTable table, int rowIndex, String label, String value) {
        table.getRow(rowIndex).getCell(0).setText(label);
        table.getRow(rowIndex).getCell(1).setText(valueOrNA(value));
    }

    private String valueOrNA(String value) {
        return value == null || value.isBlank() ? "N/A" : value;
    }

    private void applyParagraphFormatting(XWPFParagraph paragraph, Map<String, Object> formatting) {
        if (formatting == null || formatting.isEmpty()) {
            return;
        }
        Object leftIndent = formatting.get("left_indent");
        if (leftIndent instanceof Number) {
            paragraph.setIndentationLeft(((Number) leftIndent).intValue() * 20);
        }
        Object rightIndent = formatting.get("right_indent");
        if (rightIndent instanceof Number) {
            paragraph.setIndentationRight(((Number) rightIndent).intValue() * 20);
        }
        Object firstLineIndent = formatting.get("first_line_indent");
        if (firstLineIndent instanceof Number) {
            paragraph.setIndentationFirstLine(((Number) firstLineIndent).intValue() * 20);
        }
        Object justify = formatting.get("justify");
        if (justify instanceof String) {
            switch (((String) justify).toLowerCase()) {
                case "center":
                    paragraph.setAlignment(ParagraphAlignment.CENTER);
                    break;
                case "right":
                    paragraph.setAlignment(ParagraphAlignment.RIGHT);
                    break;
                default:
                    paragraph.setAlignment(ParagraphAlignment.LEFT);
            }
        }
        if (Boolean.TRUE.equals(formatting.get("bind_next_para"))) {
            paragraph.setKeepNext(true);
        }
        if (Boolean.TRUE.equals(formatting.get("voodoo"))) {
            paragraph.setWordWrapped(true);
        }
    }

    private RunStyle toRunStyle(Map<String, Object> formatting) {
        RunStyle style = new RunStyle();
        if (formatting == null || formatting.isEmpty()) {
            return style;
        }
        if (Boolean.TRUE.equals(formatting.get("bold"))) {
            style.bold = true;
        }
        if (Boolean.TRUE.equals(formatting.get("italic"))) {
            style.italic = true;
        }
        if (Boolean.TRUE.equals(formatting.get("underline"))) {
            style.underline = UnderlinePatterns.SINGLE;
        }
        Object font = formatting.get("font");
        if (font instanceof String) {
            String fontValue = ((String) font).trim();
            if (!fontValue.isEmpty()) {
                style.fontFamily = fontValue;
            }
        }
        Object fontSize = formatting.get("font_size");
        if (fontSize instanceof Number) {
            style.fontSize = Math.max(1, ((Number) fontSize).intValue());
        }
        return style;
    }

    private void appendMarkupAwareText(XWPFParagraph paragraph, String text, RunStyle defaultStyle) {
        if (text == null || text.isEmpty()) {
            return;
        }
        List<RunStyle> styleStack = new ArrayList<>();
        styleStack.add(new RunStyle(defaultStyle));

        Matcher matcher = MARKUP_PATTERN.matcher(text);
        int current = 0;
        while (matcher.find()) {
            if (matcher.start() > current) {
                createStyledRun(paragraph, text.substring(current, matcher.start()), styleStack.get(styleStack.size() - 1));
            }

            String imgSource = matcher.group(3);
            if (imgSource != null) {
                createStyledRun(paragraph, "[image: " + imgSource + "]", styleStack.get(styleStack.size() - 1));
                current = matcher.end();
                continue;
            }

            boolean closing = "/".equals(matcher.group(1));
            String tag = matcher.group(2) == null ? "" : matcher.group(2).toLowerCase();
            if (closing) {
                if (styleStack.size() > 1) {
                    styleStack.remove(styleStack.size() - 1);
                }
            } else {
                RunStyle nextStyle = new RunStyle(styleStack.get(styleStack.size() - 1));
                if ("b".equals(tag)) {
                    nextStyle.bold = true;
                } else if ("i".equals(tag)) {
                    nextStyle.italic = true;
                } else if ("sub".equals(tag)) {
                    nextStyle.subscript = true;
                    nextStyle.superscript = false;
                } else if ("sup".equals(tag)) {
                    nextStyle.superscript = true;
                    nextStyle.subscript = false;
                } else if ("font".equals(tag)) {
                    nextStyle.color = "FF0000";
                }
                styleStack.add(nextStyle);
            }
            current = matcher.end();
        }
        if (current < text.length()) {
            createStyledRun(paragraph, text.substring(current), styleStack.get(styleStack.size() - 1));
        }
    }

    private void createStyledRun(XWPFParagraph paragraph, String content, RunStyle style) {
        if (content == null || content.isEmpty()) {
            return;
        }
        XWPFRun run = paragraph.createRun();
        run.setText(content);
        run.setBold(style.bold);
        run.setItalic(style.italic);
        if (style.underline != null) {
            run.setUnderline(style.underline);
        }
        if (style.color != null) {
            run.setColor(style.color);
        }
        if (style.fontFamily != null) {
            run.setFontFamily(style.fontFamily);
        }
        if (style.fontSize != null) {
            run.setFontSize(style.fontSize);
        }
        if (style.superscript) {
            run.setSubscript(VerticalAlign.SUPERSCRIPT);
        } else if (style.subscript) {
            run.setSubscript(VerticalAlign.SUBSCRIPT);
        }
    }

    private BigInteger getOrCreateBulletNumId(XWPFDocument document) {
        XWPFNumbering numbering = document.createNumbering();
        BigInteger abstractNumId = BigInteger.valueOf(1L);
        if (numbering.getAbstractNum(abstractNumId) == null) {
            CTAbstractNum cTAbstractNum = CTAbstractNum.Factory.newInstance();
            cTAbstractNum.setAbstractNumId(abstractNumId);
            CTLvl cTLvl = cTAbstractNum.addNewLvl();
            cTLvl.setIlvl(BigInteger.ZERO);
            cTLvl.addNewNumFmt().setVal(STNumberFormat.BULLET);
            cTLvl.addNewLvlText().setVal("\\u2022");
            cTLvl.addNewStart().setVal(BigInteger.ONE);
            XWPFAbstractNum abstractNum = new XWPFAbstractNum(cTAbstractNum);
            abstractNumId = numbering.addAbstractNum(abstractNum);
        }

        for (BigInteger numId = BigInteger.ONE; numId.compareTo(BigInteger.valueOf(32)) < 0; numId = numId.add(BigInteger.ONE)) {
            if (numbering.getNum(numId) != null) {
                continue;
            }
            numbering.addNum(abstractNumId, numId);
            return numId;
        }
        return numbering.addNum(abstractNumId);
    }

    private int resolvePictureType(Path imagePath) {
        String fileName = imagePath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".png")) return XWPFDocument.PICTURE_TYPE_PNG;
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) return XWPFDocument.PICTURE_TYPE_JPEG;
        if (fileName.endsWith(".gif")) return XWPFDocument.PICTURE_TYPE_GIF;
        if (fileName.endsWith(".bmp")) return XWPFDocument.PICTURE_TYPE_BMP;
        return XWPFDocument.PICTURE_TYPE_PNG;
    }

    private String sanitizeFileName(String input) {
        return input.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static class RunStyle {
        boolean bold;
        boolean italic;
        boolean superscript;
        boolean subscript;
        UnderlinePatterns underline;
        String color;
        String fontFamily;
        Integer fontSize;

        RunStyle() {
        }

        RunStyle(RunStyle source) {
            this.bold = source.bold;
            this.italic = source.italic;
            this.superscript = source.superscript;
            this.subscript = source.subscript;
            this.underline = source.underline;
            this.color = source.color;
            this.fontFamily = source.fontFamily;
            this.fontSize = source.fontSize;
        }
    }
}

