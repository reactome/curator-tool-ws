package org.reactome.curation.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonInclude(Include.NON_NULL)
public class InstanceList {
    private List<SimpleInstance> instances;
    // Total count of the query results.
    // This number may be different from the size of the instances list, which is determined by the page size.
    private Integer totalCount;

    // Ignore this method when serializing to JSON, as it is a utility method for checking if the list is empty.
    @JsonIgnore
    public boolean isEmpty() {
        return instances == null || instances.isEmpty();
    }
}
