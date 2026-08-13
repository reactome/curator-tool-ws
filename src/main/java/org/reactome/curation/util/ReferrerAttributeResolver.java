package org.reactome.curation.util;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.reactome.server.graph.domain.model.DatabaseObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.schema.Relationship;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Maps a Neo4j relationship back to the curator-facing attribute name that holds the
 * reference, for the instance sitting at one end of that relationship.
 *
 * A referrer query knows only the relationship TYPE (from Cypher's type(r)) plus which
 * direction the edge runs in relative to the referring instance. That is not the same
 * thing as the attribute name: graph-core maps an attribute to a relationship with
 * {@code @Relationship(type = ..., direction = ...)}, and the type is only equal to the
 * field name for the majority of attributes, not all of them. The mismatches matter
 * because they are exactly the attributes that would otherwise never show up as
 * referrers:
 *
 * <ul>
 * <li>{@code Event.inferredFrom} is {@code @Relationship(type = "inferredTo", direction =
 * INCOMING)} - there is no "inferredFrom" relationship in the graph at all. The single
 * edge {@code (source)-[:inferredTo]->(inferred)} is BOTH {@code inferred.inferredFrom =
 * source} and {@code source.orthologousEvent = inferred}, so which attribute name applies
 * depends on which end of the edge the referrer is.</li>
 * <li>{@code PhysicalEntity.inferredFrom} and {@code Regulation.inferredFrom} are the same
 * INCOMING view of "inferredTo", additionally marked {@code @ReactomeTransient}.</li>
 * </ul>
 *
 * Resolution therefore searches the referrer class's fields by relationship type AND
 * direction rather than by field name. Since a relationship type can be viewed from both
 * ends, and graph-core declares a lot of convenience reverse views that curators never see
 * ({@code PhysicalEntity.componentOf} for "hasComponent", {@code Pathway.diseasePathways}
 * for "normalPathway", {@code ReferenceEntity.physicalEntity} for "referenceEntity", ...),
 * a name found that way is only accepted when it is a real attribute of the referrer's
 * schema class in curation_schema_attributes.json - i.e. something the curator can
 * actually see holding that reference. Reverse views are not attributes, and reporting one
 * as a referrer would restate an instance's own attribute value as a reference TO it.
 *
 * @see org.reactome.curation.repository.CurationRepository#getReferrers(Long)
 */
public class ReferrerAttributeResolver {
    private static final Logger logger = LoggerFactory.getLogger(ReferrerAttributeResolver.class);

    private static final String SCHEMA_ATTRIBUTES_RESOURCE = "curation_schema_attributes.json";

    // Cache of schema class name -> the attribute names curators see for that class
    private static volatile Map<String, Set<String>> clsName2AttNames;
    // Cache of domain class name -> field name -> its @Relationship annotation. Unlike
    // CurationRepository.getField2rel(), @ReactomeTransient fields are kept: they are not
    // written back, but they are still a curator-visible view of a stored relationship
    // (e.g. PhysicalEntity.inferredFrom), which is all that matters when reading referrers.
    private static final Map<String, Map<String, Relationship>> cls2field2rel = new ConcurrentHashMap<>();

    /**
     * The attribute names of {@code referrerClass} that hold a reference expressed by a
     * relationship of type {@code relationshipType} running in {@code direction} relative
     * to the referrer.
     *
     * @param referrerClass    the graph-core domain class of the referring instance
     * @param schemaClassName  the referring instance's schema class, used to check the
     *                         resolved name really is one of its curation attributes
     * @param relationshipType the Neo4j relationship type, i.e. Cypher's type(r)
     * @param direction        the edge's direction as seen from the referring instance
     * @return the matching attribute names, empty if the relationship is not a
     *         curator-visible attribute of that class in that direction
     */
    public static List<String> resolveAttributeNames(Class<?> referrerClass,
                                                     String schemaClassName,
                                                     String relationshipType,
                                                     Relationship.Direction direction) {
        if (referrerClass == null || relationshipType == null || direction == null)
            return Collections.emptyList();
        Map<String, Relationship> field2rel = getField2rel(referrerClass);

        // The common case: the attribute name IS the relationship type. Accept it without
        // consulting the curation schema, so that this resolves exactly what a field-name
        // lookup used to resolve - no new filtering of attributes that already worked.
        Relationship sameName = field2rel.get(relationshipType);
        if (sameName != null
                && relationshipType.equals(effectiveType(relationshipType, sameName))
                && direction.equals(sameName.direction()))
            return List.of(relationshipType);

        // Otherwise look for an attribute whose relationship type differs from its name.
        List<String> attNames = new ArrayList<>();
        for (Map.Entry<String, Relationship> entry : field2rel.entrySet()) {
            String fieldName = entry.getKey();
            Relationship rel = entry.getValue();
            if (!relationshipType.equals(effectiveType(fieldName, rel)) || !direction.equals(rel.direction()))
                continue;
            if (!isCurationAttribute(schemaClassName, fieldName))
                continue; // A graph-core reverse view, not an attribute of this instance
            attNames.add(fieldName);
        }
        Collections.sort(attNames); // Keep the result deterministic
        return attNames;
    }

    /**
     * Whether {@code attName} is an attribute of {@code schemaClassName} in
     * curation_schema_attributes.json, i.e. one the curator sees in the instance view.
     */
    public static boolean isCurationAttribute(String schemaClassName, String attName) {
        if (schemaClassName == null || attName == null)
            return false;
        Map<String, Set<String>> schema = getClsName2AttNames();
        Set<String> attNames = schema.get(schemaClassName);
        if (attNames == null) {
            // The curation schema keeps the MySQL data model's spelling for this one class.
            // Same hack as CurationService's constructor.
            if ("ReactionLikeEvent".equals(schemaClassName))
                attNames = schema.get("ReactionlikeEvent");
            if (attNames == null)
                return false;
        }
        return attNames.contains(attName);
    }

    /**
     * The relationship type of a field: the type given in the annotation, or the field name
     * itself when the annotation leaves the type at its default empty value.
     */
    private static String effectiveType(String fieldName, Relationship rel) {
        String type = rel.type();
        return (type == null || type.isEmpty()) ? fieldName : type;
    }

    private static Map<String, Relationship> getField2rel(Class<?> cls) {
        return cls2field2rel.computeIfAbsent(cls.getName(), name -> {
            Map<String, Relationship> field2rel = new HashMap<>();
            Class<?> _class = cls;
            while (_class != null && !_class.equals(Object.class)) {
                for (Field field : _class.getDeclaredFields()) {
                    Relationship rel = field.getAnnotation(Relationship.class);
                    // The most derived declaration of a field wins, hence putIfAbsent while
                    // walking up towards DatabaseObject.
                    if (rel != null)
                        field2rel.putIfAbsent(field.getName(), rel);
                }
                _class = _class.getSuperclass();
            }
            return Collections.unmodifiableMap(field2rel);
        });
    }

    private static Map<String, Set<String>> getClsName2AttNames() {
        Map<String, Set<String>> schema = clsName2AttNames;
        if (schema == null) {
            synchronized (ReferrerAttributeResolver.class) {
                schema = clsName2AttNames;
                if (schema == null) {
                    schema = loadClsName2AttNames();
                    clsName2AttNames = schema;
                }
            }
        }
        return schema;
    }

    private static Map<String, Set<String>> loadClsName2AttNames() {
        Map<String, Set<String>> clsName2AttNames = new HashMap<>();
        try (InputStream is = ReferrerAttributeResolver.class.getClassLoader()
                .getResourceAsStream(SCHEMA_ATTRIBUTES_RESOURCE)) {
            if (is == null) {
                logger.error("Cannot find " + SCHEMA_ATTRIBUTES_RESOURCE + " in the classpath.");
                return clsName2AttNames;
            }
            JsonNode root = new ObjectMapper().readTree(is);
            for (Iterator<String> it = root.fieldNames(); it.hasNext();) {
                String clsName = it.next();
                Set<String> attNames = new HashSet<>();
                for (JsonNode att : root.get(clsName)) {
                    JsonNode name = att.get("name");
                    if (name != null)
                        attNames.add(name.asText());
                }
                clsName2AttNames.put(clsName, attNames);
            }
        }
        catch (Exception e) {
            logger.error("Cannot load " + SCHEMA_ATTRIBUTES_RESOURCE + ": " + e.getMessage(), e);
        }
        return clsName2AttNames;
    }

    /**
     * The graph-core domain class for a schema class name, or null if there is none.
     */
    public static Class<?> getDomainClass(String schemaClassName) {
        if (schemaClassName == null)
            return null;
        try {
            return Class.forName(DatabaseObject.class.getPackageName() + '.' + schemaClassName);
        }
        catch (ClassNotFoundException e) {
            logger.error("Cannot find the domain class for schema class " + schemaClassName);
            return null;
        }
    }
}
