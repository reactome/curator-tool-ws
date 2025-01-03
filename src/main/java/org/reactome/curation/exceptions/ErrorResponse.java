package org.reactome.curation.exceptions;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * This class is used to send an exception to the front-end via a centralized exception handling mechanism using
 * SpringBoot's RestControllerAdvice approach.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {
    
    private int status;
    private String message;
    private long timestamp;

}
