package org.reactome.curation.util;

import java.lang.reflect.Method;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.Map;
import java.util.Set;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.server.graph.domain.model.Complex;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.PhysicalEntity;
import org.reactome.server.graph.domain.model.Polymer;
import org.reactome.server.graph.domain.model.ReactionLikeEvent;
import org.reactome.server.graph.domain.relationship.Has;
import org.reactome.server.graph.service.helper.StoichiometryObject;
import org.reactome.server.graph.service.util.DatabaseObjectUtils;

/**
 * A collection of some utility methods that can be used in this project.
 * @author wug
 *
 */
public class CuratorToolWSUtils {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    public static List<String> getStructureRelatedAttributes() {
        return Arrays.asList(
                ReactomeJavaConstants.hasEvent,
                ReactomeJavaConstants.catalystActivity,
                ReactomeJavaConstants.input,
                ReactomeJavaConstants.output
        );
    }

    public static Map<String, Object> getAllFields(DatabaseObject obj, boolean includeSubObjects) {
        Map<String, Object> field2value = DatabaseObjectUtils.getAllFields(obj, includeSubObjects);
        // Some modification regarding using RNAMarker (or rnaMarker, or rNAMakrer)
        if (field2value.containsKey("rNAMarker")) {
            Object value = field2value.get("rNAMarker");
            field2value.remove("rNAMarker");
            field2value.put("RNAMarker", value);
        }
        // DatabaseObjectUtils.getAllFields() populates "input"/"output"/"hasComponent"/"repeatedUnit"
        // via fetchInput()/fetchOutput()/fetchHasComponent()/fetchRepeatedUnit(), which sort by
        // displayName (Has.Util.simplifiedSort()) rather than the curator-defined order recorded in
        // each relationship's "order" property. Overwrite them here with the same relationship
        // entities, re-sorted by "order" instead, so saving doesn't scramble the order.
        if (obj instanceof ReactionLikeEvent) {
            ReactionLikeEvent rle = (ReactionLikeEvent) obj;
            if (field2value.containsKey(ReactomeJavaConstants.input))
                field2value.put(ReactomeJavaConstants.input, getOrderedStoichiometry(rle.getInputs()));
            if (field2value.containsKey(ReactomeJavaConstants.output))
                field2value.put(ReactomeJavaConstants.output, getOrderedStoichiometry(rle.getOutputs()));
        }
        else if (obj instanceof Complex) {
            Complex complex = (Complex) obj;
            if (field2value.containsKey(ReactomeJavaConstants.hasComponent))
                field2value.put(ReactomeJavaConstants.hasComponent, getOrderedStoichiometry(complex.getComponents()));
        }
        else if (obj instanceof Polymer) {
            Polymer polymer = (Polymer) obj;
            if (field2value.containsKey(ReactomeJavaConstants.repeatedUnit))
                field2value.put(ReactomeJavaConstants.repeatedUnit, getOrderedStoichiometry(polymer.getRepeatedUnits()));
        }
        return field2value;
    }

    /**
     * Build the case-insensitive Cypher regex used to match a search key as literal
     * text (i.e. the CONTAINS operand). The key is quoted so that regex metacharacters
     * typed by a curator are matched literally: without this, searching for
     * "Cyclin A (human)" would treat the parentheses as a capturing group and fail to
     * find the very instance with that display name, and a lone "[" would blow up as
     * an invalid pattern.
     * @param searchKey
     * @return
     */
    public static String buildContainsPattern(String searchKey) {
        return "(?i).*" + Pattern.quote(searchKey) + ".*";
    }

    /**
     * Build the Cypher regex used to match a search key as a regular expression (i.e.
     * the MATCHES_REGEX operand). The key is passed through unquoted and without the
     * .* padding that buildContainsPattern adds, letting ^ and $ anchor to the whole
     * value. Matching is case-insensitive to stay consistent with the rest of the
     * search UI; a pattern can opt back into case sensitivity with an inline (?-i).
     * @param searchKey
     * @return
     * @throws IllegalArgumentException if searchKey is not a valid regular expression.
     */
    public static String buildRegexPattern(String searchKey) {
        validateRegex(searchKey);
        return "(?i)" + searchKey;
    }

