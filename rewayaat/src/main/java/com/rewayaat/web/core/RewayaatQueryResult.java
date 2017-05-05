package com.rewayaat.web.core;

import java.io.Serializable;
import java.util.List;

/**
 * Represents the result of a user query.
 */
public interface RewayaatQueryResult {

    /**
     * Gives the result of the query the current object represents.
     */
    List<? extends Serializable> result() throws Exception;
}
