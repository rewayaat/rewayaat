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
 * <p>DroidKufi has no Latin glyphs at all and Source Serif has no Arabic ones, so text is
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
    private static final int BODY_BOTTOM = 496;
    private static final int FOOTER_RULE_Y = 520;
    private static final int FOOTER_CENTRE_Y = 556;
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
        Palette palette = theme == Theme.LIGHT ? light : dark;
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            paintGround(g, palette);
            paintFrame(g, palette);
            paintEyebrow(g, palette, card.eyebrow());
            paintBody(g, palette, card.arabic(), card.english());
            paintFooter(g, palette, card.footer());
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
                new Color(243, 229, 184, 214),
                new Color(255, 255, 255),
                new Color(255, 255, 255, 233),
                new Color(gold.getRed(), gold.getGreen(), gold.getBlue(), 210),
                new Color(243, 229, 184, 64),
                new Color(243, 229, 184, 168),
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

    private void paintGround(Graphics2D g, Palette palette) {
        g.setPaint(new LinearGradientPaint(new Point2D.Float(0, 0), new Point2D.Float(WIDTH, HEIGHT),
                palette.groundStops(), palette.ground()));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // The two light pools from .hub-hero::before. Without them the ground reads as one
        // flat block, which at feed thumbnail size looks like a rendering failure.
        pool(g, 0.25f * WIDTH, 0.08f * HEIGHT, 0.42f * WIDTH, 0.42f * HEIGHT,
                palette.poolHighlight());
        pool(g, 0.78f * WIDTH, 0.92f * HEIGHT, 0.36f * WIDTH, 0.36f * HEIGHT,
                palette.poolAccent());
    }

    /** An elliptical light pool; the rectangle form of the paint is what makes it an ellipse. */
    private static void pool(Graphics2D g, float cx, float cy, float rx, float ry, Color colour) {
        g.setPaint(new RadialGradientPaint(
                new Rectangle2D.Float(cx - rx, cy - ry, rx * 2, ry * 2),
                new float[]{0f, 1f}, new Color[]{colour, transparent(colour)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fillRect(0, 0, WIDTH, HEIGHT);
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
    private void paintFrame(Graphics2D g, Palette palette) {
        float outer = FRAME_INSET;
        float inner = FRAME_INSET + FRAME_GAP;

        g.setColor(palette.frame());
        g.setStroke(new BasicStroke(2f));
        g.draw(new Rectangle2D.Float(outer, outer, WIDTH - 2 * outer, HEIGHT - 2 * outer));

        g.setColor(palette.frameHairline());
        g.setStroke(new BasicStroke(1f));
        g.draw(new Rectangle2D.Float(inner, inner, WIDTH - 2 * inner, HEIGHT - 2 * inner));

        g.setColor(palette.cornerOrnament());
        for (float x : new float[]{outer, WIDTH - outer}) {
            for (float y : new float[]{outer, HEIGHT - outer}) {
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
        g.setColor(palette.eyebrow());
        AttributedString text = fonts.runs(eyebrow, fonts.latinBold().deriveFont(21f),
                fonts.arabic().deriveFont(21f), false, 0.16f);
        new TextLayout(text.getIterator(), g.getFontRenderContext()).draw(g, PAD, EYEBROW_BASELINE);
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
    private void paintBody(Graphics2D g, Palette palette, String arabic, String english) {
        FontRenderContext frc = g.getFontRenderContext();
        boolean hasArabic = arabic != null && !arabic.isBlank();
        boolean hasEnglish = english != null && !english.isBlank();
        if (!hasArabic && !hasEnglish) {
            return;
        }

        float available = BODY_BOTTOM - BODY_TOP - (hasArabic && hasEnglish ? DIVIDER_BAND : 0);
        // Arabic takes the larger type: it is the primary text and the part that makes a
        // card recognisable in a feed before anyone reads a word of it. Alone, each
        // language gets the whole body and more lines.
        float arabicBase = hasEnglish ? 44f : 52f;
        float englishBase = hasArabic ? 28f : 33f;
        int arabicCap = hasEnglish ? 3 : 5;
        int englishCap = hasArabic ? 5 : 8;

        Block ar = null;
        Block en = null;
        for (float scale : TYPE_SCALES) {
            float arabicLeading = arabicBase * scale * 1.72f;
            float englishLeading = englishBase * scale * 1.46f;
            // Arabic may take everything except two lines the English is always owed, so
            // a long narration cannot squeeze the translation down to a single clause.
            float reservedForEnglish = hasEnglish ? 2 * englishLeading : 0;
            Block a = hasArabic
                    ? block(arabic, true, arabicBase * scale,
                            budget(available - reservedForEnglish, arabicLeading, arabicCap),
                            1.72f, frc)
                    : null;
            Block e = hasEnglish
                    ? block(english, false, englishBase * scale,
                            budget(available - height(a), englishLeading, englishCap), 1.46f, frc)
                    : null;
            ar = a;
            en = e;
            if (!truncated(a) && !truncated(e)) {
                break;
            }
        }

        float total = height(ar) + (hasArabic && hasEnglish ? DIVIDER_BAND : 0) + height(en);
        float y = BODY_TOP + Math.max(0, (BODY_BOTTOM - BODY_TOP - total) / 2f);

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

    private void paintFooter(Graphics2D g, Palette palette, String footer) {
        Color hairline = palette.footerRule();
        g.setPaint(new LinearGradientPaint(
                new Point2D.Float(PAD, FOOTER_RULE_Y), new Point2D.Float(WIDTH - PAD, FOOTER_RULE_Y),
                new float[]{0f, 0.5f, 1f},
                new Color[]{transparent(hairline), hairline, transparent(hairline)}));
        g.fill(new Rectangle2D.Float(PAD, FOOTER_RULE_Y, CONTENT_WIDTH, 1f));

        BufferedImage logo = palette.logo();
        if (logo != null) {
            int width = Math.round(LOGO_HEIGHT * (float) logo.getWidth() / logo.getHeight());
            g.drawImage(logo, PAD, FOOTER_CENTRE_Y - LOGO_HEIGHT / 2, width, LOGO_HEIGHT, null);
        }

        if (footer == null || footer.isBlank()) {
            return;
        }
        g.setColor(palette.footerInk());
        TextLayout label = new TextLayout(
                fonts.runs(footer, fonts.latin().deriveFont(21f), fonts.arabic().deriveFont(21f),
                        false, 0.03f).getIterator(),
                g.getFontRenderContext());
        label.draw(g, WIDTH - PAD - label.getAdvance(), FOOTER_CENTRE_Y + 7);
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

        private final Font arabic;
        private final Font latin;
        private final Font latinBold;

        private CardFonts() {
            this.arabic = load("DroidKufi-Regular.ttf");
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
