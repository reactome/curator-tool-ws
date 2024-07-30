package org.reactome.curation.model;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class CuratorToolReferrerList {
    private String attributeName;
    private List<SimpleInstance> referrers;

    public CuratorToolReferrerList() {
    }

    public CuratorToolReferrerList(String attributeName, List<SimpleInstance> referrers) {
        this.attributeName = attributeName;
        this.referrers = referrers;
    }

}
