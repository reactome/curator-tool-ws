package org.reactome.curation.controller;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import org.gk.model.InstanceNotFoundException;
import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.CurationAttribute;
import org.reactome.curation.model.CuratorToolWSUtils;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.service.CurationService;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.Event;
import org.reactome.server.graph.domain.model.InstanceEdit;
import org.reactome.server.graph.domain.model.Person;
import org.reactome.server.graph.domain.model.UpdateTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * This class is used to do a two away converting between a DatabaseObject and a SimpleInstance object
 * for the controller.
 *
 * @author wug
 */
@Component // So a Bean for this class can be auto-created.
@SuppressWarnings("rawtypes")
public class DatabaseObjectInstanceConverter {
    private final static Logger logger = LoggerFactory.getLogger(DatabaseObjectInstanceConverter.class);

    @Autowired
    private CurationService curationService;

    public DatabaseObjectInstanceConverter() {
    }

    /**
     * Convert a DatabaseObject object to a SimpleInstance object. This converting implements a shallow layer,
     * i.e. the referred Object is converted in a shell SimpleInstance only, having dbId, displayName and
     * class name to control the output.
     *
     * @param databaseObject
     * @return
     */
    @SuppressWarnings({"unchecked"})
    public SimpleInstance convert(DatabaseObject databaseObject) throws Exception {
        SimpleInstance instance = convertInShell(databaseObject);
        List<CurationAttribute> attributes = curationService.getAttributes(databaseObject.getSchemaClass());
        for (CurationAttribute attribute : attributes) {
            // There is a bug with deletion. Disable it for the time being
            // These attributes don't have properties
            if (attribute.getProperties() == null)
                continue;
            String methodName = "get" + attribute.getName().substring(0, 1).toUpperCase() + attribute.getName().substring(1);
            Method method = databaseObject.getClass().getMethod(methodName);
            Object value = method.invoke(databaseObject);
            if (value == null)
                continue;
            Object convertedValue = null;
            // It actually returns a LinkedHashSet from the data model. Therefore, check
            // as collection
            if (value instanceof Collection<?>) {
                Collection<?> valueList = (Collection<?>) value;
                convertedValue = new ArrayList<>();
                for (Object value1 : valueList) {
                    if (value1 instanceof DatabaseObject) {
                        SimpleInstance convertedValue1 = convertInShell((DatabaseObject) value1);
                        ((List) convertedValue).add(convertedValue1);
                    } else
                        ((List) convertedValue).add(value1); // Nothing needs to be done
                }
            } else {
                if (value instanceof DatabaseObject)
                    convertedValue = convertInShell((DatabaseObject) value);
                else
                    convertedValue = value;
            }
            instance.setAttribute(attribute.getName(), convertedValue);
        }
        return instance;
    }

    public SimpleInstance convertInShell(DatabaseObject databaseObject) {
        SimpleInstance instance = new SimpleInstance();
        instance.setDbId(databaseObject.getDbId());
        instance.setDisplayName(databaseObject.getDisplayName());
        // cannot use getClassName() here since the returned value is not the actual
        // Java class name. e.g. protein is used for some EWAS
//        instance.setSchemaClassName(databaseObject.getClassName());
        instance.setSchemaClassName(databaseObject.getSchemaClass());
        return instance;
    }

    /**
     * Convert a SimpleInstance object into a DatabaseObject object.
     *
     * @param instance
     * @return
     * @throws Exception
     */
    public DatabaseObject convert(SimpleInstance instance) throws Exception {
        // To avoid an infinity loop
        Map<Long, DatabaseObject> id2obj = new HashMap<>();
        DatabaseObject databaseObject = convert(instance, id2obj);
        return databaseObject;
    }

    public DatabaseObject convert(SimpleInstance instance, Boolean createIE) throws Exception {
        // To avoid an infinity loop
        Map<Long, DatabaseObject> id2obj = new HashMap<>();
        DatabaseObject databaseObj = this.convert(instance, id2obj);
//        if(databaseObj instanceof Event)
        if (createIE) {
            InstanceEdit ie = createInstanceEdit(instance);
            if (instance.getDbId() < 0)
                databaseObj.setCreated(ie);
            else {
                List<InstanceEdit> modifiedList = databaseObj.getModifiedList();
                // If the modifiedList is not defined, we need to create it
                if (modifiedList == null) {
                    modifiedList = new ArrayList<>();
                }
                modifiedList.add(ie);
                databaseObj.setModifiedList(modifiedList);
                databaseObj.setModified(ie);
                if ((databaseObj instanceof Event) && instance.getIsStructureModified()) {
                    Event event = (Event) databaseObj;
                    List<InstanceEdit> ies = event.getStructureModified();
                    if (ies == null)
                        ies = new ArrayList<>();
                    ies.add(ie);
                    event.setStructureModified(ies);
                }
            }
            // Get the new instances so that we can add created
            // There is no need for the modified slot
            Set<Long> newDbIds = new HashSet<>();
            grepNewInstanceDbIds(instance, newDbIds);
            newDbIds.stream().forEach(dbId -> {
                DatabaseObject valueObj = id2obj.get(dbId);
                if (valueObj != null) // Just in case
                    valueObj.setCreated(ie);
            });
        }
        return databaseObj;
    }

