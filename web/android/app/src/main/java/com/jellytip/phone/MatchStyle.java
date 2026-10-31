package com.jellytip.phone;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Match info, more pop (~/ai/plans/jellytip-live-sport.md, "Pinned 31-Oct (match info: more pop, plus coverage)"): team
 * colours (neutral greys without them), the diagonal two-tone hero background, implied odds, form letters, countdown
 * text and a small image download for crests and the venue photo.
 */
final class MatchStyle {

    private static final int NEUTRAL_HOME = 0xFF4A4E58;
    private static final int NEUTRAL_AWAY = 0xFF2E3138;

    private MatchStyle() {}

    /** "#0B2265" / "0B2265" -> ARGB; anything else = [fallback]. */
    static int color(String hex, int fallback) {
        if (hex == null) return fallback;
        String h = hex.trim().replaceFirst("^#", "");
        if (h.length() != 6) return fallback;
        try {
            return 0xFF000000 | Integer.parseInt(h, 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String hex(JSONObject t, String key) {
        if (t == null || !t.has(key) || t.isNull(key)) return null;
        String s = t.optString(key);
        return s.isEmpty() ? null : s;
    }

    /** {home, away}; the away side uses its alternate when both teams share a colour. */
    static int[] colors(JSONObject home, JSONObject away) {
        String hc = hex(home, "color");
        String ac = hex(away, "color");
        int h = color(hc, NEUTRAL_HOME);
        int a = hc != null && hc.equalsIgnoreCase(ac) ? color(hex(away, "altColor"), NEUTRAL_AWAY) : color(ac, NEUTRAL_AWAY);
        return new int[] {h, a};
    }

    /** 45% towards black (the hero background, for white text). */
    static int darken(int c) {
        return mix(c, 0xFF000000, 0.45f);
    }

    /** 18% towards white (bars and dots on the dark glass). */
    static int brighten(int c) {
        return mix(c, 0xFFFFFFFF, 0.18f);
    }

    /** 35% towards white (the crest glow). */
    static int lighten(int c) {
        return mix(c, 0xFFFFFFFF, 0.35f);
    }

    private static int mix(int c, int to, float f) {
        int r = Math.round(((c >> 16) & 0xFF) * (1 - f) + ((to >> 16) & 0xFF) * f);
        int g = Math.round(((c >> 8) & 0xFF) * (1 - f) + ((to >> 8) & 0xFF) * f);
        int b = Math.round((c & 0xFF) * (1 - f) + (to & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static String initials(String name) {
        if (name == null || name.trim().isEmpty()) return "?";
        String[] w = name.trim().split("\\s+");
        String s = w.length > 1 ? "" + w[0].charAt(0) + w[w.length - 1].charAt(0) : w[0].substring(0, Math.min(2, w[0].length()));
        return s.toUpperCase(Locale.ROOT);
    }

    /** "Starts in 2h 14m" / "Starts in 25m" / "Starting now"; a day or more away = "Starts Sat 19:30". */
    static String countdown(long startsMs, long nowMs) {
        long mins = (startsMs - nowMs + 59_999) / 60_000;
        if (mins <= 0) return "Starting now";
        if (mins >= 24 * 60) return "Starts " + new java.text.SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(new java.util.Date(startsMs));
        if (mins >= 60) return "Starts in " + (mins / 60) + "h" + (mins % 60 > 0 ? " " + (mins % 60) + "m" : "");
        return "Starts in " + mins + "m";
    }

    /** American (-150 / +130) or decimal (1.85) odds -> implied probability; null = unreadable. */
    static Double impliedProb(String s) {
        if (s == null) return null;
        String raw = s.trim().replace('−', '-');
        double v;
        try {
            v = Double.parseDouble(raw.startsWith("+") ? raw.substring(1) : raw);
        } catch (NumberFormatException e) {
            return null;
        }
        boolean signed = raw.startsWith("+") || raw.startsWith("-");
        if (v < 0) return -v / (-v + 100);
        if (signed || v >= 100) return 100 / (v + 100);
        if (v > 1) return 1 / v;
        return null;
    }

    /** Form entries as single letters (an entry may be a run like "WWLDW"). */
    static List<String> formLetters(List<String> form) {
        List<String> out = new ArrayList<>();
        for (String f : form) {
            String u = f.toUpperCase(Locale.ROOT);
            if (u.length() > 1 && u.matches("[WLD]+")) {
                for (char ch : u.toCharArray()) out.add(String.valueOf(ch));
            } else {
                out.add(u.substring(0, Math.min(1, u.length())));
            }
        }
        return out;
    }

    static int formColor(String r) {
        if ("W".equals(r)) return 0xFF2E9E5B;
        if ("L".equals(r)) return 0xFFD64545;
        return 0xFF6B6F78;
    }

    /** Blocking; off the main thread. Decoded down to about [maxPx] on the long side; null = failed. */
    static Bitmap download(String url, String token, String companionBase, int maxPx) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8_000);
            c.setReadTimeout(8_000);
            // Companion links carry the token (signed image links ignore it); third-party hosts never get it
            if (token != null && !token.isEmpty() && url.startsWith(companionBase)) {
                c.setRequestProperty("Authorization", "MediaBrowser Token=\"" + token + "\"");
            }
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            byte[] bytes = out.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, d);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The hero background: home colour, with the away colour on a diagonal from 58% (top) to 42% (bottom). */
    static final class SplitDrawable extends Drawable {
        private final Paint home = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint away = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius;

        SplitDrawable(int homeColor, int awayColor, float radius) {
            home.setColor(homeColor);
            away.setColor(awayColor);
            this.radius = radius;
        }

        @Override
        public void draw(Canvas canvas) {
            float w = getBounds().width();
            float h = getBounds().height();
            Path clip = new Path();
            clip.addRoundRect(new RectF(0, 0, w, h), radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawRect(0, 0, w, h, home);
            Path p = new Path();
            p.moveTo(w * 0.58f, 0);
            p.lineTo(w, 0);
            p.lineTo(w, h);
            p.lineTo(w * 0.42f, h);
            p.close();
            canvas.drawPath(p, away);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            home.setAlpha(alpha);
            away.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            home.setColorFilter(cf);
            away.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
