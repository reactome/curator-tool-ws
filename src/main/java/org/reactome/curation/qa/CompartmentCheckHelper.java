package org.reactome.curation.qa;

import java.util.Map;
import java.util.Set;

import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;

public interface CompartmentCheckHelper {
    
    public QACheckResult checkCompartment(Map<Long, SimpleInstance> containerId2Comp,
                                          Map<Long, SimpleInstance> includedId2Comp,
                                          Map<String, SimpleInstance> idRole2Contained,
                                          Map<Long, SimpleInstance> containedId2Comp);

}
