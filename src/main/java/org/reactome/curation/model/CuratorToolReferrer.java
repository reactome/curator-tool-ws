package org.reactome.curation.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

public class CuratorToolReferrer {

@Setter
@Getter
    private String className;
    private SimpleInstance simpleInstance;

    public CuratorToolReferrer() {
    }

    public CuratorToolReferrer(String className, SimpleInstance simpleInstance) {
        this.className = className;
        this.simpleInstance = simpleInstance;
    }

    public SimpleInstance getSimpleInstance() {
        return simpleInstance;
    }

    public void setSimpleInstance(SimpleInstance simpleInstance) {
        this.simpleInstance = simpleInstance;
    }
}