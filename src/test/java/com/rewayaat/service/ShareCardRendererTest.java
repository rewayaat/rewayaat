package com.rewayaat.service;

import com.rewayaat.service.ShareCardRenderer.Theme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the share card's rendering against the things that would silently break it.
 *
 * <p>None of this can tell you the card looks good — that needs eyes on a PNG. What it
 * can tell you is that the card still draws at all, which is not a given: the renderer
 * depends on three font files and two logo files being on the classpath and on Java2D
 * shaping Arabic, and those are the kind of thing that disappears in a deployment rather
 * than in a build.
 *
 * <p>Everything is measured as "ink": pixels whose luminance is far from the card's own
 * ground. Testing for bright pixels would have passed on the dark card and been
 * meaningless on the light one, where the ground is the bright thing.
 */
class ShareCardRendererTest {

    private static final String ARABIC =
            "إِنَّ الله عَزَّ وَجَلَّ وَضَعَ الإيمَانَ عَلَى سَبْعَةِ أَسْهُمٍ عَلَى الْبِرِّ وَالصِّدْقِ وَالْيَقِينِ";
    private static final String ENGLISH =
            "Allah, the Most Majestic, the Most Holy, made belief in seven shares: virtue, "
                    + "truthfulness, certainty, compliance, loyalty, knowledge and forbearance.";

    private final ShareCardRenderer renderer = new ShareCardRenderer();

    @ParameterizedTest
    @EnumSource(Theme.class)
    void rendersTheOpenGraphSizeSoPreviewsAreNotCroppedOrRejected(Theme theme) throws IOException {
        BufferedImage card = decode(renderer.render(new ShareCardRenderer.Card(
                "AL-KĀFI · VOLUME 2 · HADITH 81", ARABIC, ENGLISH, "hadith.academyofislam.com"),
                theme));

        assertEquals(1200, card.getWidth());
        assertEquals(630, card.getHeight());
    }

    /**
     * DroidKufi has no Latin glyphs and Source Serif has no Arabic ones, so a single-font
     * card would draw one of the two scripts as rows of .notdef boxes. Both of these must
     * put ink on the card, in both directions and in both themes.
     */
    @ParameterizedTest
    @EnumSource(Theme.class)
    void drawsBothScriptsRatherThanFallingBackToMissingGlyphBoxes(Theme theme) throws IOException {
        BufferedImage arabicOnly = decode(renderer.render(new ShareCardRenderer.Card(
                "AL-KHIṢĀL · HADITH 4", ARABIC, "", "hadith.academyofislam.com"), theme));
        BufferedImage englishOnly = decode(renderer.render(new ShareCardRenderer.Card(
                "AL-KHIṢĀL · HADITH 4", "", ENGLISH, "hadith.academyofislam.com"), theme));

        assertTrue(ink(arabicOnly, 114, 496) > 2000,
                "Arabic-only card drew almost nothing; the Arabic face is probably missing");
        assertTrue(ink(englishOnly, 114, 496) > 2000,
                "English-only card drew almost nothing; the Latin face is probably missing");
    }

    /**
     * The longest narrations used to run their last line of English straight through the
     * footer rule, because the layout picked the smallest type size and drew it whether or
     * not it fitted. The band between the body and the footer rule must stay clear — the
     * frame's own vertical rules are deliberately too low-contrast to register as ink.
     */
    @ParameterizedTest
    @EnumSource(Theme.class)
    void aVeryLongNarrationStaysAboveTheFooterInsteadOfOverrunningIt(Theme theme) throws IOException {
        BufferedImage card = decode(renderer.render(new ShareCardRenderer.Card(
                "AL-KĀFI · VOLUME 1 · HADITH 1", (ARABIC + " ").repeat(6), (ENGLISH + " ").repeat(6),
                "hadith.academyofislam.com"), theme));

        assertEquals(0, ink(card, 500, 516), "body text overran into the footer band");
    }

    /** A narration with only one language must still fill the card rather than half of it. */
    @ParameterizedTest
    @EnumSource(Theme.class)
    void aSingleLanguageNarrationUsesTheWholeBodyRatherThanTheTopHalf(Theme theme) throws IOException {
        BufferedImage card = decode(renderer.render(new ShareCardRenderer.Card(
                "NAHJ AL-BALĀGHA · HADITH 12", "",
                "Patience is to faith what the head is to the body.",
                "hadith.academyofislam.com"), theme));

        // Centred, so the single line lands in the middle band of the body, not at its top.
        assertTrue(ink(card, 250, 360) > 500, "the lone line was not centred in the body");
        assertEquals(0, ink(card, 120, 180), "something was drawn where a second block would go");
    }

    /**
     * The trap the light theme sets: the wordmark the dark card uses is white on
     * transparent, and on cream it is an invisible smudge. Each theme has to carry a mark
     * that actually reads against its own ground.
     */
    @ParameterizedTest
    @EnumSource(Theme.class)
    void theAliMarkIsVisibleAgainstTheGroundItIsDrawnOn(Theme theme) throws IOException {
        BufferedImage card = decode(renderer.render(new ShareCardRenderer.Card(
                "AL-KĀFI · VOLUME 2 · HADITH 81", ARABIC, ENGLISH, "hadith.academyofislam.com"),
                theme));

        // The footer's left half, which holds the mark and nothing else.
        assertTrue(ink(card, 536, 576, 76, 320) > 300,
                "the mark did not read against the " + theme + " ground");
    }

    /** Same input, same bytes — otherwise the content-hash ETag would change on every restart. */
    @Test
    void renderingIsDeterministicSoTheContentHashEtagStaysStable() {
        ShareCardRenderer.Card card = new ShareCardRenderer.Card(
                "AL-KĀFI · VOLUME 2 · HADITH 81", ARABIC, ENGLISH, "hadith.academyofislam.com");

        assertTrue(Arrays.equals(renderer.render(card, Theme.DARK), renderer.render(card, Theme.DARK)),
                "two renders of the same card produced different bytes");
    }

    /** The two themes are separate images behind one URL, so they must not collide. */
    @Test
    void theThemesAreDifferentImages() {
        ShareCardRenderer.Card card = new ShareCardRenderer.Card(
                "AL-KĀFI · VOLUME 2 · HADITH 81", ARABIC, ENGLISH, "hadith.academyofislam.com");

        assertTrue(!Arrays.equals(renderer.render(card, Theme.DARK), renderer.render(card, Theme.LIGHT)));
    }

    private static BufferedImage decode(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image, "the bytes were not a readable PNG");
        return image;
    }

    private static int ink(BufferedImage card, int fromY, int toY) {
        return ink(card, fromY, toY, 0, card.getWidth());
    }

    /**
     * Pixels far enough from the card's own ground to be text or a mark rather than the
     * gradient, its light pools or the deliberately faint frame.
     */
    private static int ink(BufferedImage card, int fromY, int toY, int fromX, int toX) {
        // Sampled above the frame, where nothing but the ground is ever drawn.
        double ground = luminance(card.getRGB(card.getWidth() / 2, 6));
        int count = 0;
        for (int y = fromY; y < toY; y++) {
            for (int x = fromX; x < toX; x++) {
                if (Math.abs(luminance(card.getRGB(x, y)) - ground) > 90) {
                    count++;
                }
            }
        }
        return count;
    }

    private static double luminance(int rgb) {
        return 0.2126 * ((rgb >> 16) & 0xff) + 0.7152 * ((rgb >> 8) & 0xff) + 0.0722 * (rgb & 0xff);
    }
}
