package org.reactome.curation.model;

import java.util.List;

import lombok.Data;

/**
 * A simple POJO to hold a list of referrers for a given attribute name.
 *
 */
@Data
public class NamedReferrerList {
    private String attributeName;
    private List<SimpleInstance> referrers;

    public NamedReferrerList() {
    }

    public NamedReferrerList(String attributeName, List<SimpleInstance> referrers) {
        this.attributeName = attributeName;
        this.referrers = referrers;
    }

}
