/*
 * Created on Aug 4, 2005
 *
 */
package org.reactome.curation.service.autofill;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.InstanceList;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.repository.CurationRepository;
import org.reactome.server.graph.repository.AdvancedDatabaseObjectRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;

/**
 * Base class for concrete AttributeAutoFiller implementations in the web service.
 * Provides access to the shared Spring-managed repositories.
 *
 * @author guanming
 */
public abstract class AbstractAttributeAutoFiller {

    @Autowired
    protected CurationRepository curationRepository;
    @Autowired
    protected AdvancedDatabaseObjectRepository objectRepository;

    /**
     * Perform the auto-filling process for the given instance.
     *
     * @param instance the SimpleInstance to populate with fetched data
     * @throws Exception if fetching or applying attribute data fails
     */
    public abstract void process(SimpleInstance instance) throws Exception;

    /**
     * A helper method to get a ReferenceDatabase instance for the specified instance.
     * @param dbName
     * @return
     * @throws Exception
     */
    protected SimpleInstance getReferenceDatabase(String dbName) throws Exception {
        // Using _displayName can fetch local shell instances.
        SimpleInstance refdbInst = curationRepository.findInstance(dbName,
                Collections.singletonList(ReactomeJavaConstants.ReferenceDatabase));
        return refdbInst;
    }

}
