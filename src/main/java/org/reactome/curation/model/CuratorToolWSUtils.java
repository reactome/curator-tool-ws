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
     * Find the set methos for an attribute in the DatabaseObjet class.
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
        if (parameterCls == ArrayList.class)
            parameterCls = List.class; // Make it more generic since it is used in class definitions
        Method method = object.getClass().getMethod(methodName, parameterCls);
        return method;
    }

}
