package com.rewayaat.web.core;

/**
 * Represents the result of a user query.
 */
public interface RewayaatQueryResult {

    /**
     * Gives the result of the query the current object represents.
     */
    HadithObjectCollection result() throws Exception;
}
