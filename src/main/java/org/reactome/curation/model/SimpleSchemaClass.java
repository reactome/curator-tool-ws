package org.reactome.curation.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * This is a really simple SchemaClass definition to provide the data model hierarchy to the frontend.
 * The data should be dumped by class CuratorToolExporter in package org.reactome.curation for easy
 * configuration and updating. 
 * Note: This class is very similar to SchemaNode in org.reactome.server.graph.service.helper. However
 * it is preferred here to make the front end parsing easy.
 * @author wug
 *
 */
@Data
@NoArgsConstructor
@JsonInclude(Include.NON_NULL)
public class SimpleSchemaClass {
    
    private String name;
    private List<SimpleSchemaClass> children;
    private boolean isAbstract;
    private SimpleSchemaClass superClass;
    private Integer count; // This may need to use Long in the future.

}
