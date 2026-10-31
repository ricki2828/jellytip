package com.jellytip.phone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.TextView;
import androidx.core.content.res.ResourcesCompat;
import java.util.Locale;

/**
 * Direction A, Broadcast (~/ai/plans/jellytip-live-sport.md, "In-match visual direction: DECIDED 31-Oct"; mockups in
 * ~/ai/state/jellytip-match-design, section A (c)) for the native player's panels: Barlow Condensed caps for labels,
 * numbers and team names, Barlow for body text; network gold; angled full-colour slabs; the whole panel washed in the
 * two team colours (22% into #0D1015) with text picked by contrast; body text on near-black cards.
 */
final class Broadcast {

    static final int BASE = 0xFF0D1015;
    static final int GOLD = 0xFFFFC72C;
    static final int SECONDARY = 0xFFA9B0BC;
    static final int BODY = 0xFFC9CED6;
    static final int TEXT = 0xFFF5F6F8;
    static final int LINE = 0xFF232833;
    static final int CHIP = 0xFF1A1F28;
    static final int SUB = 0xFF151A22;
    static final int CARD = 0xD90D1015;
    static final int WIN = 0xFF22C55E;
    static final int LOSS = 0xFF3A414D;
    static final int INK = 0xFF111111;
    static final int LIVE = 0xFFFF3B3B;
    static final int PLAY_RULE = 0xFF1C212A;

    private static Typeface condMedium;
    private static Typeface condSemi;
    private static Typeface condBold;
    private static Typeface bodyMedium;
    private static Typeface bodySemi;
    private static Typeface bodyBold;

    private Broadcast() {}

    private static void load(Context c) {
        if (condBold != null) return;
        condMedium = font(c, R.font.barlow_condensed_medium);
        condSemi = font(c, R.font.barlow_condensed_semibold);
        condBold = font(c, R.font.barlow_condensed_bold);
        bodyMedium = font(c, R.font.barlow_medium);
        bodySemi = font(c, R.font.barlow_semibold);
        bodyBold = font(c, R.font.barlow_bold);
    }

    private static Typeface font(Context c, int id) {
        try {
            Typeface t = ResourcesCompat.getFont(c, id);
            return t == null ? Typeface.DEFAULT_BOLD : t;
        } catch (Exception e) {
            return Typeface.DEFAULT_BOLD;
        }
    }

    /** Barlow Condensed caps; [weight] 500 | 600 | 700. */
    static TextView cond(Context c, String s, float sp, int color, int weight) {
        TextView t = new TextView(c);
        styleCond(t, sp, color, weight);
        t.setText(s == null ? "" : s);
        return t;
    }

    static void styleCond(TextView t, float sp, int color, int weight) {
        load(t.getContext());
        t.setTypeface(weight >= 700 ? condBold : weight >= 600 ? condSemi : condMedium);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setAllCaps(true);
        t.setLetterSpacing(0.03f);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
    }

    /** Barlow body text. */
    static TextView body(Context c, String s, float sp, int color, int weight) {
        TextView t = new TextView(c);
        styleBody(t, sp, color, weight);
        t.setText(s == null ? "" : s);
        return t;
    }

    static void styleBody(TextView t, float sp, int color, int weight) {
        load(t.getContext());
        t.setTypeface(weight >= 700 ? bodyBold : weight >= 600 ? bodySemi : bodyMedium);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setAllCaps(false);
    }

    /** A gold section head ("WHERE TO WATCH") with a rule under it. */
    static TextView sectionHead(Context c, String s) {
        TextView t = cond(c, s, 13, GOLD, 700);
        t.setLetterSpacing(0.08f);
        int dp = Math.round(c.getResources().getDisplayMetrics().density);
        t.setPadding(0, 14 * dp, 0, 4 * dp);
        t.setBackground(new RuleDrawable(LINE, Math.max(1, dp)));
        return t;
    }

    /** Black or white, whichever contrasts more with [bg] (WCAG relative luminance). */
    static int on(int bg) {
        double l = luminance(bg);
        double vsWhite = 1.05 / (l + 0.05);
        double vsBlack = (l + 0.05) / 0.05;
        return vsBlack > vsWhite ? INK : 0xFFFFFFFF;
    }

