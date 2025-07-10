package org.reactome.curation.model;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.reactome.server.graph.domain.model.DatabaseObject;

/**
 * A collection of some utility methods that can be used in this project.
 * @author wug
 *
 */
public class CuratorToolWSUtils {
    
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
                    if (parameterType.isAssignableFrom(parameterCls)) {
                        return method; // Found the method
                    }
                }
            }
        }
        return null; // Not found
    }

}
