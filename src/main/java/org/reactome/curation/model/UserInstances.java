package org.reactome.curation.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * This class is used to model staged instances for a user before these instances
 * committed into the database and some user specific information, such as bookmarks.
 */
@Data
@NoArgsConstructor
@JsonInclude(Include.NON_NULL)
public class UserInstances {
    
    private List<SimpleInstance> newInstances;
    private List<SimpleInstance> updatedInstances;
    private List<SimpleInstance> deletedInstances;
    private List<SimpleInstance> bookmarks;
    private SimpleInstance defaultPerson;

}
