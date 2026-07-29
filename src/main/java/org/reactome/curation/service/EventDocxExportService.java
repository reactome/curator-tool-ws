package org.reactome.curation.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.awt.image.BufferedImage;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.VerticalAlign;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.reactome.server.graph.domain.model.*;
import org.reactome.server.graph.service.AdvancedDatabaseObjectService;
import org.reactome.server.graph.service.DatabaseObjectService;
import org.reactome.server.graph.service.helper.RelationshipDirection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EventDocxExportService {
    @Autowired
    private CurationService service;

    @Autowired(required = false)
    private AdvancedDatabaseObjectService advancedDatabaseObjectService;
    @Autowired(required = false)
    private DatabaseObjectService databaseObjectService;

    @Value("${figure_root_dir:}")
    private String figureRootPath;

    @Autowired(required = false)
    private ReactionImageRendererService reactionImageRendererService;

    private static final String DOCX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int REACTION_IMAGE_WIDTH_PX = 420;
    private static final int REACTION_IMAGE_HEIGHT_PX = 173;

    private static final String FONT = "Times New Roman";
    /**
     * Export an Event as a rich DOCX document, following the same structure as the
     * Perl GenerateTextRTF.pm:
     * <p>
     * 1. Document title  (generate_prolog / header)
     * 2. Metadata table  (dbId, stable id, schema class, species, compartments)
     * 3. Summation(s)    (generate_paragraph with markup)
     * 4. Authorship      (generate_header + paragraph)
     * 5. Literature references (generate_hyperlink style)
     */
    public byte[] exportEventDocx(Event event) throws IOException {
        if (event == null) {
            throw new IllegalArgumentException("Event cannot be null.");
        }

        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            // --- 1. Title (Perl: generate_prolog + generate_header depth=0) ---
            addHeader(document, 0, "Reactome Event Export");

            // --- 2. Metadata table ---
//            createMetadataTable(document, event);
//
//            // Blank line after metadata table
//            document.createParagraph();

            // Batch-preload the whole reachable event tree (and everything it references) up
            // front, instead of the recursive walk below calling findById() once per event,
            // per InstanceEdit, and per Publication - see buildExportContext(). This also
            // renders every reaction's image concurrently up front (see
            // renderReactionImagesInParallel()), since rendering was measured to dominate the
            // remaining export time and each reaction's image is independent of every other's.
            ExportContext context = buildExportContext(event);

            // Recursively render this event and all nested events via hasEvent/getHasEvent.
            writeEventTree(document, event, 0, "1", new LinkedHashSet<>(), context);

            document.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private void writeEventTree(XWPFDocument document, Event event, int depth, String numbering,
                                Set<String> visitedKeys, ExportContext context) {
        Event resolved = resolveEventForExport(event, context);
        if (resolved == null) {
            return;
        }

        String visitKey = buildEventVisitKey(resolved);
        if (!visitedKeys.add(visitKey)) {
            return;
        }

        int eventHeaderDepth = Math.min(5, 1 + depth);
        String eventHeader = numbering + " " + valueOrNA(resolved.getDisplayName()) + " (" + getEventTypeLabel(resolved) + ")";
        addHeader(document, eventHeaderDepth, eventHeader);
        addPathwayBrowserLink(document, resolved);

        writeEventSections(document, resolved, depth, context);

        List<Event> containedEvents = getContainedEvents(resolved);
        int childIndex = 1;
        for (Event child : containedEvents) {
            writeEventTree(document, child, depth + 1, numbering + "." + childIndex, visitedKeys, context);
            childIndex++;
        }
    }

    private String getEventTypeLabel(Event event) {
        if (event == null) {
            return "Event";
        }
        try {
            String schemaClass = event.getSchemaClass();
            if (hasText(schemaClass)) {
                return schemaClass;
            }
        } catch (Exception ignored) {
        }
        String simpleName = event.getClass().getSimpleName();
        return hasText(simpleName) ? simpleName : "Event";
    }

    private void writeEventSections(XWPFDocument document, Event event, int depth, ExportContext context) {
        int sectionHeaderDepth = Math.min(6, 2 + depth);

        // Authorship
        try {
            List<InstanceEdit> authored = event.getAuthored();
            if (authored != null && !authored.isEmpty()) {
                addHeader(document, sectionHeaderDepth, "Authored");
                for (InstanceEdit ie : authored) {
                    String authorLine = formatInstanceEditLine(ie, context);
                    if (authorLine != null) addBulletText(document, authorLine);
                }
            }
        } catch (Exception ignored) {
        }

        try {
            List<InstanceEdit> edited = event.getEdited();
            if (edited != null && !edited.isEmpty()) {
                addHeader(document, sectionHeaderDepth, "Edited");
                for (InstanceEdit ie : edited) {
                    String authorLine = formatInstanceEditLine(ie, context);
                    if (authorLine != null) addBulletText(document, authorLine);
                }
            }
        } catch (Exception ignored) {
        }

        try {
            List<InstanceEdit> reviewed = event.getReviewed();
            if (reviewed != null && !reviewed.isEmpty()) {
                addHeader(document, sectionHeaderDepth, "Reviewed");
                for (InstanceEdit ie : reviewed) {
                    String authorLine = formatInstanceEditLine(ie, context);
                    if (authorLine != null) addBulletText(document, authorLine);
                }
            }
        } catch (Exception ignored) {
        }

        // Summation
        try {
            List<Summation> summations = event.getSummation();
            if (summations != null && !summations.isEmpty()) {
                addHeader(document, sectionHeaderDepth, "Summation");
                for (Summation summation : summations) {
                    String text = summation.getText();
                    if (text != null && !text.isBlank()) {
                        Map<String, Object> fmt = Map.of("font", FONT, "font_size", 11);
                        addParagraph(document, text, fmt);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // If this is a reaction-like event, render the reaction image right after summation.
        boolean reactionImageAdded = false;
        try {
            if (isReactionEvent(event) && reactionImageRendererService != null) {
                byte[] renderedReaction = lookupOrRenderReactionImage(event, context);
                if (renderedReaction != null && renderedReaction.length > 0) {
                    String fileName = "reaction_" + (event.getDbId() == null ? "unknown" : event.getDbId()) + ".png";
                    addImageFromBytes(document, renderedReaction, fileName, REACTION_IMAGE_WIDTH_PX, REACTION_IMAGE_HEIGHT_PX, XWPFDocument.PICTURE_TYPE_PNG);
                    reactionImageAdded = true;
                }
            }
        } catch (Exception ignored) {
        }

        // Figures
        try {
            boolean figureAdded = false;
            List<Figure> figures = event.getFigure();
            if (figures != null && !figures.isEmpty()) {
                for (Figure figure : figures) {
                    String fileName = figure.getDisplayName();
                    if (fileName != null && fileName.endsWith(".svg")) {
                        int index = fileName.lastIndexOf(".svg");
                        fileName = fileName.substring(0, index) + ".png";
                    }
                    Path figurePath = resolveFigurePath(fileName);
                    if (figurePath != null && Files.exists(figurePath)) {
                        addImageFromFileBasic(document, figurePath);
                        figureAdded = true;
                        break;
                    }
                }
            }

            // Fallback for non-reaction events only (reaction image is handled above).
            if (!figureAdded && !reactionImageAdded && !isReactionEvent(event) && reactionImageRendererService != null) {
                byte[] renderedReaction = lookupOrRenderReactionImage(event, context);
                if (renderedReaction != null && renderedReaction.length > 0) {
                    String fileName = "reaction_" + (event.getDbId() == null ? "unknown" : event.getDbId()) + ".png";
                    addImageFromBytes(document, renderedReaction, fileName, REACTION_IMAGE_WIDTH_PX, REACTION_IMAGE_HEIGHT_PX, XWPFDocument.PICTURE_TYPE_PNG);
                }
            }
        } catch (Exception ignored) {
        }

        // Literature references
        try {
            List<Publication> refs = event.getLiteratureReference();
            if (refs != null && !refs.isEmpty()) {
                addHeader(document, sectionHeaderDepth, "Literature References");
                int num = 1;
                for (Publication pub : refs) {
                    Publication resolvedPublication = pub;
                    try {
                        if (pub != null && pub.getDbId() != null) {
                            Publication cached = context.publications.get(pub.getDbId());
                            if (cached != null) {
                                resolvedPublication = cached;
                            } else if (service != null) {
                                Object loaded = service.findById(pub.getDbId());
                                if (loaded instanceof Publication) {
                                    resolvedPublication = (Publication) loaded;
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }

                    String citation = formatPublication(resolvedPublication);
                    String url = resolvePublicationUrl(resolvedPublication);
                    if (url != null) {
                        addHyperlink(document, citation, url);
                    } else {
                        addNumberedText(document, citation, num);
                    }
                    num++;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private Event resolveEventForExport(Event event, ExportContext context) {
        if (event == null) {
            return null;
        }
        if (event.getDbId() != null) {
            Event cached = context.events.get(event.getDbId());
            if (cached != null) {
                return cached;
            }
        }
        // Fallback for anything the tree preload didn't capture (shouldn't normally happen).
        try {
            if (service != null && event.getDbId() != null) {
                Object loaded = service.findById(event.getDbId());
                if (loaded instanceof Event) {
                    return (Event) loaded;
                }
            }
        } catch (Exception ignored) {
        }
        return event;
    }

    private String buildEventVisitKey(Event event) {
        if (event == null) {
            return "NULL";
        }
        try {
            if (event.getDbId() != null) {
                return "DB:" + event.getDbId();
            }
        } catch (Exception ignored) {
        }
        String stId = getStableIdentifierWithoutVersion(event);
        if (hasText(stId)) {
            return "ST:" + stId;
        }
        return "NAME:" + valueOrNA(event.getDisplayName());
    }

    @SuppressWarnings("unchecked")
    private List<Event> getContainedEvents(Event event) {
        if (event == null) {
            return Collections.emptyList();
        }

        Object value = invokeGetter(event, "getHasEvent");
        if (value == null) {
            value = invokeGetter(event, "hasEvent");
        }
        if (!(value instanceof List<?>)) {
            return Collections.emptyList();
        }

        List<Event> events = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (item instanceof Event) {
                events.add((Event) item);
            }
        }
        return events;
    }

    // -------------------------------------------------------------------------
    // Batch preload: replaces the old one-findById()-per-entity pattern in the
    // recursive tree walk with a handful of batched queries. Measured on a
    // 143-event pathway: individual findById() calls took ~1030ms combined
    // (events + InstanceEdits + Publications); the batched equivalent took
    // ~225ms, with no redundant re-fetching of entities referenced by more than
    // one event (e.g. a shared reviewer, or a paper cited by several reactions),
    // which the old per-call approach did on every occurrence.
    // -------------------------------------------------------------------------

    private static class ExportContext {
        final Map<Long, Event> events;
        final Map<Long, InstanceEdit> instanceEdits;
        final Map<Long, Publication> publications;
        final Map<Long, byte[]> reactionImages;

        ExportContext(Map<Long, Event> events, Map<Long, InstanceEdit> instanceEdits, Map<Long, Publication> publications,
                     Map<Long, byte[]> reactionImages) {
            this.events = events;
            this.instanceEdits = instanceEdits;
            this.publications = publications;
            this.reactionImages = reactionImages;
        }
    }

    private ExportContext buildExportContext(Event rootEvent) {
        Map<Long, Event> events = preloadEventTree(rootEvent);
        Map<Long, InstanceEdit> instanceEdits = batchLoadInstanceEdits(collectInstanceEditDbIds(events));
        Map<Long, Publication> publications = batchLoadPublications(collectPublicationDbIds(events));
        Map<Long, byte[]> reactionImages = renderReactionImagesInParallel(events);
        return new ExportContext(events, instanceEdits, publications, reactionImages);
    }

    /**
     * Renders every reaction's image concurrently up front - see
     * ReactionImageRendererService.renderReactionImagesInParallel() for the concurrency model
     * and the one known thread-safety hazard (reaction-exporter's compartment/GO tree cache)
     * that method works around.
     */
    private Map<Long, byte[]> renderReactionImagesInParallel(Map<Long, Event> events) {
        if (reactionImageRendererService == null) {
            return Collections.emptyMap();
        }
        List<ReactionLikeEvent> reactions = new ArrayList<>();
        for (Event e : events.values()) {
            if (e instanceof ReactionLikeEvent) {
                reactions.add((ReactionLikeEvent) e);
            }
        }
        return reactionImageRendererService.renderReactionImagesInParallel(reactions);
    }

    /**
     * Looks up an already-rendered reaction image from the parallel pre-render pass; falls back
     * to rendering it inline if it's missing for some reason (e.g. dbId is null, or the event
     * wasn't captured by the tree preload - shouldn't normally happen).
     */
    private byte[] lookupOrRenderReactionImage(Event event, ExportContext context) {
        if (event != null && event.getDbId() != null) {
            byte[] cached = context.reactionImages.get(event.getDbId());
            if (cached != null) {
                return cached;
            }
        }
        return reactionImageRendererService.renderReactionImage(event);
    }

    /**
     * Batch-loads the whole reachable event tree level by level (breadth-first over hasEvent),
     * instead of resolveEventForExport() calling findById() once per event during the recursive
     * walk. Each round fetches every not-yet-loaded dbId in the current frontier in one or two
     * queries (see batchLoadEvents()), discovers the next level's dbIds from the newly-loaded
     * events' hasEvent lists, and repeats until no new events are found.
     */
    private Map<Long, Event> preloadEventTree(Event rootEvent) {
        Map<Long, Event> loaded = new HashMap<>();
        if (rootEvent == null || rootEvent.getDbId() == null
                || advancedDatabaseObjectService == null || databaseObjectService == null) {
            return loaded;
        }

        Set<Long> frontier = new HashSet<>();
        frontier.add(rootEvent.getDbId());

        while (!frontier.isEmpty()) {
            Set<Long> toFetch = new HashSet<>();
            for (Long id : frontier) {
                if (!loaded.containsKey(id)) toFetch.add(id);
            }
            if (toFetch.isEmpty()) break;

            Map<Long, Event> batch = batchLoadEvents(toFetch);
            loaded.putAll(batch);

            Set<Long> nextFrontier = new HashSet<>();
            for (Event e : batch.values()) {
                for (Event child : getContainedEvents(e)) {
                    if (child != null && child.getDbId() != null && !loaded.containsKey(child.getDbId())) {
                        nextFrontier.add(child.getDbId());
                    }
                }
            }
            frontier = nextFrontier;
        }
        return loaded;
    }

    /**
     * Batch-loads one level of events. findByDbIds() only supports one relationship direction
     * per call, so this issues two batched calls - one OUTGOING (hasEvent/summation/
     * literatureReference/figure/stableIdentifier) and one INCOMING (authored/edited/reviewed) -
     * and merges the INCOMING results onto the same objects the OUTGOING call produced. A
     * shallow load goes first to guarantee one entry per dbId even for an event with none of the
     * relationships requested below (findByDbIds's MATCH is required, not optional, so such an
     * event would otherwise be silently missing from the result).
     *
     * stableIdentifier is included specifically because addPathwayBrowserLink() needs it to
     * build the PathwayBrowser URL - it's easy to miss since it's not read anywhere else in the
     * export, unlike the old resolveEventForExport()'s full depth-1 findById(), which pulled in
     * every relationship (including this one) whether the export used it or not.
     */
    private Map<Long, Event> batchLoadEvents(Set<Long> dbIds) {
        Map<Long, Event> result = new HashMap<>();
        if (dbIds.isEmpty()) return result;

        for (Object obj : databaseObjectService.findByIdsNoRelations(dbIds)) {
            if (obj instanceof Event) {
                Event e = (Event) obj;
                result.put(e.getDbId(), e);
            }
        }

        Collection<DatabaseObject> outgoing = advancedDatabaseObjectService.findByDbIds(
                dbIds, RelationshipDirection.OUTGOING, "hasEvent", "summation", "literatureReference", "figure", "stableIdentifier");
        for (DatabaseObject obj : outgoing) {
            if (obj instanceof Event) {
                Event e = (Event) obj;
                result.put(e.getDbId(), e);
            }
        }

        Collection<DatabaseObject> incoming = advancedDatabaseObjectService.findByDbIds(
                dbIds, RelationshipDirection.INCOMING, "authored", "edited", "reviewed");
        for (DatabaseObject obj : incoming) {
            if (!(obj instanceof Event)) continue;
            Event enriched = (Event) obj;
            Event target = result.get(enriched.getDbId());
            if (target == null) {
                result.put(enriched.getDbId(), enriched);
                continue;
            }
            target.setAuthored(enriched.getAuthored());
            target.setEdited(enriched.getEdited());
            target.setReviewed(enriched.getReviewed());
        }

        return result;
    }

    private Map<Long, InstanceEdit> batchLoadInstanceEdits(Set<Long> dbIds) {
        Map<Long, InstanceEdit> result = new HashMap<>();
        if (dbIds.isEmpty() || advancedDatabaseObjectService == null || databaseObjectService == null) {
            return result;
        }
        for (Object obj : databaseObjectService.findByIdsNoRelations(dbIds)) {
            if (obj instanceof InstanceEdit) {
                InstanceEdit ie = (InstanceEdit) obj;
                result.put(ie.getDbId(), ie);
            }
        }
        Collection<DatabaseObject> enriched = advancedDatabaseObjectService.findByDbIds(dbIds, RelationshipDirection.INCOMING, "author");
        for (DatabaseObject obj : enriched) {
            if (obj instanceof InstanceEdit) {
                InstanceEdit ie = (InstanceEdit) obj;
                result.put(ie.getDbId(), ie);
            }
        }
        return result;
    }

    private Map<Long, Publication> batchLoadPublications(Set<Long> dbIds) {
        Map<Long, Publication> result = new HashMap<>();
        if (dbIds.isEmpty() || advancedDatabaseObjectService == null || databaseObjectService == null) {
            return result;
        }
        for (Object obj : databaseObjectService.findByIdsNoRelations(dbIds)) {
            if (obj instanceof Publication) {
                Publication pub = (Publication) obj;
                result.put(pub.getDbId(), pub);
            }
        }
        Collection<DatabaseObject> enriched = advancedDatabaseObjectService.findByDbIds(dbIds, RelationshipDirection.INCOMING, "author");
        for (DatabaseObject obj : enriched) {
            if (obj instanceof Publication) {
                Publication pub = (Publication) obj;
                result.put(pub.getDbId(), pub);
            }
        }
        return result;
    }

    private Set<Long> collectInstanceEditDbIds(Map<Long, Event> events) {
        Set<Long> ids = new HashSet<>();
        for (Event e : events.values()) {
            try {
                addDbIds(ids, e.getAuthored());
                addDbIds(ids, e.getEdited());
                addDbIds(ids, e.getReviewed());
            } catch (Exception ignored) {
            }
        }
        return ids;
    }

    private Set<Long> collectPublicationDbIds(Map<Long, Event> events) {
        Set<Long> ids = new HashSet<>();
        for (Event e : events.values()) {
            try {
                addDbIds(ids, e.getLiteratureReference());
            } catch (Exception ignored) {
            }
        }
        return ids;
    }

    private void addDbIds(Set<Long> ids, List<? extends DatabaseObject> objects) {
        if (objects == null) return;
        for (DatabaseObject obj : objects) {
            if (obj != null && obj.getDbId() != null) {
                ids.add(obj.getDbId());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private document section helpers
    // -------------------------------------------------------------------------

    private String formatInstanceEditLine(InstanceEdit ie, ExportContext context) {
        if (ie == null) return null;
        // ie is a shell instance only - look it up in the batch-preloaded map first.
        if (ie.getAuthor() == null || ie.getAuthor().isEmpty()) {
            InstanceEdit cached = ie.getDbId() != null ? context.instanceEdits.get(ie.getDbId()) : null;
            ie = cached != null ? cached : (InstanceEdit) service.findById(ie.getDbId());
        }
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

        List<String> citationParts = new ArrayList<>();

        String authors = formatPublicationAuthors(pub);
        if (hasText(authors)) {
            citationParts.add(authors);
        }

        String title = normalizeText(pub.getTitle());
        if (hasText(title)) {
            citationParts.add(title);
        }

        String container = resolvePublicationContainer(pub);
        String italicContainer = hasText(container) ? "<i>" + container + "</i>" : null;
        String volume = resolvePublicationVolume(pub);
        String year = resolvePublicationYear(pub);
        String pages = resolvePublicationPages(pub);

        StringBuilder sourcePart = new StringBuilder();
        if (hasText(italicContainer)) {
            sourcePart.append(italicContainer);
        }
        if (hasText(volume)) {
            if (sourcePart.length() > 0) sourcePart.append(" ");
            sourcePart.append(volume);
        }
        if (hasText(year)) {
            if (sourcePart.length() > 0) sourcePart.append(" ");
            sourcePart.append("(").append(year).append(")");
        }
        if (hasText(pages)) {
            if (sourcePart.length() > 0) {
                sourcePart.append(": ").append(pages);
            } else {
                sourcePart.append("p. ").append(pages);
            }
        }
        if (sourcePart.length() > 0) {
            citationParts.add(sourcePart.toString());
        }

        if (citationParts.isEmpty()) {
            return "N/A";
        }
        return String.join(". ", citationParts) + ".";
    }

    private String formatPublicationAuthors(Publication pub) {
        Object authorsObject = invokeGetter(pub, "getAuthor");
        if (!(authorsObject instanceof List<?>)) {
            authorsObject = invokeGetter(pub, "getAuthors");
        }
        if (!(authorsObject instanceof List<?>)) {
            return null;
        }

        List<String> names = new ArrayList<>();
        for (Object author : (List<?>) authorsObject) {
            if (author instanceof Person) {
                String personName = formatPersonName((Person) author);
                if (hasText(personName)) {
                    names.add(personName);
                }
            } else if (author != null) {
                String value = normalizeText(author.toString());
                if (hasText(value)) {
                    names.add(value);
                }
            }
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    private String resolvePublicationContainer(Publication pub) {
        if (pub instanceof LiteratureReference) {
            LiteratureReference lr = (LiteratureReference) pub;
            String journal = normalizeText(lr.getJournal());
            if (hasText(journal)) {
                return journal;
            }
        }

        String bookTitle = normalizeText(invokeGetter(pub, "getBookTitle"));
        if (hasText(bookTitle)) {
            return bookTitle;
        }

        return normalizeText(invokeGetter(pub, "getBook"));
    }

    private String resolvePublicationVolume(Publication pub) {
        if (pub instanceof LiteratureReference) {
            LiteratureReference lr = (LiteratureReference) pub;
            String volume = normalizeText(lr.getVolume());
            if (hasText(volume)) {
                return volume;
            }
        }
        return normalizeText(invokeGetter(pub, "getVolume"));
    }

    private String resolvePublicationYear(Publication pub) {
        if (pub instanceof LiteratureReference) {
            LiteratureReference lr = (LiteratureReference) pub;
            String year = normalizeText(lr.getYear());
            if (hasText(year)) {
                return year;
            }
        }
        return normalizeText(invokeGetter(pub, "getYear"));
    }

    private String resolvePublicationPages(Publication pub) {
        if (pub instanceof LiteratureReference) {
            LiteratureReference lr = (LiteratureReference) pub;
            String pages = normalizeText(lr.getPages());
            if (hasText(pages)) {
                return pages;
            }
        }
        String pages = normalizeText(invokeGetter(pub, "getPages"));
        if (hasText(pages)) {
            return pages;
        }
        return normalizeText(invokeGetter(pub, "getPage"));
    }

    private String resolvePublicationUrl(Publication pub) {
        if (pub instanceof LiteratureReference) {
            LiteratureReference lr = (LiteratureReference) pub;
            if (lr.getUrl() != null && !lr.getUrl().isBlank()) return lr.getUrl();
            if (lr.getPubMedIdentifier() != null) {
                return "https://pubmed.ncbi.nlm.nih.gov/" + lr.getPubMedIdentifier();
            }
        }
        return null;
    }

    private Object invokeGetter(Object target, String getterName) {
        if (target == null || getterName == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(getterName).invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeText(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.replaceAll("[.\\s]+$", "");
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
     * <p>
     * Supported keys: font, font_size, bold, italic, underline, left_indent,
     * right_indent, first_line_indent, justify, bind_next_para, voodoo.
     */
    public XWPFParagraph addParagraph(XWPFDocument document, String text, Map<String, Object> formatting) {
        XWPFParagraph paragraph = document.createParagraph();
        applyParagraphFormatting(paragraph, formatting);
        return appendMarkupAwareText(document, paragraph, formatting, valueOrNA(text), toRunStyle(formatting));
    }

    /**
     * Perl generate_bullet_text equivalent for DOCX.
     */
    public XWPFParagraph addBulletText(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setNumID(getOrCreateBulletNumId(document));
        XWPFRun run = paragraph.createRun();
        run.setFontFamily(FONT);
        run.setText(valueOrNA(text));
        return paragraph;
    }

    /**
     * Perl generate_numbered_text equivalent for DOCX.
     */
    public XWPFParagraph addNumberedText(XWPFDocument document, String text, int number) {
        XWPFParagraph paragraph = document.createParagraph();
        RunStyle style = new RunStyle();
        return appendMarkupAwareText(document, paragraph, null, String.format("%d. %s", number, valueOrNA(text)), style);
    }

    /**
     * Perl generate_hyperlink equivalent for DOCX. The URL is emitted inline for compatibility.
     */
    public XWPFParagraph addHyperlink(XWPFDocument document, String text, String url) {
        XWPFParagraph paragraph = document.createParagraph();

        // Keep citation text plain (supports markup like <i> for journal/book title).
        paragraph = appendMarkupAwareText(document, paragraph, null, valueOrNA(text) + " (", new RunStyle());

        String safeUrl = valueOrNA(url);
        if (hasText(url)) {
            // Only the URL itself is an active hyperlink.
            XWPFHyperlinkRun hyperlinkRun = paragraph.createHyperlinkRun(url);
            hyperlinkRun.setFontFamily(FONT);
            hyperlinkRun.setColor("0000FF");
            hyperlinkRun.setUnderline(UnderlinePatterns.SINGLE);
            hyperlinkRun.setText(safeUrl);
        } else {
            XWPFRun urlRun = paragraph.createRun();
            urlRun.setFontFamily(FONT);
            urlRun.setText(safeUrl);
        }

        XWPFRun closeRun = paragraph.createRun();
        closeRun.setFontFamily(FONT);
        closeRun.setText(")");
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
        return appendMarkupAwareText(document, paragraph, null, valueOrNA(text), style);
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
        paragraph.setAlignment(ParagraphAlignment.CENTER);
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

    private void addImageFromBytes(XWPFDocument document,
                                   byte[] imageBytes,
                                   String fileName,
                                   int widthPx,
                                   int heightPx,
                                   int pictureType) throws IOException {
        if (imageBytes == null || imageBytes.length == 0) {
            return;
        }
        int[] fittedSize = fitImageInBounds(imageBytes, widthPx, heightPx);
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = paragraph.createRun();
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(imageBytes)) {
            run.addPicture(inputStream,
                    pictureType,
                    fileName == null ? "image.png" : fileName,
                    Units.toEMU(fittedSize[0]),
                    Units.toEMU(fittedSize[1]));
        } catch (POIXMLException e) {
            // Skip unsupported/invalid images to keep export robust.
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Cannot add in-memory image to docx: " + e.getMessage(), e);
        }
    }

    private int[] fitImageInBounds(byte[] imageBytes, int maxWidthPx, int maxHeightPx) {
        int safeMaxWidth = Math.max(1, maxWidthPx);
        int safeMaxHeight = Math.max(1, maxHeightPx);
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(imageBytes)) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return new int[]{safeMaxWidth, safeMaxHeight};
            }
            double widthRatio = (double) safeMaxWidth / image.getWidth();
            double heightRatio = (double) safeMaxHeight / image.getHeight();
            double scale = Math.min(widthRatio, heightRatio);
            int scaledWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int scaledHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
            return new int[]{scaledWidth, scaledHeight};
        } catch (Exception ignored) {
            return new int[]{safeMaxWidth, safeMaxHeight};
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
        } catch (Exception ignored) {
        }

        // schemaClass — getSchemaClass() may NPE on a plain domain object not backed by graph
        String schemaClassName = "N/A";
        try {
            schemaClassName = valueOrNA(event.getSchemaClass());
        } catch (Exception ignored) {
        }

        // Species
        String speciesValue = "N/A";
        try {
            List<Species> speciesList = event.getSpecies();
            if (speciesList != null && !speciesList.isEmpty()) {
                speciesValue = speciesList.stream()
                        .map(s -> s.getDisplayName() != null ? s.getDisplayName() : "?")
                        .collect(java.util.stream.Collectors.joining(", "));
            }
        } catch (Exception ignored) {
        }

        // Compartments
        String compartmentValue = "N/A";
        try {
            List<Compartment> compartments = event.getCompartment();
            if (compartments != null && !compartments.isEmpty()) {
                compartmentValue = compartments.stream()
                        .map(c -> c.getDisplayName() != null ? c.getDisplayName() : "?")
                        .collect(java.util.stream.Collectors.joining(", "));
            }
        } catch (Exception ignored) {
        }

        // releaseDate
        String releaseDate = "N/A";
        try {
            releaseDate = valueOrNA(event.getReleaseDate());
        } catch (Exception ignored) {
        }

        XWPFTable table = document.createTable(8, 2);
        setRow(table, 0, "dbId", String.valueOf(event.getDbId()));
        setRow(table, 1, "stableId", stIdValue);
        setRow(table, 2, "displayName", valueOrNA(event.getDisplayName()));
        setRow(table, 3, "schemaClassName", schemaClassName);
        setRow(table, 4, "species", speciesValue);
        setRow(table, 5, "compartment", compartmentValue);
        setRow(table, 6, "releaseDate", releaseDate);
        setRow(table, 7, "exportedAt", LocalDateTime.now().format(DATE_TIME_FORMATTER));
    }

    private void setRow(XWPFTable table, int rowIndex, String label, String value) {
        table.getRow(rowIndex).getCell(0).removeParagraph(0);
        XWPFParagraph labelParagraph = table.getRow(rowIndex).getCell(0).addParagraph();
        XWPFRun labelRun = labelParagraph.createRun();
        labelRun.setFontFamily(FONT);
        labelRun.setText(valueOrNA(label));

        table.getRow(rowIndex).getCell(1).removeParagraph(0);
        XWPFParagraph valueParagraph = table.getRow(rowIndex).getCell(1).addParagraph();
        XWPFRun valueRun = valueParagraph.createRun();
        valueRun.setFontFamily(FONT);
        valueRun.setText(valueOrNA(value));
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

    /**
     * Parses text as an HTML fragment (curator summations/notes routinely contain
     * &lt;p&gt;/&lt;font&gt;/&lt;span&gt; markup and entities like &amp;#160; - both from the legacy
     * curation tool's own conventions and from pasting straight out of MS Word) and appends it to
     * paragraph, translating recognized tags to DOCX formatting instead of dumping raw markup as
     * text. Uses Jsoup rather than a hand-rolled regex because curator HTML is routinely malformed
     * (unbalanced tags, stray closing tags) and Jsoup's tree builder repairs that the same way a
     * browser would, and it decodes entities for free.
     * <p>
     * &lt;p&gt; tags start a new paragraph (reusing paragraphFormatting, if any, so continuation
     * paragraphs keep the same indent/justification as the first) rather than being emitted as
     * literal text - so this can grow the document by more than one paragraph. Returns whichever
     * paragraph ends up holding the tail of the text, since callers that keep appending after this
     * call (e.g. addHyperlink's trailing " (url)") need to target that one, not the paragraph they
     * originally created.
     */
    private XWPFParagraph appendMarkupAwareText(XWPFDocument document, XWPFParagraph paragraph,
                                                 Map<String, Object> paragraphFormatting,
                                                 String text, RunStyle defaultStyle) {
        if (text == null || text.isEmpty()) {
            return paragraph;
        }
        org.jsoup.nodes.Document parsed = Jsoup.parseBodyFragment(text);
        parsed.outputSettings().prettyPrint(false);
        MarkupCursor cursor = new MarkupCursor(document, paragraph, paragraphFormatting);
        appendChildren(parsed.body(), cursor, defaultStyle);
        return cursor.paragraph;
    }

    /**
     * Mutable walk state for appendMarkupAwareText(). A block-level tag (&lt;p&gt;, &lt;li&gt;, ...)
     * only requests a paragraph break via pendingParagraphBreak - the break is realized lazily, in
     * ensureParagraphReady(), right before the next piece of actual content is written. Realizing it
     * eagerly at the tag boundary itself would leave a trailing blank paragraph after every
     * &lt;p&gt;...&lt;/p&gt;-wrapped summation (the overwhelmingly common case) since closing the
     * outermost &lt;p&gt; would create a paragraph for content that may never come.
     */
    private static class MarkupCursor {
        final XWPFDocument document;
        final Map<String, Object> paragraphFormatting;
        XWPFParagraph paragraph;
        boolean paragraphHasContent;
        boolean pendingParagraphBreak;

        MarkupCursor(XWPFDocument document, XWPFParagraph paragraph, Map<String, Object> paragraphFormatting) {
            this.document = document;
            this.paragraph = paragraph;
            this.paragraphFormatting = paragraphFormatting;
        }
    }

    private void requestParagraphBreak(MarkupCursor cursor) {
        cursor.pendingParagraphBreak = true;
    }

    private void ensureParagraphReady(MarkupCursor cursor) {
        if (cursor.pendingParagraphBreak && cursor.paragraphHasContent) {
            cursor.paragraph = cursor.document.createParagraph();
            applyParagraphFormatting(cursor.paragraph, cursor.paragraphFormatting);
            cursor.paragraphHasContent = false;
        }
        cursor.pendingParagraphBreak = false;
    }

    private void appendChildren(Element element, MarkupCursor cursor, RunStyle style) {
        for (Node child : element.childNodes()) {
            appendNode(child, cursor, style);
        }
    }

    private void appendNode(Node node, MarkupCursor cursor, RunStyle style) {
        if (node instanceof TextNode) {
            String text = ((TextNode) node).text();
            if (!text.isEmpty()) {
                ensureParagraphReady(cursor);
                createStyledRun(cursor.paragraph, text, style);
                cursor.paragraphHasContent = true;
            }
            return;
        }
        if (!(node instanceof Element)) {
            return; // Comments, doctype declarations, etc. - nothing to render.
        }

        Element element = (Element) node;
        switch (element.tagName()) {
            case "p":
            case "div":
                requestParagraphBreak(cursor);
                appendChildren(element, cursor, style);
                requestParagraphBreak(cursor);
                break;
            case "br":
                ensureParagraphReady(cursor);
                cursor.paragraph.createRun().addBreak();
                cursor.paragraphHasContent = true;
                break;
            case "li": {
                requestParagraphBreak(cursor);
                ensureParagraphReady(cursor);
                createStyledRun(cursor.paragraph, "• ", style);
                cursor.paragraphHasContent = true;
                appendChildren(element, cursor, style);
                requestParagraphBreak(cursor);
                break;
            }
            case "tr":
                requestParagraphBreak(cursor);
                appendChildren(element, cursor, style);
                break;
            case "td":
            case "th":
                appendChildren(element, cursor, style);
                ensureParagraphReady(cursor);
                createStyledRun(cursor.paragraph, "\t", style);
                cursor.paragraphHasContent = true;
                break;
            case "b":
            case "strong":
                appendChildren(element, cursor, withBold(style));
                break;
            case "i":
            case "em":
                appendChildren(element, cursor, withItalic(style));
                break;
            case "u":
                appendChildren(element, cursor, withUnderline(style));
                break;
            case "sub":
                appendChildren(element, cursor, withSubscript(style));
                break;
            case "sup":
                appendChildren(element, cursor, withSuperscript(style));
                break;
            case "font":
                appendChildren(element, cursor, withFont(style, element));
                break;
            case "a": {
                String href = element.attr("href");
                if (href.isBlank()) {
                    appendChildren(element, cursor, style);
                    break;
                }
                ensureParagraphReady(cursor);
                XWPFHyperlinkRun hyperlinkRun = cursor.paragraph.createHyperlinkRun(href);
                hyperlinkRun.setFontFamily(style.fontFamily != null ? style.fontFamily : FONT);
                hyperlinkRun.setColor("0000FF");
                hyperlinkRun.setUnderline(UnderlinePatterns.SINGLE);
                hyperlinkRun.setText(element.text());
                cursor.paragraphHasContent = true;
                break;
            }
            case "img":
                ensureParagraphReady(cursor);
                createStyledRun(cursor.paragraph, "[image: " + element.attr("src") + "]", style);
                cursor.paragraphHasContent = true;
                break;
            default:
                // span, table/tbody/thead/caption, ul/ol, and any other unrecognized tag: a
                // transparent wrapper - recurse into its children with the style unchanged, rather
                // than either rendering full DOCX tables/lists or leaking the raw tag as text.
                appendChildren(element, cursor, style);
        }
    }

    private RunStyle withBold(RunStyle style) {
        RunStyle next = new RunStyle(style);
        next.bold = true;
        return next;
    }

    private RunStyle withItalic(RunStyle style) {
        RunStyle next = new RunStyle(style);
        next.italic = true;
        return next;
    }

    private RunStyle withUnderline(RunStyle style) {
        RunStyle next = new RunStyle(style);
        next.underline = UnderlinePatterns.SINGLE;
        return next;
    }

    private RunStyle withSubscript(RunStyle style) {
        RunStyle next = new RunStyle(style);
        next.subscript = true;
        next.superscript = false;
        return next;
    }

    private RunStyle withSuperscript(RunStyle style) {
        RunStyle next = new RunStyle(style);
        next.superscript = true;
        next.subscript = false;
        return next;
    }

    /**
     * &lt;font&gt; is overloaded in curator text: the legacy curation tool convention is
     * &lt;font color=red&gt; to flag added/highlighted text, while a bare &lt;font face="Arial"&gt;
     * with no color is just an MS-Word-paste artifact that shouldn't turn the text red - so color
     * and face are applied independently, each only when actually present.
     */
    private RunStyle withFont(RunStyle style, Element fontElement) {
        RunStyle next = new RunStyle(style);
        String color = normalizeColor(fontElement.attr("color"));
        if (color != null) {
            next.color = color;
        }
        String face = fontElement.attr("face");
        if (!face.isBlank()) {
            next.fontFamily = face;
        }
        return next;
    }

    private String normalizeColor(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        if (normalized.matches("(?i)[0-9a-f]{6}")) {
            return normalized.toUpperCase();
        }
        switch (normalized.toLowerCase()) {
            case "red": return "FF0000";
            case "blue": return "0000FF";
            case "green": return "008000";
            case "black": return "000000";
            default: return null;
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
            cTLvl.addNewLvlText().setVal("\u2022");
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

    private Path resolveFigurePath(String figureDisplayName) {
        if (!hasText(figureDisplayName)) {
            return null;
        }

        String normalizedDisplayName = figureDisplayName.trim().replace('\\', '/');
        if (normalizedDisplayName.isEmpty()) {
            return null;
        }

        Path displayPath;
        try {
            displayPath = Path.of(normalizedDisplayName).normalize();
        } catch (Exception ignored) {
            return null;
        }

        if (!hasText(figureRootPath)) {
            return displayPath;
        }

        Path rootPath;
        try {
            rootPath = Path.of(figureRootPath.trim()).normalize();
        } catch (Exception ignored) {
            return displayPath;
        }

        // Figure display names can start with "/figures/..." and should be resolved under the configured root.
        String relativeFigure = normalizedDisplayName.startsWith("/")
                ? normalizedDisplayName.substring(1)
                : normalizedDisplayName;

        try {
            return rootPath.resolve(relativeFigure).normalize();
        } catch (Exception ignored) {
            return displayPath;
        }
    }

    private void addPathwayBrowserLink(XWPFDocument document, Event event) {
        String stableId = getStableIdentifierWithoutVersion(event);
        if (!hasText(stableId)) {
            return;
        }

        String url = "https://newcurator.reactome.org/curatorgraph/PathwayBrowser/" + stableId;
        XWPFParagraph paragraph = document.createParagraph();

        XWPFRun textRun = paragraph.createRun();
        textRun.setFontFamily(FONT);
        textRun.setText("See web page for this " + getEventTypeForLinkText(event) + ": ");

        XWPFHyperlinkRun hyperlinkRun = paragraph.createHyperlinkRun(url);
        hyperlinkRun.setFontFamily(FONT);
        hyperlinkRun.setColor("0000FF");
        hyperlinkRun.setUnderline(UnderlinePatterns.SINGLE);
        hyperlinkRun.setText(url);
    }

    private String getEventTypeForLinkText(Event event) {
        String eventType = getEventTypeLabel(event).toLowerCase();
        if (eventType.contains("pathway")) {
            return "pathway";
        }
        if (eventType.contains("reaction")) {
            return "reaction";
        }
        return "event";
    }

    private String getStableIdentifierWithoutVersion(Event event) {
        if (event == null) {
            return null;
        }
        try {
            StableIdentifier stableIdentifier = event.getStableIdentifier();
            if (stableIdentifier == null || !hasText(stableIdentifier.getIdentifier())) {
                return null;
            }
            return stableIdentifier.getIdentifier().replaceFirst("\\.\\d+$", "");
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isReactionEvent(Event event) {
        String eventType = getEventTypeLabel(event).toLowerCase();
        return eventType.contains("reaction");
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
            this.fontFamily = FONT;
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
