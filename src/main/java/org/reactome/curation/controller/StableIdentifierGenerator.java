package org.reactome.curation.controller;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import org.reactome.curation.model.CurationAttribute;
import org.reactome.curation.repository.CurationRepository;
import org.reactome.curation.service.CurationService;
import org.reactome.server.graph.domain.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


/**
 * Methods handling stable identifiers are collected here. This class implements the following approach based on Joel's
 * document in http://devwiki.reactome.org/index.php/Development_Teleconferences#Minutes_and_Agendas:
 * If instance is a physical entity:
 * If one species instance is attached, use it to get the prefix
 * If more than one species instance is attached, the prefix is ‘NUL’
 * If no species instances are attached:
 * Get all species instances recursively from the physical entity
 * (i.e. components from complexes, members/candidates from sets, repeated unit from polymers)
 * If one species instance is attached, use it to get the prefix
 * If more than one species instance is attached, the prefix is ‘NUL’
 * If no species instance is attached, the prefix is ‘ALL’
 * <p>
 * If instance is an event:
 * If one species instance is attached, use it to get the prefix
 * If no or more than one species is attached, the prefix is ‘NUL’
 *
 * @author Gwu
 */
//@SuppressWarnings("unchecked")
@Component // generate a bean for this file
public class StableIdentifierGenerator {
    private final String NUL_SPECIES = "NUL";
    private final String ALL_SPECIES = "ALL";
    private Set<Class<?>> stidClasses;

    @Autowired
    private CurationService curationService;
    @Autowired
    private CurationRepository curationRepository;

    public StableIdentifierGenerator() {
    }

    /**
     * Check if a DatabaseObject needs to have a stable id.
     *
     * @param dbObject Instance to check
     * @return true if stable id is needed
     */
    private boolean needStid(DatabaseObject dbObject) {
        Set<Class<?>> classes = getClassNamesWithStableIds();
        for (Class<?> cls : classes) {
            if (cls.isAssignableFrom(dbObject.getClass())) {
                return true;
            }
        }
        return false;
    }

    private Set<Class<?>> getClassNamesWithStableIds() {
        if (stidClasses == null) {
            stidClasses = new HashSet<>();
            stidClasses.add(PhysicalEntity.class);
            stidClasses.add(Event.class);
        }
        return stidClasses;
    }

    public void setStableIdentifierAndStId(DatabaseObject instance) throws Exception {
        if(this.needStid(instance)){
            StableIdentifier stableIdentifier = this.generateStableId(instance, instance.getCreated());
            instance.setStableIdentifier(stableIdentifier);

            String stId = this.generateIdentifier(instance);
            instance.setStId(stId);
        }
    }

    /**
     * Create a StableIdentifier instance for the passed GKInstance object.
     *
     * @param instance Instance for which to create stable id instance
     * @param created  Created instance edit instance to attach to newly created stable id instance
     * @return Stable identifier instance
     * @throws Exception Thrown if unable to generate an identifier for the instance or if unable to set attribute
     *                   values for the newly created StableIdentifier instance
     */
    private StableIdentifier generateStableId(DatabaseObject instance,
                                             InstanceEdit created) throws Exception {
        if (!needStid(instance))
            return null;
        String id = generateIdentifier(instance);
        StableIdentifier stableIdentifier = new StableIdentifier();
        stableIdentifier.setIdentifier(id);
        stableIdentifier.setIdentifierVersion("1");
        if (created != null)
            stableIdentifier.setCreated(created);
        stableIdentifier.setDisplayName(id + "." + stableIdentifier.getIdentifierVersion());

        return stableIdentifier;
    }


    /**
     * The actual method to generate a stable identifier for a GKInstance.
     *
     * @param instance Instance for which to generate a stable identifier
     * @return String containing the generated stable identifier value
     * @throws Exception Thrown if unable to get species abbreviation for instance
     */
    private String generateIdentifier(DatabaseObject instance) throws Exception {
        String species = getSpeciesForSTID(instance);
        String id = "R-" + species + "-" + instance.getDbId();
        return id;
    }


