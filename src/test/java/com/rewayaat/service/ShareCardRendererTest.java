package com.rewayaat.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the share card's rendering against the things that would silently break it.
 *
 * <p>None of this can tell you the card looks good — that needs eyes on a PNG. What it
 * can tell you is that the card still draws at all, which is not a given: the renderer
 * depends on four font files being on the classpath and on Java2D shaping Arabic, and
 * both are the kind of thing that disappears in a deployment rather than in a build.
 */
class ShareCardRendererTest {

    private static final String ARABIC =
            "إِنَّ الله عَزَّ وَجَلَّ وَضَعَ الإيمَانَ عَلَى سَبْعَةِ أَسْهُمٍ عَلَى الْبِرِّ وَالصِّدْقِ وَالْيَقِينِ";
    private static final String ENGLISH =
            "Allah, the Most Majestic, the Most Holy, made belief in seven shares: virtue, "
                    + "truthfulness, certainty, compliance, loyalty, knowledge and forbearance.";

    private final ShareCardRenderer renderer = new ShareCardRenderer();

    @Test
    void rendersTheOpenGraphSizeSoPreviewsAreNotCroppedOrRejected() throws IOException {
        BufferedImage card = decode(renderer.render(new ShareCardRenderer.Card(
                "AL-KĀFI · VOLUME 2 · HADITH 81", ARABIC, ENGLISH, "hadith.academyofislam.com")));

        assertEquals(1200, card.getWidth());
        assertEquals(630, card.getHeight());
    }

    /**
     * DroidKufi has no Latin glyphs and Source Serif has no Arabic ones, so a single-font
     * card would draw one of the two scripts as rows of .notdef boxes. Both of these must
     * put ink on the card, in both directions.
     */
    @Test
    void drawsBothScriptsRatherThanFallingBackToMissingGlyphBoxes() throws IOException {
        BufferedImage arabicOnly = decode(renderer.render(new ShareCardRenderer.Card(
                "AL-KHIṢĀL · HADITH 4", ARABIC, "", "hadith.academyofislam.com")));
        BufferedImage englishOnly = decode(renderer.render(new ShareCardRenderer.Card(
                "AL-KHIṢĀL · HADITH 4", "", ENGLISH, "hadith.academyofislam.com")));

        assertTrue(inkInBody(arabicOnly) > 2000,
                "Arabic-only card drew almost nothing; the Arabic face is probably missing");
        assertTrue(inkInBody(englishOnly) > 2000,
                "English-only card drew almost nothing; the Latin face is probably missing");
    }

    /**
     * The longest narrations used to run their last line of English straight through the
     * footer rule, because the layout picked the smallest type size and drew it whether or
     * not it fitted. Nothing may be painted below the footer rule but the footer itself.
     */
    @Test
    void aVeryLongNarrationStaysAboveTheFooterInsteadOfOverrunningIt() throws IOException {
        String longArabic = (ARABIC + " ").repeat(6);
        String longEnglish = (ENGLISH + " ").repeat(6);

        BufferedImage card = decode(renderer.render(new ShareCardRenderer.Card(
                "AL-KĀFI · VOLUME 1 · HADITH 1", longArabic, longEnglish,
                "hadith.academyofislam.com")));

        // The band between the last line of body text and the footer rule at y=536.
        assertEquals(0, ink(card, 516, 532),
                "body text overran into the footer band");
    }

    /** A narration with only one language must still fill the card rather than half of it. */
    @Test
    void aSingleLanguageNarrationUsesTheWholeBodyRatherThanTheTopHalf() throws IOException {
        BufferedImage card = decode(renderer.render(new ShareCardRenderer.Card(
                "NAHJ AL-BALĀGHA · HADITH 12", "",
                "Patience is to faith what the head is to the body.",
                "hadith.academyofislam.com")));

        // Centred, so the single line lands in the middle band of the body, not at its top.
        assertTrue(ink(card, 260, 360) > 500, "the lone line was not centred in the body");
        assertEquals(0, ink(card, 120, 200), "something was drawn where a second block would go");
    }

    /** Same input, same bytes — otherwise the content-hash ETag would change on every restart. */
    @Test
    void renderingIsDeterministicSoTheContentHashEtagStaysStable() {
        ShareCardRenderer.Card card = new ShareCardRenderer.Card(
                "AL-KĀFI · VOLUME 2 · HADITH 81", ARABIC, ENGLISH, "hadith.academyofislam.com");

        assertTrue(java.util.Arrays.equals(renderer.render(card), renderer.render(card)),
                "two renders of the same card produced different bytes");
    }

    private static BufferedImage decode(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image, "the bytes were not a readable PNG");
        return image;
    }

    /** Pixels bright enough to be text rather than the navy ground or its light pools. */
    private static int ink(BufferedImage card, int fromY, int toY) {
        int count = 0;
        for (int y = fromY; y < toY; y++) {
            for (int x = 0; x < card.getWidth(); x++) {
                int rgb = card.getRGB(x, y);
                int red = (rgb >> 16) & 0xff;
                int green = (rgb >> 8) & 0xff;
                int blue = rgb & 0xff;
                if (red > 180 && green > 180 && blue > 180) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int inkInBody(BufferedImage card) {
        return ink(card, 112, 512);
    }
}