    private static double luminance(int c) {
        double r = lin(((c >> 16) & 0xFF) / 255.0);
        double g = lin(((c >> 8) & 0xFF) / 255.0);
        double b = lin((c & 0xFF) / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double lin(double v) {
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** CIE76 ΔE between two colours (the clash rule: under 20, the away side takes its alt colour). */
    static double deltaE(int a, int b) {
        double[] x = lab(a);
        double[] y = lab(b);
        return Math.sqrt(Math.pow(x[0] - y[0], 2) + Math.pow(x[1] - y[1], 2) + Math.pow(x[2] - y[2], 2));
    }

    private static double[] lab(int c) {
        double r = lin(((c >> 16) & 0xFF) / 255.0);
        double g = lin(((c >> 8) & 0xFF) / 255.0);
        double b = lin((c & 0xFF) / 255.0);
        double x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047;
        double y = r * 0.2126 + g * 0.7152 + b * 0.0722;
        double z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883;
        return new double[] {116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z))};
    }

    private static double f(double t) {
        return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16.0 / 116;
    }

    /** [c] blended into the base by [k] (the wash uses 0.22). */
    static int blend(int c, float k) {
        int r = Math.round(((BASE >> 16) & 0xFF) * (1 - k) + ((c >> 16) & 0xFF) * k);
        int g = Math.round(((BASE >> 8) & 0xFF) * (1 - k) + ((c >> 8) & 0xFF) * k);
        int b = Math.round((BASE & 0xFF) * (1 - k) + (c & 0xFF) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** The two-tone wash (home left, away right, 22% into the base); plain base without team colours. */
    static Drawable wash(LiveTeams t, int alpha) {
        int h = t.known ? blend(t.homeColor, 0.22f) : BASE;
        int a = t.known ? blend(t.awayColor, 0.22f) : BASE;
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {h, h, a, a});
        d.setAlpha(alpha);
        return d;
    }

    /** A country's flag (ISO 2 letters, or ISO / IOC 3 letters mapped to 2) as emoji; null = unknown. */
    static String flag(String country) {
        if (country == null) return null;
        String c = country.trim().toUpperCase(Locale.ROOT);
        String two = null;
        if (c.length() == 2) {
            two = c;
        } else if (c.length() == 3) {
            for (String iso : Locale.getISOCountries()) {
                try {
                    if (new Locale("", iso).getISO3Country().equalsIgnoreCase(c)) {
                        two = iso;
                        break;
                    }
                } catch (java.util.MissingResourceException ignored) {
                    // no alpha-3 for it
                }
            }
            if (two == null) two = IOC.get(c);
        }
        if (two == null || two.length() != 2 || !Character.isLetter(two.charAt(0)) || !Character.isLetter(two.charAt(1))) return null;
        return new String(Character.toChars(0x1F1E6 + (two.charAt(0) - 'A'))) + new String(Character.toChars(0x1F1E6 + (two.charAt(1) - 'A')));
    }

    private static final java.util.Map<String, String> IOC = new java.util.HashMap<>();

    static {
        String[] pairs = {"GER", "DE", "SUI", "CH", "NED", "NL", "RSA", "ZA", "CRO", "HR", "GRE", "GR", "POR", "PT", "DEN", "DK", "BUL", "BG", "CHI", "CL", "URU", "UY", "PAR", "PY", "TPE", "TW", "INA", "ID", "MAS", "MY", "PHI", "PH", "LAT", "LV", "SLO", "SI", "ZIM", "ZW", "ENG", "GB", "SCO", "GB", "WAL", "GB", "NIR", "GB"};
        for (int i = 0; i < pairs.length; i += 2) IOC.put(pairs[i], pairs[i + 1]);
    }

    static String caps(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    /** A full-colour slab: left = straight left edge, right edge leaning in at the bottom; right = the mirror. */
    static final class Slab extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean left;
        private final Path path = new Path();

        Slab(int color, boolean left) {
            p.setColor(color);
            this.left = left;
        }

        void setColor(int c) {
            if (p.getColor() != c) {
                p.setColor(c);
                invalidateSelf();
            }
        }

        @Override
        public void draw(Canvas c) {
            float w = getBounds().width();
            float h = getBounds().height();
            path.reset();
            if (left) {
                path.moveTo(0, 0);
                path.lineTo(w, 0);
                path.lineTo(w * 0.94f, h);
                path.lineTo(0, h);
            } else {
                path.moveTo(w * 0.06f, 0);
                path.lineTo(w, 0);
                path.lineTo(w, h);
                path.lineTo(0, h);
            }
            path.close();
            c.drawPath(path, p);
        }

        @Override
        public void setAlpha(int a) {
            p.setAlpha(a);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            p.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** A rule along the bottom (section heads, tab rows); [underline] = a thicker coloured bar instead (selected tab). */
    static final class RuleDrawable extends Drawable {
        private final Paint p = new Paint();
        private final int height;

        RuleDrawable(int color, int height) {
            p.setColor(color);
            this.height = height;
        }

        @Override
        public void draw(Canvas c) {
            c.drawRect(getBounds().left, getBounds().bottom - height, getBounds().right, getBounds().bottom, p);
        }

        @Override
        public void setAlpha(int a) {
            p.setAlpha(a);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            p.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
