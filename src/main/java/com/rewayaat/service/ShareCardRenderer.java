package com.rewayaat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
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

    private static final int EYEBROW_BASELINE = 76;
    private static final int BODY_TOP = 112;
    private static final int BODY_BOTTOM = 512;
    private static final int FOOTER_RULE_Y = 536;
    private static final int FOOTER_CENTRE_Y = 576;
    private static final int BOTTOM_RULE_HEIGHT = 5;

    /** Room for the ornament that separates the Arabic from the English. */
    private static final int DIVIDER_BAND = 48;

    // Design tokens, mirrored from manuscript.css. The card has to look like it came
    // from the site, so these track the ".hub-hero" gradient and the gold rule under it.
    private static final Color GOLD = new Color(0xc8a23d);
    private static final Color GOLD_LIGHT = new Color(0xf3e5b8);
    private static final Color EYEBROW = new Color(243, 229, 184, 214);
    private static final Color ARABIC_INK = new Color(255, 255, 255);
    private static final Color ENGLISH_INK = new Color(255, 255, 255, 233);
    private static final Color FOOTER_INK = new Color(243, 229, 184, 168);

    private static final float[] GROUND_STOPS = {0f, 0.20f, 0.50f, 0.80f, 1f};
    private static final Color[] GROUND_COLORS = {
            new Color(0x0a1628), new Color(0x0f2440), new Color(0x1e3a5f),
            new Color(0x1a4a7a), new Color(0x1e3a5f)};

    /** U+06DE, the same ornament the hadith card uses on the site. */
    private static final String ORNAMENT = "۞";

    private final CardFonts fonts = new CardFonts();
    private final BufferedImage logo = loadLogo();

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

    public byte[] render(Card card) {
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

            paintGround(g);
            paintEyebrow(g, card.eyebrow());
            paintBody(g, card.arabic(), card.english());
            paintFooter(g, card.footer());
        } finally {
            g.dispose();
        }
        return encode(image);
    }

    // ── Ground ──────────────────────────────────────────────────────────────

    private void paintGround(Graphics2D g) {
        g.setPaint(new LinearGradientPaint(new Point2D.Float(0, 0), new Point2D.Float(WIDTH, HEIGHT),
                GROUND_STOPS, GROUND_COLORS));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // The two light pools from .hub-hero::before. Without them the ground reads as a
        // flat block of navy, which at feed thumbnail size looks like a rendering failure.
        pool(g, 0.25f * WIDTH, 0.08f * HEIGHT, 0.42f * WIDTH, 0.42f * HEIGHT,
                new Color(255, 255, 255, 26));
        pool(g, 0.78f * WIDTH, 0.92f * HEIGHT, 0.36f * WIDTH, 0.36f * HEIGHT,
                new Color(200, 162, 61, 36));

        // The gold rule that closes every navy panel on this site (.hub-hero::after).
        g.setPaint(new LinearGradientPaint(
                new Point2D.Float(0, 0), new Point2D.Float(WIDTH, 0),
                new float[]{0f, 0.22f, 0.5f, 0.78f, 1f},
                new Color[]{transparent(GOLD), GOLD, GOLD_LIGHT, GOLD, transparent(GOLD)}));
        g.fillRect(0, HEIGHT - BOTTOM_RULE_HEIGHT, WIDTH, BOTTOM_RULE_HEIGHT);
    }

    /** An elliptical light pool; the rectangle form of the paint is what makes it an ellipse. */
    private static void pool(Graphics2D g, float cx, float cy, float rx, float ry, Color colour) {
        g.setPaint(new RadialGradientPaint(
                new Rectangle2D.Float(cx - rx, cy - ry, rx * 2, ry * 2),
                new float[]{0f, 1f}, new Color[]{colour, transparent(colour)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fillRect(0, 0, WIDTH, HEIGHT);
    }

    private static Color transparent(Color colour) {
        return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 0);
    }

    // ── Eyebrow ─────────────────────────────────────────────────────────────

    private void paintEyebrow(Graphics2D g, String eyebrow) {
        FontRenderContext frc = g.getFontRenderContext();
        float x = PAD;

        g.setColor(GOLD);
        TextLayout mark = new TextLayout(ORNAMENT, fonts.arabic().deriveFont(30f), frc);
        mark.draw(g, x, EYEBROW_BASELINE);
        x += mark.getAdvance() + 18;

        if (eyebrow == null || eyebrow.isBlank()) {
            return;
        }
        g.setColor(EYEBROW);
        AttributedString text = fonts.runs(eyebrow, fonts.latinBold().deriveFont(21f),
                fonts.arabic().deriveFont(21f), false, 0.16f);
        new TextLayout(text.getIterator(), frc).draw(g, x, EYEBROW_BASELINE);
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
     * layout can never overrun into the footer — an earlier version searched sizes and
     * accepted whatever was smallest when nothing fitted, and the longest narrations drew
     * their last English line straight through the footer rule.
     *
     * <p>Within that, the search takes the largest type size that needs no truncation, and
     * the smallest one otherwise: bigger type means fewer, shorter lines, so preferring
     * size unconditionally would cut a narration in half to gain four points of leading.
     *
     * <p>The result is centred vertically, which is what keeps a short narration and a
     * single-language one from sitting in the top third of an otherwise empty card.
     */
    private void paintBody(Graphics2D g, String arabic, String english) {
        FontRenderContext frc = g.getFontRenderContext();
        boolean hasArabic = arabic != null && !arabic.isBlank();
        boolean hasEnglish = english != null && !english.isBlank();
        if (!hasArabic && !hasEnglish) {
            return;
        }

        float available = BODY_BOTTOM - BODY_TOP - (hasArabic && hasEnglish ? DIVIDER_BAND : 0);
        // Arabic takes the larger type and a little over half the height: it is the primary
        // text and the part that makes a card recognisable in a feed before anyone reads a
        // word of it. Alone, each language gets the whole body and more lines.
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
            g.setColor(ARABIC_INK);
            draw(g, ar, y, true);
            y += height(ar);
        }
        if (hasArabic && hasEnglish) {
            paintOrnamentRule(g, y + DIVIDER_BAND / 2f);
            y += DIVIDER_BAND;
        }
        if (en != null) {
            g.setColor(ENGLISH_INK);
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
    private void paintOrnamentRule(Graphics2D g, float centreY) {
        FontRenderContext frc = g.getFontRenderContext();
        TextLayout mark = new TextLayout(ORNAMENT, fonts.arabic().deriveFont(24f), frc);
        float markWidth = mark.getAdvance();
        float ruleWidth = 190;
        float gap = 16;
        float totalWidth = ruleWidth * 2 + gap * 2 + markWidth;
        float left = (WIDTH - totalWidth) / 2f;

        rule(g, left, centreY, ruleWidth, false);
        g.setColor(new Color(200, 162, 61, 210));
        mark.draw(g, left + ruleWidth + gap, centreY + 8);
        rule(g, left + ruleWidth + gap * 2 + markWidth, centreY, ruleWidth, true);
    }

    private static void rule(Graphics2D g, float x, float y, float width, boolean fadeRight) {
        Color from = fadeRight ? GOLD : transparent(GOLD);
        Color to = fadeRight ? transparent(GOLD) : GOLD;
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

    private void paintFooter(Graphics2D g, String footer) {
        g.setPaint(new LinearGradientPaint(
                new Point2D.Float(PAD, FOOTER_RULE_Y), new Point2D.Float(WIDTH - PAD, FOOTER_RULE_Y),
                new float[]{0f, 0.5f, 1f},
                new Color[]{new Color(243, 229, 184, 46), new Color(243, 229, 184, 74),
                        new Color(243, 229, 184, 46)}));
        g.fill(new Rectangle2D.Float(PAD, FOOTER_RULE_Y, CONTENT_WIDTH, 1f));

        if (logo != null) {
            int height = 46;
            int width = Math.round(height * (float) logo.getWidth() / logo.getHeight());
            g.drawImage(logo, PAD, FOOTER_CENTRE_Y - height / 2, width, height, null);
        }

        if (footer == null || footer.isBlank()) {
            return;
        }
        g.setColor(FOOTER_INK);
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

    private static BufferedImage loadLogo() {
        try (InputStream in = new ClassPathResource("static/img/Alilogov2-transparent.png")
                .getInputStream()) {
            return ImageIO.read(in);
        } catch (Exception e) {
            // A card without the mark still carries the domain, so this is not fatal.
            LOGGER.warn("Could not load the ALI mark; share cards will render without it", e);
            return null;
        }
    }

    /**
     * The four bundled faces, plus the per-character run splitting they need.
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
