package com.rewayaat.web.core;

import java.io.Serializable;
import java.util.List;

public interface RewayaatQueryResult {

	/**
	 * Gives the result of the query the current object represents.
	 */
	public List<? extends Serializable> result() throws Exception;
}
