package com.rewayaat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.Shape;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the 1200x630 share image that a narration's {@code og:image} points at.
 *
 * <p>Java2D rather than an SVG rasteriser: the shaping spike showed that a
 * {@link TextLayout} built with {@link TextAttribute#RUN_DIRECTION_RTL} over the bundled
 * DroidKufi joins and orders Arabic correctly, so the whole card needs no new dependency.
 *
 * <p><b>Every font is loaded from the classpath.</b> Nothing here names a logical font
 * ("Serif", "SansSerif"), because the deployment image is an {@code eclipse-temurin} JRE
 * with no font packages installed — a logical name there resolves to whatever fontconfig
 * can find, which may be nothing at all. Loading the exact TTFs we ship makes the output
 * identical on a developer laptop and in the cluster.
 *
 * <p>Noto Naskh has no Latin glyphs to speak of and Source Serif has no Arabic ones, so text is
 * split into runs by which font can actually draw each character. 711 of the English
 * fields in the index contain Arabic script, so a single-font card would have rendered
 * rows of .notdef boxes on those.
 */
@Component
public class ShareCardRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShareCardRenderer.class);

    /** The Open Graph standard, and the ratio MailPoet and every chat client handle. */
    public static final int WIDTH = 1200;
    public static final int HEIGHT = 630;

    private static final int PAD = 76;
    private static final int CONTENT_WIDTH = WIDTH - 2 * PAD;

    // The manuscript frame: an inset double rule, not an edge-to-edge band, so the card
    // reads as a page rather than as a slide. Everything below is measured to clear it.
    private static final int FRAME_INSET = 26;
    private static final int FRAME_GAP = 6;

    private static final int EYEBROW_BASELINE = 80;
    private static final int BODY_TOP = 114;
    /** Distances from the bottom edge, so the footer survives the card growing taller. */
    private static final int BODY_BOTTOM_INSET = 134;
    private static final int FOOTER_RULE_INSET = 110;
    private static final int FOOTER_CENTRE_INSET = 74;

    /**
     * A ceiling on a full card. Some narrations run to thousands of words, and an image
     * tall enough for all of them is one no mail client will show; past this the card
     * truncates as it always did.
     */
    private static final int MAX_FULL_HEIGHT = 4200;
    private static final int LOGO_HEIGHT = 42;

    /** Room for the ornament that separates the Arabic from the English. */
    private static final int DIVIDER_BAND = 48;

    /** U+06DE, the same ornament the hadith card and the divider use on the site. */
    private static final String ORNAMENT = "۞";

    private final CardFonts fonts = new CardFonts();
    private final Palette dark = darkPalette();
    private final Palette light = lightPalette();

    /**
     * Dark is the default and is what {@code og:image} uses — navy is more striking in a
     * feed. Light exists for the Friday newsletter: MailPoet templates are white, and a
     * heavy navy block dropped into a white email reads as a foreign object.
     */
    /** Which of the two texts the card carries. */
    public enum Language { BOTH, ARABIC, ENGLISH }

    /**
     * What the reader asked for in the share dialog.
     *
     * <p>{@code full} trades the Open Graph ratio for completeness: the card grows
     * downwards until the narration fits rather than ending in an ellipsis. That is the
     * wrong shape for a link preview and the right one for an email or a printout, which
     * is why it is a choice and not the default.
     */
    public record Options(Language language, boolean full) {
        public static final Options DEFAULT = new Options(Language.BOTH, false);
    }

    public enum Theme {
        DARK, LIGHT
    }

    /**
     * One card's content, already excerpted and citation-formatted by the caller.
     *
     * @param eyebrow  the citation, drawn uppercase and letterspaced across the top; it is
     *                 what makes a screenshotted card attributable without its link
     * @param arabic   the Arabic matn, or blank for a single-language card
     * @param english  the English matn, or blank for a single-language card
     * @param footer   the domain, drawn opposite the ALI mark
     */
    public record Card(String eyebrow, String arabic, String english, String footer) {
    }

    public byte[] render(Card card, Theme theme) {
        return render(card, theme, Options.DEFAULT);
    }

    public byte[] render(Card card, Theme theme, Options options) {
        Palette palette = theme == Theme.LIGHT ? light : dark;
        Options opts = options == null ? Options.DEFAULT : options;
        String arabic = opts.language() == Language.ENGLISH ? null : card.arabic();
        String english = opts.language() == Language.ARABIC ? null : card.english();
        int height = opts.full() ? fullHeight(arabic, english) : HEIGHT;
        BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            paintGround(g, palette, height);
            paintFrame(g, palette, height);
            paintEyebrow(g, palette, card.eyebrow());
            paintBody(g, palette, arabic, english, height, opts.full());
            paintFooter(g, palette, card.footer(), height);
        } finally {
            g.dispose();
        }
        return encode(image);
    }

    // ── Palettes ────────────────────────────────────────────────────────────

    /**
     * Everything the card's look depends on, so the two themes differ in data rather than
     * in branching all through the drawing code.
     *
     * <p>The colours are the site's own design tokens from manuscript.css. A shared card
     * has to look like it came from this site, and inventing a second palette for the
     * light card is how the two would drift.
     */
    private record Palette(
            Color[] ground, float[] groundStops,
            Color poolHighlight, Color poolAccent,
            Color frame, Color frameHairline, Color cornerOrnament,
            Color eyebrow, Color arabicInk, Color englishInk,
            Color ornament, Color footerRule, Color footerInk,
            BufferedImage logo) {
    }

    /** {@code .hub-hero}: the navy gradient with its two light pools and gold accents. */
    private static Palette darkPalette() {
        Color gold = new Color(0xc8a23d);
        return new Palette(
                new Color[]{new Color(0x0a1628), new Color(0x0f2440), new Color(0x1e3a5f),
                        new Color(0x1a4a7a), new Color(0x1e3a5f)},
                new float[]{0f, 0.20f, 0.50f, 0.80f, 1f},
                new Color(255, 255, 255, 26),
                new Color(200, 162, 61, 36),
                new Color(200, 162, 61, 165),
                new Color(200, 162, 61, 92),
                new Color(200, 162, 61, 70),
                // #f3e5b8 measured 8.74:1 on the ground but composites to rgb(210,201,168) —
                // a beige, not a gold. Brightness and goldenness pull against each other
                // here: every lighter yellow is also a less saturated one, which is how
                // the original ended up looking washed out. #ffdb5c is the exception,
                // brighter than #ffd76a (10.56:1 against 10.32:1) and more saturated than
                // it too, and it takes the footer domain from 4.42:1, under AA for its
                // size, to 7.0:1.
                new Color(0xff, 0xdb, 0x5c),
                new Color(255, 255, 255),
                new Color(255, 255, 255, 233),
                new Color(gold.getRed(), gold.getGreen(), gold.getBlue(), 210),
                new Color(255, 219, 92, 80),
                new Color(255, 219, 92, 245),
                loadImage("static/img/Alilogov2-transparent.png"));
    }

    /**
     * {@code --surface-warm} into {@code --gold-bg}, the ground the light surfaces on the
     * site already use.
     *
     * <p>Two things here are not interchangeable with the dark palette. The gold is
     * {@code --gold-dark}: {@code --gold} on cream is too pale to read, and it is the
     * frame and the eyebrow that would go first. And the mark is {@code ALI-Logo.png}
     * rather than the white-on-transparent wordmark the dark card uses, which on a cream
     * ground would be an invisible smudge.
     */
    private static Palette lightPalette() {
        Color goldDark = new Color(0xa07e28);
        return new Palette(
                new Color[]{new Color(0xfefdfb), new Color(0xfefcf6), new Color(0xfdf8ed),
                        new Color(0xfcf5e6)},
                new float[]{0f, 0.35f, 0.75f, 1f},
                new Color(255, 255, 255, 130),
                new Color(200, 162, 61, 26),
                new Color(goldDark.getRed(), goldDark.getGreen(), goldDark.getBlue(), 170),
                new Color(goldDark.getRed(), goldDark.getGreen(), goldDark.getBlue(), 95),
                new Color(goldDark.getRed(), goldDark.getGreen(), goldDark.getBlue(), 75),
                goldDark,
                new Color(0x1a1a2e),
                new Color(26, 26, 46, 240),
                new Color(goldDark.getRed(), goldDark.getGreen(), goldDark.getBlue(), 200),
                new Color(160, 126, 40, 71),
                new Color(0x4a5568),
                loadImage("static/img/ALI-Logo.png"));
    }

    // ── Ground and frame ────────────────────────────────────────────────────

    private void paintGround(Graphics2D g, Palette palette, int height) {
        g.setPaint(new LinearGradientPaint(new Point2D.Float(0, 0), new Point2D.Float(WIDTH, height),
                palette.groundStops(), palette.ground()));
        g.fillRect(0, 0, WIDTH, height);

        // The two light pools from .hub-hero::before. Without them the ground reads as one
        // flat block, which at feed thumbnail size looks like a rendering failure.
        pool(g, 0.25f * WIDTH, 0.08f * height, 0.42f * WIDTH, 0.42f * height,
                palette.poolHighlight(), height);
        pool(g, 0.78f * WIDTH, 0.92f * height, 0.36f * WIDTH, 0.36f * height,
                palette.poolAccent(), height);
    }

    /** An elliptical light pool; the rectangle form of the paint is what makes it an ellipse. */
    private static void pool(Graphics2D g, float cx, float cy, float rx, float ry,
                             Color colour, int height) {
        g.setPaint(new RadialGradientPaint(
                new Rectangle2D.Float(cx - rx, cy - ry, rx * 2, ry * 2),
                new float[]{0f, 1f}, new Color[]{colour, transparent(colour)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fillRect(0, 0, WIDTH, height);
    }

    /**
     * The classic manuscript frame: a 2px rule with a hairline a few pixels inside it, and
     * a small ornament at each corner.
     *
     * <p>All of it is deliberately low-contrast. The card is seen most often as a WhatsApp
     * thumbnail a couple of hundred pixels wide, where ornament that reads as detail at
     * full size turns to mud and takes the narration down with it. The text keeps the
     * contrast; the frame only has to be felt.
     */
    private void paintFrame(Graphics2D g, Palette palette, int height) {
        float outer = FRAME_INSET;
        float inner = FRAME_INSET + FRAME_GAP;

        g.setColor(palette.frame());
        g.setStroke(new BasicStroke(2f));
        g.draw(new Rectangle2D.Float(outer, outer, WIDTH - 2 * outer, height - 2 * outer));

        g.setColor(palette.frameHairline());
        g.setStroke(new BasicStroke(1f));
        g.draw(new Rectangle2D.Float(inner, inner, WIDTH - 2 * inner, height - 2 * inner));

        g.setColor(palette.cornerOrnament());
        for (float x : new float[]{outer, WIDTH - outer}) {
            for (float y : new float[]{outer, height - outer}) {
                cornerOrnament(g, x, y);
            }
        }
    }

    /** Centres the ornament glyph on the frame corner, so the rules run behind it. */
    private void cornerOrnament(Graphics2D g, float cx, float cy) {
        TextLayout mark = new TextLayout(ORNAMENT, fonts.arabic().deriveFont(18f),
                g.getFontRenderContext());
        Rectangle2D bounds = mark.getBounds();
        mark.draw(g,
                (float) (cx - bounds.getWidth() / 2 - bounds.getX()),
                (float) (cy - bounds.getHeight() / 2 - bounds.getY()));
    }

    private static Color transparent(Color colour) {
        return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 0);
    }

    // ── Eyebrow ─────────────────────────────────────────────────────────────

    private void paintEyebrow(Graphics2D g, Palette palette, String eyebrow) {
        if (eyebrow == null || eyebrow.isBlank()) {
            return;
        }
        TextLayout layout = eyebrowLayout(g, fitEyebrow(g, eyebrow));
        glow(g, layout, palette.eyebrow(), PAD, EYEBROW_BASELINE);
        g.setColor(palette.eyebrow());
        layout.draw(g, PAD, EYEBROW_BASELINE);
    }

    private TextLayout eyebrowLayout(Graphics2D g, String eyebrow) {
        AttributedString text = fonts.runs(eyebrow, fonts.latinBold().deriveFont(21f),
                fonts.arabic().deriveFont(21f), false, 0.16f);
        return new TextLayout(text.getIterator(), g.getFontRenderContext());
    }

    /**
     * Drops citation segments from the middle until the line fits the card.
     *
     * <p>The eyebrow is drawn as a single unwrapped line, so it used to be the caller's
     * job never to hand over anything long. Now that it carries the part and the chapter,
     * whose titles run to sixty characters in Al-Kāfi, it is this method's job.
     *
     * <p>The middle goes first because the ends carry the identity: the book at the start
     * and the hadith number at the end are what make the citation resolvable, while the
     * levels between them are context. Truncating the tail instead would drop the number,
     * which is the one part a reader might type back into the search box.
     */
    private String fitEyebrow(Graphics2D g, String eyebrow) {
        String separator = " · ";
        if (eyebrowLayout(g, eyebrow).getAdvance() <= CONTENT_WIDTH) {
            return eyebrow;
        }
        List<String> parts = new ArrayList<>(List.of(eyebrow.split(java.util.regex.Pattern.quote(separator))));
        while (parts.size() > 2) {
            parts.remove(parts.size() / 2);
            String candidate = String.join(separator,
                    parts.subList(0, parts.size() / 2))
                    + separator + "…" + separator
                    + String.join(separator, parts.subList(parts.size() / 2, parts.size()));
            if (eyebrowLayout(g, candidate).getAdvance() <= CONTENT_WIDTH) {
                return candidate;
            }
        }
        return parts.isEmpty() ? eyebrow : parts.get(0);
    }

    /**
     * A soft halo in the text's own colour, drawn as two widening strokes underneath it.
     *
     * <p>Java2D has no blur, and a real one would be wasted here anyway: this is read at
     * WhatsApp thumbnail size as often as at full size, where a wide soft glow turns to
     * haze. One thin low-alpha outline gives the letters a little weight against the
     * navy. An earlier pair of wider strokes read as an actual glow, which was too much.
     */
    private static void glow(Graphics2D g, TextLayout layout, Color colour, float x, float y) {
        Shape outline = layout.getOutline(AffineTransform.getTranslateInstance(x, y));
        Object hint = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (float[] pass : new float[][]{{1.8f, 30f}}) {
            g.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(),
                    Math.round(pass[1])));
            g.setStroke(new BasicStroke(pass[0], BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(outline);
        }
        if (hint != null) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, hint);
        }
    }

    // ── Body ────────────────────────────────────────────────────────────────

    /**
     * The type sizes tried, largest first. Above 1.0 so a two-line narration fills the
     * card instead of floating in the middle of it; floored well short of unreadable,
     * because a card is a teaser and dropping a clause is better than shrinking the whole
     * thing past what survives a feed thumbnail.
     */
    private static final float[] TYPE_SCALES = {1.34f, 1.20f, 1.08f, 1f, 0.93f, 0.87f};

    /**
     * Lays out the matn in whichever languages the narration actually has.
     *
     * <p>Each block's line budget is derived from the space actually left for it, so a
     * layout can never overrun into the footer or under the frame — an earlier version
     * searched sizes and accepted whatever was smallest when nothing fitted, and the
     * longest narrations drew their last English line straight through the footer rule.
     *
     * <p>Within that, the search takes the largest type size that needs no truncation, and
     * the smallest one otherwise: bigger type means fewer, shorter lines, so preferring
     * size unconditionally would cut a narration in half to gain four points of leading.
     *
     * <p>The result is centred vertically, which is what keeps a short narration and a
     * single-language one from sitting in the top third of an otherwise empty card.
     */
    private void paintBody(Graphics2D g, Palette palette, String arabic, String english,
                           int height, boolean full) {
        FontRenderContext frc = g.getFontRenderContext();
        boolean hasArabic = arabic != null && !arabic.isBlank();
        boolean hasEnglish = english != null && !english.isBlank();
        if (!hasArabic && !hasEnglish) {
            return;
        }

        int bodyBottom = height - BODY_BOTTOM_INSET;
        Laid laid = layout(frc, arabic, english, height, full);
        Block ar = laid.arabic();
        Block en = laid.english();

        float total = height(ar) + (hasArabic && hasEnglish ? DIVIDER_BAND : 0) + height(en);
        float y = BODY_TOP + Math.max(0, (bodyBottom - BODY_TOP - total) / 2f);

        if (ar != null) {
            g.setColor(palette.arabicInk());
            draw(g, ar, y, true);
            y += height(ar);
        }
        if (hasArabic && hasEnglish) {
            paintOrnamentRule(g, palette, y + DIVIDER_BAND / 2f);
            y += DIVIDER_BAND;
        }
        if (en != null) {
            g.setColor(palette.englishInk());
            draw(g, en, y, false);
        }
    }

    /**
     * How tall the card has to be for this narration to fit whole.
     *
     * <p>Measured against a scratch context because the real one does not exist until the
     * image does, and the image cannot be made until this answer is known. The type sizes
     * are the ones {@link #paintBody} uses at scale 1, so what is measured here is what
     * gets drawn.
     */
    private int fullHeight(String arabic, String english) {
        boolean hasArabic = arabic != null && !arabic.isBlank();
        boolean hasEnglish = english != null && !english.isBlank();
        if (!hasArabic && !hasEnglish) {
            return HEIGHT;
        }
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scratch.createGraphics();
        try {
            FontRenderContext frc = g.getFontRenderContext();
            float arabicBase = hasEnglish ? 32f : 37f;
            float englishBase = hasArabic ? 24f : 29f;
            Block ar = hasArabic
                    ? block(arabic, true, arabicBase, Integer.MAX_VALUE, 1.58f, frc) : null;
            Block en = hasEnglish
                    ? block(english, false, englishBase, Integer.MAX_VALUE, 1.46f, frc) : null;
            float content = height(ar) + (hasArabic && hasEnglish ? DIVIDER_BAND : 0) + height(en);
            int needed = Math.round(BODY_TOP + content) + BODY_BOTTOM_INSET;
            return Math.max(HEIGHT, Math.min(MAX_FULL_HEIGHT, needed));
        } finally {
            g.dispose();
        }
    }

    /** The two text blocks as they will be drawn. */
    private record Laid(Block arabic, Block english) {
        boolean truncated() {
            return (arabic != null && arabic.truncated()) || (english != null && english.truncated());
        }
    }

    /**
     * Chooses a type size and a line budget for each block.
     *
     * <p>Arabic takes the larger type: it is the primary text and the part that makes a
     * card recognisable in a feed before anyone reads a word of it. Alone, each language
     * gets the whole body and more lines. Amiri is a naskh face that sits low in its em,
     * so it reads smaller than these numbers suggest beside the Latin.
     */
    private Laid layout(FontRenderContext frc, String arabic, String english,
                        int height, boolean full) {
        boolean hasArabic = arabic != null && !arabic.isBlank();
        boolean hasEnglish = english != null && !english.isBlank();
        int bodyBottom = height - BODY_BOTTOM_INSET;
        float available = bodyBottom - BODY_TOP - (hasArabic && hasEnglish ? DIVIDER_BAND : 0);
        float arabicBase = hasEnglish ? 32f : 37f;
        float englishBase = hasArabic ? 24f : 29f;
        // A full card was sized to hold everything, so the caps that keep a 630px card
        // balanced would be the only thing still cutting the narration short.
        int arabicCap = full ? Integer.MAX_VALUE : (hasEnglish ? 7 : 11);
        int englishCap = full ? Integer.MAX_VALUE : (hasArabic ? 8 : 13);

        Block ar = null;
        Block en = null;
        for (float scale : TYPE_SCALES) {
            float arabicSize = arabicBase * scale;
            float englishSize = englishBase * scale;
            float arabicLeading = arabicSize * 1.58f;
            float englishLeading = englishSize * 1.46f;

            // What each language would take if it had the card to itself. Asking first,
            // rather than laying one out and giving the other the remainder, is the whole
            // point: the remainder rule handed Arabic five lines and English two, and got
            // worse as the type shrank — seven and two — because the reserve was a flat
            // two lines rather than a share.
            Block wholeArabic = hasArabic
                    ? block(arabic, true, arabicSize, Integer.MAX_VALUE, 1.58f, frc) : null;
            Block wholeEnglish = hasEnglish
                    ? block(english, false, englishSize, Integer.MAX_VALUE, 1.46f, frc) : null;
            float needArabic = wholeArabic == null ? 0 : wholeArabic.lines().size() * arabicLeading;
            float needEnglish = wholeEnglish == null ? 0 : wholeEnglish.lines().size() * englishLeading;

            if (needArabic + needEnglish <= available) {
                ar = wholeArabic;
                en = wholeEnglish;
                break;
            }

            // Neither fits, so split the body evenly and hand back whatever one of them
            // does not want. A short translation beside a long matn keeps all of itself
            // and the matn takes the rest; two long texts are cut by the same measure.
            float shareArabic = hasArabic && hasEnglish ? available / 2f : available;
            float shareEnglish = hasArabic && hasEnglish ? available / 2f : available;
            if (hasArabic && hasEnglish) {
                if (needArabic < shareArabic) {
                    shareEnglish += shareArabic - needArabic;
                    shareArabic = needArabic;
                } else if (needEnglish < shareEnglish) {
                    shareArabic += shareEnglish - needEnglish;
                    shareEnglish = needEnglish;
                }
            }

            Block a = hasArabic
                    ? block(arabic, true, arabicSize,
                            budget(shareArabic, arabicLeading, arabicCap), 1.58f, frc)
                    : null;
            Block e = hasEnglish
                    ? block(english, false, englishSize,
                            budget(shareEnglish, englishLeading, englishCap), 1.46f, frc)
                    : null;
            ar = a;
            en = e;
            if (!truncated(a) && !truncated(e)) {
                break;
            }
        }
        return new Laid(ar, en);
    }

    /**
     * Whether the default card would cut this narration short.
     *
     * <p>The share dialog asks so it can drop the trimmed/full choice when there is
     * nothing to choose between — most narrations are short enough that both settings
     * produce the same image, and a control that does nothing is worse than no control.
     */
    public boolean truncates(Card card, Options options) {
        Options opts = options == null ? Options.DEFAULT : options;
        String arabic = opts.language() == Language.ENGLISH ? null : card.arabic();
        String english = opts.language() == Language.ARABIC ? null : card.english();
        if ((arabic == null || arabic.isBlank()) && (english == null || english.isBlank())) {
            return false;
        }
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scratch.createGraphics();
        try {
            return layout(g.getFontRenderContext(), arabic, english, HEIGHT, false).truncated();
        } finally {
            g.dispose();
        }
    }

    /** How many lines of the given leading fit in {@code space}, at least one and at most the cap. */
    private static int budget(float space, float leading, int cap) {
        return Math.max(1, Math.min(cap, (int) Math.floor(space / leading)));
    }

    private static boolean truncated(Block block) {
        return block != null && block.truncated();
    }

    private static float height(Block block) {
        return block == null ? 0f : block.lines().size() * block.lineHeight();
    }

    /** Line — ornament — line, the divider the site draws inside every hadith card. */
    private void paintOrnamentRule(Graphics2D g, Palette palette, float centreY) {
        FontRenderContext frc = g.getFontRenderContext();
        TextLayout mark = new TextLayout(ORNAMENT, fonts.arabic().deriveFont(24f), frc);
        float markWidth = mark.getAdvance();
        float ruleWidth = 190;
        float gap = 16;
        float totalWidth = ruleWidth * 2 + gap * 2 + markWidth;
        float left = (WIDTH - totalWidth) / 2f;

        rule(g, palette, left, centreY, ruleWidth, false);
        g.setColor(palette.ornament());
        mark.draw(g, left + ruleWidth + gap, centreY + 8);
        rule(g, palette, left + ruleWidth + gap * 2 + markWidth, centreY, ruleWidth, true);
    }

    private static void rule(Graphics2D g, Palette palette, float x, float y, float width,
                             boolean fadeRight) {
        Color solid = palette.ornament();
        Color from = fadeRight ? solid : transparent(solid);
        Color to = fadeRight ? transparent(solid) : solid;
        g.setPaint(new LinearGradientPaint(new Point2D.Float(x, y), new Point2D.Float(x + width, y),
                new float[]{0f, 1f}, new Color[]{from, to}));
        g.fill(new Rectangle2D.Float(x, y, width, 1.4f));
    }

    private void draw(Graphics2D g, Block block, float top, boolean rtl) {
        float baseline = top + block.ascent();
        for (TextLayout line : block.lines()) {
            float x = rtl ? WIDTH - PAD - line.getAdvance() : PAD;
            line.draw(g, x, baseline);
            baseline += block.lineHeight();
        }
    }

    // ── Footer ──────────────────────────────────────────────────────────────

    private void paintFooter(Graphics2D g, Palette palette, String footer, int height) {
        float ruleY = height - FOOTER_RULE_INSET;
        float centreY = height - FOOTER_CENTRE_INSET;
        Color hairline = palette.footerRule();
        g.setPaint(new LinearGradientPaint(
                new Point2D.Float(PAD, ruleY), new Point2D.Float(WIDTH - PAD, ruleY),
                new float[]{0f, 0.5f, 1f},
                new Color[]{transparent(hairline), hairline, transparent(hairline)}));
        g.fill(new Rectangle2D.Float(PAD, ruleY, CONTENT_WIDTH, 1f));

        BufferedImage logo = palette.logo();
        if (logo != null) {
            int width = Math.round(LOGO_HEIGHT * (float) logo.getWidth() / logo.getHeight());
            g.drawImage(logo, PAD, Math.round(centreY) - LOGO_HEIGHT / 2, width, LOGO_HEIGHT, null);
        }

        if (footer == null || footer.isBlank()) {
            return;
        }
        TextLayout label = new TextLayout(
                fonts.runs(footer, fonts.latin().deriveFont(21f), fonts.arabic().deriveFont(21f),
                        false, 0.03f).getIterator(),
                g.getFontRenderContext());
        float labelX = WIDTH - PAD - label.getAdvance();
        glow(g, label, palette.footerInk(), labelX, centreY + 7);
        g.setColor(palette.footerInk());
        label.draw(g, labelX, centreY + 7);
    }

    // ── Text layout ─────────────────────────────────────────────────────────

    /** A wrapped paragraph: the laid-out lines plus the metrics needed to stack them. */
    private record Block(List<TextLayout> lines, float lineHeight, float ascent,
                         boolean truncated) {
    }

    /**
     * Greedy word wrap, capped at {@code maxLines} with an ellipsis on the last one.
     *
     * <p>The break is always on whitespace — a card is a teaser and the page has the rest,
     * so cutting an Arabic word in half to save four pixels buys nothing and looks broken.
     */
    private Block block(String text, boolean rtl, float size, int maxLines, float leadingRatio,
                        FontRenderContext frc) {
        Font primary = (rtl ? fonts.arabic() : fonts.latin()).deriveFont(size);
        Font fallback = (rtl ? fonts.latin() : fonts.arabic()).deriveFont(size);

        List<String> lines = new ArrayList<>();
        String[] words = text.trim().split("\\s+");
        StringBuilder current = new StringBuilder();
        boolean truncated = false;

        for (String word : words) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (advance(candidate, primary, fallback, rtl, frc) <= CONTENT_WIDTH) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (current.isEmpty()) {
                // A single unbreakable token wider than the card. Nothing in the corpus
                // does this today, but a URL in a note would, and a hard cut beats a
                // line that runs off the edge.
                lines.add(clip(word, primary, fallback, rtl, frc));
            } else {
                lines.add(current.toString());
                current.setLength(0);
                current.append(word);
            }
            if (lines.size() == maxLines) {
                truncated = true;
                break;
            }
        }
        if (!truncated && !current.isEmpty()) {
            lines.add(current.toString());
        }
        if (truncated && !lines.isEmpty()) {
            lines.set(lines.size() - 1,
                    ellipsise(lines.get(lines.size() - 1), primary, fallback, rtl, frc));
        }

        List<TextLayout> laid = new ArrayList<>(lines.size());
        float ascent = 0;
        for (String line : lines) {
            TextLayout layout = layout(line, primary, fallback, rtl, frc);
            laid.add(layout);
            ascent = Math.max(ascent, layout.getAscent());
        }
        return new Block(laid, size * leadingRatio, ascent, truncated);
    }

    /** Drops trailing words until the line plus an ellipsis fits. */
    private String ellipsise(String line, Font primary, Font fallback, boolean rtl,
                             FontRenderContext frc) {
        String candidate = line;
        while (advance(candidate + "…", primary, fallback, rtl, frc) > CONTENT_WIDTH) {
            int cut = candidate.lastIndexOf(' ');
            if (cut <= 0) {
                break;
            }
            candidate = candidate.substring(0, cut);
        }
        return candidate + "…";
    }

    private String clip(String word, Font primary, Font fallback, boolean rtl, FontRenderContext frc) {
        String candidate = word;
        while (candidate.length() > 1
                && advance(candidate, primary, fallback, rtl, frc) > CONTENT_WIDTH) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate;
    }

    private float advance(String text, Font primary, Font fallback, boolean rtl,
                          FontRenderContext frc) {
        return layout(text, primary, fallback, rtl, frc).getAdvance();
    }

    private TextLayout layout(String text, Font primary, Font fallback, boolean rtl,
                              FontRenderContext frc) {
        return new TextLayout(fonts.runs(text, primary, fallback, rtl, null).getIterator(), frc);
    }

    // ── Encoding ────────────────────────────────────────────────────────────

    private static byte[] encode(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(200_000);
        try {
            // No disk cache: these are small and the container's /tmp is a volume.
            ImageIO.setUseCache(false);
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode the share card", e);
        }
        return out.toByteArray();
    }

    private static BufferedImage loadImage(String classpathLocation) {
        try (InputStream in = new ClassPathResource(classpathLocation).getInputStream()) {
            return ImageIO.read(in);
        } catch (Exception e) {
            // A card without the mark still carries the domain, so this is not fatal.
            LOGGER.warn("Could not load {}; share cards will render without the mark",
                    classpathLocation, e);
            return null;
        }
    }

    /**
     * The three bundled faces, plus the per-character run splitting they need.
     */
    private static final class CardFonts {

        /*
         * Noto Naskh Arabic, and the choice is made by the shaper rather than by taste.
         * Java2D applies neither OpenType GSUB nor GPOS: it shapes Arabic by mapping to
         * the legacy Presentation Forms-B block and stacks combining marks by their own
         * metrics. A face has to survive both to be usable here, and the obvious
         * candidates do not:
         *
         *   Scheherazade New — the face the site itself uses. Carries none of the
         *       presentation forms, so the mandatory lam-alif ligature never formed and
         *       لا rendered as two separate strokes.
         *   Amiri — has the presentation forms, so it ligates correctly, but positions
         *       every mark through GPOS. The harakat floated clear of their letters.
         *   DroidKufi — correct on both counts, but kufi: geometric, heavy, and hard to
         *       read at length. It is what this replaced.
         *
         * Noto Naskh has the full presentation-forms range and mark metrics that stand up
         * without GPOS. Check any replacement for U+FEFB/U+FEFC and look at the harakat
         * before adopting it; both failures are silent.
         */

        private final Font arabic;
        private final Font latin;
        private final Font latinBold;

        private CardFonts() {
            this.arabic = load("NotoNaskhArabic-Regular.ttf");
            this.latin = load("SourceSerif4-Regular.ttf");
            this.latinBold = load("SourceSerif4-Semibold.ttf");
        }

        Font arabic() {
            return arabic;
        }

        Font latin() {
            return latin;
        }

        Font latinBold() {
            return latinBold;
        }

        private static Font load(String name) {
            try (InputStream in = new ClassPathResource("static/fonts/" + name).getInputStream()) {
                return Font.createFont(Font.TRUETYPE_FONT, in);
            } catch (IOException | FontFormatException e) {
                throw new IllegalStateException("Missing share-card font " + name, e);
            }
        }

        /**
         * Tags each run of characters with the font that can actually draw it.
         *
         * <p>Neither face covers the other's script, and the corpus mixes them: 711
         * English fields quote Arabic inline, and Arabic matn carries ASCII numbering.
         * Splitting on coverage keeps shaping intact inside each run — Arabic words are
         * shaped independently either side of a space anyway — while letting the other
         * script through.
         */
        AttributedString runs(String text, Font primary, Font fallback, boolean rtl, Float tracking) {
            AttributedString styled = new AttributedString(text);
            styled.addAttribute(TextAttribute.RUN_DIRECTION, rtl
                    ? TextAttribute.RUN_DIRECTION_RTL : TextAttribute.RUN_DIRECTION_LTR);
            if (tracking != null) {
                styled.addAttribute(TextAttribute.TRACKING, tracking);
            }
            int start = 0;
            while (start < text.length()) {
                Font run = fontFor(text.codePointAt(start), primary, fallback);
                int end = start;
                while (end < text.length()) {
                    int codePoint = text.codePointAt(end);
                    if (fontFor(codePoint, primary, fallback) != run) {
                        break;
                    }
                    end += Character.charCount(codePoint);
                }
                styled.addAttribute(TextAttribute.FONT, run, start, end);
                start = end;
            }
            return styled;
        }

        private static Font fontFor(int codePoint, Font primary, Font fallback) {
            if (primary.canDisplay(codePoint) || !fallback.canDisplay(codePoint)) {
                return primary;
            }
            return fallback;
        }
    }
}
