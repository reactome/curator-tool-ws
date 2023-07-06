package org.reactome.curation.config;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper.DefaultTypeResolverBuilder;
import com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;

/**
 * This customized TypeResolverBuilder is based on https://stackoverflow.com/questions/12353774/how-to-customize-jackson-type-information-mechanism
 * and also https://www.demo2s.com/java/jackson-typeresolverbuilder-tutorial-with-examples.html. By using this TypeResolverBuilder,
 * we don't need to have a customized ObjectDeserializer as described in https://redamessoudi.com/jackson-polymorphic-deserialization-free-annotations/
 * and https://www.baeldung.com/jackson-deserialization. Using a customized ObjectDeserializer needs to provide our own
 * id-based cache mechanism to leverage the ids in JSON and also it needs to register an object of it for all abstract classes,
 * which may be bug prone. 
 * This approach is similar to using PolymorphicTypeValidator as described in https://redamessoudi.com/jackson-polymorphic-deserialization-free-annotations/.
 * However, using this approach is more flexible considering it is easier to inject type as a property and the name of
 * this property may be customized for any. For the time being, this property is called @JavaClass, which is required to be provided by the angular
 * front-end application. 
 * @author wug
 *
 */
@SuppressWarnings("serial")
public class DatabaseObjectTypeResolverBuilder extends DefaultTypeResolverBuilder {
    
    public DatabaseObjectTypeResolverBuilder(DefaultTyping t, PolymorphicTypeValidator ptv) {
        super(t, ptv);
    }

    @Override
    public boolean useForType(JavaType t) {
        // Make sure all Reactome model classes have the full names
        if (t.getRawClass().getName().startsWith("org.reactome")) {
            return true;
        }
        // For others, there is no need. This is mainly applied to ArrayList or other
        // Java core data classes.
        return false;
    }
}