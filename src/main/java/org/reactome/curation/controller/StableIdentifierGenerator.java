package org.reactome.curation.controller;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import org.reactome.curation.util.CuratorToolWSUtils;
import org.reactome.curation.service.CurationService;
import org.reactome.server.graph.domain.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Note: The identifierVersion is handled by another project, release-update-stable-ids: 
 * https://github.com/reactome/release-update-stable-ids.
 *
 * @author Gwu
 */
//@SuppressWarnings("unchecked")
@Component // generate a bean for this file
public class StableIdentifierGenerator {
    private final String NUL_SPECIES = "NUL";
    private final String ALL_SPECIES = "ALL";
    private Set<Class<?>> stidClasses;
    private static final Logger logger = LoggerFactory.getLogger(StableIdentifierGenerator.class);

    @Autowired
    private CurationService curationService;

    public StableIdentifierGenerator() {
    }

    /**
     * Check if a DatabaseObject needs to have a stable id.
     *
     * @param dbObject Instance to check
     * @return true if stable id is needed
     */
    private boolean needStid(DatabaseObject dbObject) {
        Set<Class<?>> classes = getClassesWithStableIds();
        for (Class<?> cls : classes) {
            if (cls.isAssignableFrom(dbObject.getClass())) {
                return true;
            }
        }
        return false;
    }

    private Set<Class<?>> getClassesWithStableIds() {
        if (stidClasses == null) {
            stidClasses = new HashSet<>();
            stidClasses.add(PhysicalEntity.class);
            stidClasses.add(Event.class);
        }
        return stidClasses;
    }

    public void setStableIdentifier(DatabaseObject instance) throws Exception {
        if(this.needStid(instance)){
            StableIdentifier stableIdentifier = this.generateStableId(instance);
            if (stableIdentifier == null)
                return; // No need to set stable identifier
            instance.setStableIdentifier(stableIdentifier);
            String stId = this.generateIdentifier(instance);
            instance.setStId(stId);
        }
    }

    /**
     * Create a StableIdentifier instance for the passed GKInstance object.
     *
     * @param instance Instance for which to create stable id instance
     * @return Stable identifier instance
     * @throws Exception Thrown if unable to generate an identifier for the instance or if unable to set attribute
     *                   values for the newly created StableIdentifier instance
     */
    private StableIdentifier generateStableId(DatabaseObject instance) throws Exception {
        String id = generateIdentifier(instance);
        // Need to check if there is a need to re-assign stable identifier.
        StableIdentifier existingSI = instance.getStableIdentifier();
        if (existingSI != null) {
            // Make sure exitingSI is loaded
            if (!existingSI.isLoaded) {
                existingSI = (StableIdentifier) curationService.findById(existingSI.getDbId());
                instance.setStableIdentifier(existingSI); // Need to replace with the loaded one
            }
            if (id.equals(existingSI.getIdentifier())) {
                return null; // The identifierVersion will be handled during slicing.
            }
        }
        StableIdentifier rtn = null;
        InstanceEdit created = getLastInstanceEdit(instance);
        if (existingSI != null) {
            rtn = existingSI;
            rtn.setIdentifier(id); // Update identifier value. But leave the identifierVersion alone
            if (created != null) {
                rtn.setModified(created);
                List<InstanceEdit> modifiedList = rtn.getModifiedList();
                if (modifiedList == null)
                    modifiedList = new ArrayList<>();
                modifiedList.add(created);
                rtn.setModifiedList(modifiedList); 
            }            
        }
        else {
            rtn = new StableIdentifier();
            rtn.setIdentifierVersion("1");
            if (created != null)
                rtn.setCreated(created);
        }
        rtn.setDisplayName(id + "." + rtn.getIdentifierVersion());

        return rtn;
    }

    private InstanceEdit getLastInstanceEdit(DatabaseObject instance) {
        InstanceEdit lastIE = null;
        List<InstanceEdit> ieList = instance.getModifiedList();
        if (ieList != null && !ieList.isEmpty()) {
            lastIE = ieList.get(ieList.size() - 1);
        } else {
            lastIE = instance.getCreated();
        }
        return lastIE;
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

        if (inst instanceof Event)
            species = getSpeciesFromEvent((Event) inst);
        else if (inst instanceof PhysicalEntity)
            species = getSpeciesFromPhysicalEntity(inst);
        return species;
    }

    private String getSpeciesFromPhysicalEntity(DatabaseObject physicalEntity) throws Exception {
        Set<Taxon> speciesSet = getSpeciesFromPE(physicalEntity);
        if (speciesSet == null || speciesSet.size() == 0)
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

    @SuppressWarnings("unchecked")
    private Set<Taxon> getSpeciesFromPE(DatabaseObject pe) throws Exception {
        // The following code is based on the assumption that the passed object and its referred objects
        // have been stored in the database. However, this assumption most likely is not true when a new
        // instance is being created with references that are not stored in the database. TODO: We may 
        // need to revisit the code here. 
        try {
            // If species is defined at the PE level and it is not empty, we will use it.
            // Otherwise, we may try to get species from its components, members or repeated unit.
            Method method = CuratorToolWSUtils.getGetMethod("species", pe);
            if (method == null)
                return Collections.emptySet();
            Object result = method.invoke(pe);
            if (result instanceof Taxon)
                return Collections.singleton((Taxon) result);
            if (result instanceof List) {
                return new HashSet<>((List<Taxon>) result);
            }
            // Three cases of nothing is defined at the PE level.
            if (Complex.class.isAssignableFrom(pe.getClass()))
                return this.grepAllSpeciesInPE(pe, "hasComponent", "Complex");
            if (EntitySet.class.isAssignableFrom(pe.getClass()))
                return this.grepAllSpeciesInPE(pe, "hasMember", "EntitySet");
            if (Polymer.class.isAssignableFrom(pe.getClass()))
                return this.grepAllSpeciesInPE(pe, "repeatedUnit", "Polymer");
        }
        catch (InvocationTargetException | NoSuchMethodException e) {
            logger.error("An error occurred while invoking " + pe.getDisplayName() + " with method getSpecies.");
        }
        return Collections.emptySet();
    }

    private Set<Taxon> grepAllSpeciesInPE(DatabaseObject pe, String followRelationship, String schemaClass) throws Exception {
        return this.curationService.grepSpecies(pe.getDbId(), followRelationship, schemaClass);
    }


    private String getSpeciesAbbreviation(DatabaseObject species) throws Exception {
        if (!(species instanceof Taxon)) {
            throw new IllegalArgumentException("Instance " + species.getDbId() + " is not a Taxon instance");
        }
        if (species.isLoaded) // Usually it is not the case
            return ((Species) species).getAbbreviation();
        return curationService.querySpeciesAbbreviation(species.getDbId());
    }

}