package org.reactome.curation.model;

import lombok.Data;

@Data
public class Referrer {

    private String attributeName;
    private SimpleInstance simpleInstance;

    public Referrer() {
    }

    public Referrer(String attributeName, SimpleInstance simpleInstance) {
        this.attributeName = attributeName;
        this.simpleInstance = simpleInstance;
    }

}