    private void grepNewInstanceDbIds(SimpleInstance instance, Set<Long> dbIds) {
        if (instance.getAttributes() != null) {
            for (String attName : instance.getAttributes().keySet()) {
                Object attValue = instance.getAttribute(attName);
                if (attValue == null)
                    continue;
                if (attValue instanceof SimpleInstance) {
                    SimpleInstance valueInst = (SimpleInstance) attValue;
                    if (valueInst.getDbId() < 0) {
                        if (dbIds.contains(valueInst.getDbId()))
                            continue; // Checked already
                        grepNewInstanceDbIds(valueInst, dbIds);
                        dbIds.add(valueInst.getDbId());
                    }
                } else if (attValue instanceof List) {
                    List valueList = (List) attValue;
                    if (valueList.size() == 0 || !(valueList.get(0) instanceof SimpleInstance))
                        continue;
                    for (int i = 0; i < valueList.size(); i++) {
                        SimpleInstance valueInst = (SimpleInstance) valueList.get(i);
                        if (valueInst == null)
                            continue; // Just in case
                        if (dbIds.contains(valueInst.getDbId()))
                            continue; // Checked already
                        grepNewInstanceDbIds(valueInst, dbIds);
                        dbIds.add(valueInst.getDbId());
                    }
                }
            }
        }
    }

    public InstanceEdit createInstanceEdit(SimpleInstance instance) throws Exception {
        Long personId = instance.getDefaultPersonId();
        if (personId == null) {
            logger.error("Person dbId is not defined!");
            throw new IllegalArgumentException("personId is null");
        }
        DatabaseObject person = curationService.findById(personId);
        if (person == null) {
            logger.error("Cannot find Person with dbId: " + personId);
            throw new InstanceNotFoundException(ReactomeJavaConstants.Person, personId);
        }
        InstanceEdit ie = new InstanceEdit();
        // Need to specify author and datetime
        ie.setAuthor(Collections.singletonList((Person) person));
        ie.setDateTime(this.getDateTime());
        // Generate display name for it
        // Technically we should have a place to manage this for all instances
        // However, this work has been moved to the front-end. We just limit it
        // to InstanceEdit here
        String displayName = person.getDisplayName() + ", " + ie.getDateTime().split(" ")[0];
        ie.setDisplayName(displayName);
        return ie;
    }

    private String getDateTime() {
        // Use GMT to ensure the same time zone for all curators
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("GMT"));
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return now.format(formatter);
    }

    private DatabaseObject convert(SimpleInstance instance,
                                   Map<Long, DatabaseObject> id2object) throws Exception {
        // An instance may refer to itself via some loop either because of the true biology
        // (e.g. loop) or by curation error. Therefore, we have to check if this instance has
        // been converted already to avoid falling into a infinity loop.
        if (id2object.containsKey(instance.getDbId()))
            return id2object.get(instance.getDbId());
        DatabaseObject databaseObject = newInstance(instance);
        id2object.put(instance.getDbId(), databaseObject);
        // The basic information
        databaseObject.setDbId(instance.getDbId());
        databaseObject.setDisplayName(instance.getDisplayName());
        if (instance.getAttributes() == null)
            return databaseObject; // Nothing else to do.
        // Assign attributes
        for (String attributeName : instance.getAttributes().keySet()) {
            // Ignore dbId and displayName here since they should be handled already
            if (attributeName.equals("dbId") || attributeName.equals("displayName"))
                continue;
            Object value = instance.getAttributes().get(attributeName);
            if (value instanceof SimpleInstance) { // Need to convert the value to DatabaseObject
                value = convert((SimpleInstance) value, id2object);
            } else if (value instanceof Collection) {
                Optional<?> any = ((Collection) value).stream().findAny();
                if (any.isEmpty())
                    continue; // Do nothing
                Object anyValue = any.get();
                if (anyValue instanceof SimpleInstance) {
                    // Need to convert the list to a list of SimpleInstance
                    List<DatabaseObject> tmpList = new ArrayList<>();
                    for (Object obj : (Collection) value) {
                        DatabaseObject tmpObj = convert((SimpleInstance) obj, id2object);
                        tmpList.add(tmpObj);
                    }
                    value = tmpList;
                }
            }
            Method setMethod = CuratorToolWSUtils.getSetMethod(attributeName, value, databaseObject);
            if (setMethod == null) { // This should not occur since getSetMethod has checked already. Just in case!
                logger.error("Cannot find a set method for " + attributeName + " in " + instance.getSchemaClassName());
                continue;
            }
            setMethod.invoke(databaseObject, value);
        }
        return databaseObject;
    }

    private DatabaseObject newInstance(SimpleInstance instance) throws Exception {
        Class<? extends DatabaseObject> cls = instance.getGraphModelClass();
        if (cls == null)
            return instance;
        // Find the constructor having no parameters
        Constructor<?> defaultConstructor = null;
        for (Constructor<?> c : cls.getConstructors()) {
            if (c.getGenericParameterTypes().length == 0) {
                defaultConstructor = c;
                break;
            }
        }
        if (defaultConstructor == null)
            throw new IllegalStateException("Cannot create an object of " + instance.getSchemaClassName() + ": No default constructor.");
        return (DatabaseObject) defaultConstructor.newInstance();
    }

}