    private String getSpeciesForSTID(DatabaseObject inst) throws Exception {
        String species = NUL_SPECIES;

        if (Event.class.isAssignableFrom(inst.getClass()))
            species = getSpeciesFromEvent((Event) inst);
        else if (PhysicalEntity.class.isAssignableFrom(inst.getClass()))
            species = getSpeciesFromPhysicalEntity(inst);
        return species;
    }

    private String getSpeciesFromPhysicalEntity(DatabaseObject physicalEntity) throws Exception {
        Set<DatabaseObject> speciesSet = getSpeciesFromPE(physicalEntity);
        if (speciesSet.size() == 0)
            return ALL_SPECIES;
        else if (speciesSet.size() > 1)
            return NUL_SPECIES;
        else {
            DatabaseObject species = speciesSet.iterator().next();
            return getSpeciesAbbreviation(species);
        }
    }

    /**
     * Species is a mandatory value. If nothing there, we will use NUL.
     *
     * @param event
     * @throws Exception
     */
    private String getSpeciesFromEvent(Event event) throws Exception {
        List<Species> speciesSet = event.getSpecies();
        if (speciesSet == null || speciesSet.size() == 0 || speciesSet.size() > 1)
            return NUL_SPECIES;
        DatabaseObject species = speciesSet.get(0);
        return getSpeciesAbbreviation(species);
    }

    private Set<DatabaseObject> getSpeciesFromPE(DatabaseObject pe) throws Exception {

        if (!this.isValidAttribute(pe, "species"))
            return null;
        Method method = pe.getClass().getMethod("getSpecies");
        ArrayList<DatabaseObject> species = (ArrayList<DatabaseObject>) method.invoke(pe);
        if (species != null && !species.isEmpty())
            return new HashSet<>(species);
        if (Complex.class.isAssignableFrom(pe.getClass()))
            return this.grepAllSpeciesInPE(pe, "hasComponent", "Complex");
        if (EntitySet.class.isAssignableFrom(pe.getClass()))
            return this.grepAllSpeciesInPE(pe, "hasMember", "EntitySet");
        if (Polymer.class.isAssignableFrom(pe.getClass()))
            return this.grepAllSpeciesInPE(pe, "repeatedUnit", "Polymer");
        return null;
    }

    private Set<DatabaseObject> grepAllSpeciesInPE(DatabaseObject pe, String followRelationship, String schemaClass) throws Exception {
        Set<DatabaseObject> speciesSet = new HashSet<>();
        // Collecting the complex's and component's species name and dbId
        Collection<Map<String, Object>> all = this.curationRepository.grepSpecies(pe.getDbId(), followRelationship, schemaClass);
        for (Map<String, Object> map : all) {
            String containedSpeciesDisplayName = map.get("containedSpecies.displayName").toString();
            String containedSpeciesDbId = map.get("containedSpecies.dbId").toString();
            // Create a simple instance to model the species and add to map
            Species species = new Species();
            species.setDbId(Long.parseLong(containedSpeciesDbId));
            species.setDisplayName(containedSpeciesDisplayName);
            speciesSet.add(species);
        }
        return speciesSet;
    }


    private String getSpeciesAbbreviation(DatabaseObject species) throws Exception {
        if (!Species.class.isAssignableFrom(species.getClass())) {
            throw new IllegalArgumentException("Instance " + species.getDbId() + " is not a species instance");
        }
        if (!species.isLoaded) {
            // We need to query the database to get the abbreviation
            species = curationService.findById(species.getDbId());
            if (species == null)
                throw new IllegalArgumentException("Cannot find species in the database: " + species);
        }
        // If species is shell, it should be replaced by a db copy already. However,
        // the db copy is not checked out for easy management.
        Species spec = (Species) species;
        String abbreviation = spec.getAbbreviation();
        if (abbreviation == null || abbreviation.isEmpty()) {
            throw new IllegalArgumentException(species.getDisplayName() + " has no abbreviation");
        }
        return abbreviation;
    }

    private boolean isValidAttribute(DatabaseObject databaseObject, String attributeName) throws Exception {
        List<CurationAttribute> attributes = curationService.getAttributes(databaseObject.getSchemaClass());
        List<String> attNames = attributes.stream().map(CurationAttribute::getName).collect(Collectors.toUnmodifiableList());

        return attNames.contains(attributeName);
    }

}