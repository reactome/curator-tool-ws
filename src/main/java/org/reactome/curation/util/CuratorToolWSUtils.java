package org.reactome.curation.util;

import java.lang.reflect.Method;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.server.graph.domain.model.DatabaseObject;

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
        for (Method method : object.getClass().getMethods()) {
            if (method.getName().equals(methodName)) {
                Class[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length == 1) {
                    Class<?> parameterType = parameterTypes[0];
                    // The method defined using super class may not be found using a subclass using getMethod directly.
                    // So we need to check if the parameter type is assignable from the value's class.
                    if (parameterType.isAssignableFrom(parameterCls)) {
                        return method; // Found the method
                    }
                }
            }
        }
        return null; // Not found
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