    /**
     * Reject an invalid regular expression up front with a message naming the problem.
     * Neo4j would otherwise fail the whole query with an opaque error, which surfaces
     * to the curator as either a 500 or (worse) an empty result that reads as "no
     * matches found" rather than "your pattern is malformed".
     * @param searchKey
     */
    public static void validateRegex(String searchKey) {
        try {
            Pattern.compile(searchKey);
        }
        catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regular expression \"" + searchKey + "\": "
                    + e.getDescription());
        }
    }

    /**
     * Split the comma-delimited searchKeys parameter, honouring backslash-escaped
     * commas so that a search term can contain one. This matters for regex terms in
     * particular: a quantifier such as a{2,3} would otherwise be split in half and
     * desynchronize the attribute, operand and key lists. Terms with no backslash in
     * them split exactly as a plain String.split(",") would.
     * @param searchKeys
     * @return
     */
    public static List<String> splitSearchKeys(String searchKeys) {
        List<String> keys = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < searchKeys.length(); i++) {
            char c = searchKeys.charAt(i);
            if (c == '\\' && i + 1 < searchKeys.length()) {
                char next = searchKeys.charAt(i + 1);
                // Only ',' and '\' are escaped by the client; every other backslash is
                // part of the term itself (e.g. the \d in a regex) and is kept as-is.
                if (next == ',' || next == '\\') {
                    current.append(next);
                    i++;
                    continue;
                }
                current.append(c);
            }
            else if (c == ',') {
                keys.add(current.toString());
                current.setLength(0);
            }
            else
                current.append(c);
        }
        keys.add(current.toString());
        return keys;
    }

    /**
     * Find the set method for an attribute in the DatabaseObjet class.
     * @param attributeName
     * @param value
     * @param object
     * @return
     * @throws Exception
     */
    @SuppressWarnings("rawtypes")
    public static Method getSetMethod(String attributeName,
                                      Object value,
                                      DatabaseObject object) throws Exception {
        String methodName = "set" + attributeName.substring(0, 1).toUpperCase() + attributeName.substring(1);
        Class parameterCls = value.getClass();
        // A method matching only via the collection-mismatch heuristic below isn't necessarily
        // usable for parameterCls (e.g. an unrelated overload that happens to take a List/Set of
        // a different element type), so it's kept only as a fallback: scan every method first and
        // prefer a directly assignable match wherever it appears, instead of returning on whichever
        // check passes first for the first matching method getMethods() happens to return.
        Method coercibleMethod = null;
        for (Method method : object.getClass().getMethods()) {
            if (method.getName().equals(methodName)) {
                Class[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length == 1) {
                    Class<?> parameterType = parameterTypes[0];
                    // The method defined using super class may not be found using a subclass using getMethod directly.
                    // So we need to check if the parameter type is assignable from the value's class.
                    if (parameterType.isAssignableFrom(parameterCls)) {
                        return method; // Directly usable - no need to keep scanning.
                    }
                    // Handle common collection mismatches (e.g. List value for Set setter).
                    if (coercibleMethod == null && value instanceof Collection &&
                        (List.class.isAssignableFrom(parameterType) || Set.class.isAssignableFrom(parameterType))) {
                        coercibleMethod = method;
                    }
                }
            }
        }
        return coercibleMethod; // No directly assignable match found; fall back to the coercible one, if any.
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static Object convertValueForSetMethod(Method setMethod, Object value) {
        if (value == null)
            return null;
        Class<?> parameterType = setMethod.getParameterTypes()[0];
        if (parameterType.isAssignableFrom(value.getClass()))
            return value;
        if (!(value instanceof Collection))
            return value;
        Collection collection = (Collection) value;
        if (Set.class.isAssignableFrom(parameterType))
            return new LinkedHashSet<>(collection);
        if (List.class.isAssignableFrom(parameterType))
            return new ArrayList<>(collection);
        return value;
    }

    public static Method getGetMethod(String attributeName,
                                      DatabaseObject object) throws Exception {
        String methodName = "get" + attributeName.substring(0, 1).toUpperCase() + attributeName.substring(1);
        for (Method method : object.getClass().getMethods()) {
            if (method.getName().equals(methodName))
                return method;
        }
        return null; // Not found
    }

    public static String getDateTime() {
        // Use GMT to ensure the same time zone for all curators
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("GMT"));
        return now.format(DATE_TIME_FORMATTER);
    }
}
