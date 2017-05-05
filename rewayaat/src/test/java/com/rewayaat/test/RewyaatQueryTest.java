package com.rewayaat.test;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.rewayaat.web.core.RewayaatQuery;

/**
 * Ensures requirements of the RewayaatQuery class are satisfied.
 */
public class RewyaatQueryTest {

	/**
	 * Ensure any regular english terms below or equal to
	 * a length of five characters
	 * are assigned a default fuzziness of 1.
	 */
	@Test
	public void testSmallEnglishQueryTermsAutoFuzziness() {
		assertTrue(new RewayaatQuery("hello test")
				.query()
				.trim()
				.equals("hello~1 test~1"));
	}
	
	/**
	 * Ensure any regular english terms above
	 * a length of five characters
	 * are assigned a default fuzziness of 2.
	 */
	@Test
	public void testLargeEnglishQueryTermsAutoFuzziness() {
		assertTrue(new RewayaatQuery("cuppycakes")
				.query()
				.trim()
				.equals("cuppycakes~2"));
	}
	
	/**
	 * Ensure phrases are slopped correctly
	 */
	@Test
	public void testAutoSloppingOfSmallPhrase() {
		assertTrue(new RewayaatQuery("\"a group of our associates\"")
				.query()
				.trim()
				.equals("\"a group of our associates\"~3"));
	}
	
	/**
	 * Ensure phrases are slopped correctly
	 */
	@Test
	public void testAutoSloppingOfLargePhrase() {
		assertTrue(new RewayaatQuery("\"a group of our associates a group of our associates a group of our associates\"")
				.query()
				.trim()
				.equals("\"a group of our associates a group of our associates a group of our associates\"~6"));
	}
	
	@Test
	public void testAutoFuzzinessForAMixOfLargeAndSmallEnglishQueryTerms() {
		assertTrue(new RewayaatQuery("cuppycakes test")
				.query()
				.trim()
				.equals("cuppycakes~2 test~1"));
	}
	
	/**
	 * Auto fuzziness should not be applied to query terms
	 * fuzzied by the user.
	 */
	@Test
	public void testNoAutoFuzzinessForUserFuzziesTerms() {
		// "test" should keep its fuzziness of 3...
		assertTrue(new RewayaatQuery("cuppycakes test~3")
				.query()
				.trim()
				.equals("cuppycakes~2 test~3"));
	}
	
	/**
	 * Auto fuzziness should not be applied to query phrases
	 * fuzzied by the user.
	 */
	@Test
	public void testNoAutoFuzzinessForUserPhrases() {
		// "test" should keep its fuzziness of 3...
		assertTrue(new RewayaatQuery("cuppycakes \"a group\"~6")
				.query()
				.trim()
				.equals("cuppycakes~2 \"a group\"~6"));
	}
	
	/**
	 * Auto fuzziness should not be applied to query phrases
	 * fuzzied by the user.
	 */
	@Test
	public void testNoAutoFuzzinessForUserPhrasesWithBoost() {
		// "test" should keep its fuzziness of 3...
		assertTrue(new RewayaatQuery("\"a group\"^3~6")
				.query()
				.trim()
				.equals("\"a group\"^3~6"));
	}
	
	/**
	 * Auto fuzziness should not be applied to query terms
	 * fuzzied by the user.
	 */
	@Test
	public void testNoAutoFuzzinessForUserTermsWithBoost() {
		// "test" should keep its fuzziness of 3...
		assertTrue(new RewayaatQuery("cuppycakes test^3~6")
				.query()
				.trim()
				.equals("cuppycakes~2 test^3~6"));
	}
}
