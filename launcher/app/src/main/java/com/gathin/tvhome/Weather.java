package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Current conditions and today's high/low beside the clock, from Open-Meteo (free, no key, no
 * account): the launcher's only weather network use. Ported from WidgeTV
 * (https://github.com/ashfaqhaq/widgetv, MIT License, Copyright (c) ashfaqhaq), trimmed to one
 * city. MainActivity calls maybeFetch() only while it is resumed (onResume and the minute tick,
 * which runs only while resumed), so nothing is fetched while another app is in front. No retries,
 * alarms or services: a failed fetch is tried again at a later stale check (at most every 10
 * minutes). The last result is cached in SharedPreferences together with the URL it came from, so
 * editing the city in a theme's config.json never shows the old city's numbers.
 */
final class Weather {
    private static final String TAG = "TvHome.Weather";
    private static final long STALE_MS = 30 * 60_000L, HIDE_MS = 3 * 60 * 60_000L, RETRY_GAP_MS = 10 * 60_000L;

    /** One reading, in the configured units. */
    static final class City {
        final int temp, code;
        final boolean day;
        /** Today's high/low in the city's own local day. */
        final int max, min;
        final boolean hasRange;

        City(int temp, int code, boolean day, int max, int min, boolean hasRange) {
            this.temp = temp;
            this.code = code;
            this.day = day;
            this.max = max;
            this.min = min;
            this.hasRange = hasRange;
        }
    }

    private final SharedPreferences prefs;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final boolean enabled;
    private final String url;
    private boolean inFlight;
    private long lastAttempt;

    Weather(Context c) {
        prefs = c.getSharedPreferences("weather", Context.MODE_PRIVATE);
        Config cfg = Config.load(c);
        enabled = cfg.weatherEnabled && !Double.isNaN(cfg.weatherLat) && !Double.isNaN(cfg.weatherLon);
        url = "https://api.open-meteo.com/v1/forecast?latitude=" + coord(cfg.weatherLat)
                + "&longitude=" + coord(cfg.weatherLon)
                + "&current=temperature_2m,weather_code,is_day"
                + "&daily=temperature_2m_max,temperature_2m_min"
                // Two days of hourly points: the curve needs the next 24 hours even late in the day.
                + "&hourly=temperature_2m,precipitation_probability,weather_code&timezone=auto&forecast_days=2"
                // config stores c/f; the API wants the full word.
                + "&temperature_unit=" + ("f".equals(cfg.weatherUnits) ? "fahrenheit" : "celsius");
    }

    private static String coord(double v) {
        return String.format(Locale.US, "%.4f", v);
    }

    /**
     * The reading, or null when weather is off, has no coordinates, was never fetched for the
     * current config, or is older than three hours (hidden rather than shown stale).
     */
    City current() {
        if (!enabled || !url.equals(prefs.getString("url", ""))) return null;
        long at = prefs.getLong("at", 0);
        if (at == 0 || System.currentTimeMillis() - at > HIDE_MS) return null;
        return new City(prefs.getInt("t", 0), prefs.getInt("c", 0), prefs.getBoolean("d", true),
                prefs.getInt("x", 0), prefs.getInt("n", 0), prefs.contains("x"));
    }

    /** Fetch if the cache is older than 30 minutes (or force); onDone runs on the main thread after a success. */
    void maybeFetch(boolean force, Runnable onDone) {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        boolean stale = !url.equals(prefs.getString("url", "")) || now - prefs.getLong("at", 0) > STALE_MS;
        if (inFlight || (!force && (!stale || now - lastAttempt < RETRY_GAP_MS))) return;
        inFlight = true;
        lastAttempt = now;
        Log.i(TAG, "weather: fetch start" + (force ? " (forced)" : ""));
        net.execute(() -> {
            boolean ok = fetch();
            main.post(() -> {
                inFlight = false;
                if (ok) onDone.run();
            });
        });
    }

    private boolean fetch() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setUseCaches(false);
            if (conn.getResponseCode() != 200) {
                Log.w(TAG, "weather: HTTP " + conn.getResponseCode());
                return false;
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) body.write(buf, 0, n);
            }
            String json = body.toString("UTF-8").trim();
            JSONObject cur = new JSONObject(json).getJSONObject("current");
            JSONObject daily = new JSONObject(json).getJSONObject("daily");
            JSONObject hourly = new JSONObject(json).getJSONObject("hourly");
            SharedPreferences.Editor e = prefs.edit();
            double t = cur.getDouble("temperature_2m");
            e.putInt("t", (int) Math.round(t))
                    .putInt("c", cur.getInt("weather_code"))
                    .putBoolean("d", cur.getInt("is_day") == 1)
                    .putInt("x", (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)))
                    .putInt("n", (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)));
            JSONArray hTimes = hourly.getJSONArray("time");
            JSONArray hTemps = hourly.getJSONArray("temperature_2m");
            JSONArray hPop = hourly.optJSONArray("precipitation_probability");
            JSONArray hCodes = hourly.optJSONArray("weather_code");
            StringBuilder ts = new StringBuilder(), cs = new StringBuilder(), ps = new StringBuilder(), ws = new StringBuilder();
            for (int i = 0; i < hTimes.length(); i++) {
                if (i > 0) { ts.append(','); cs.append(','); ps.append(','); ws.append(','); }
                ts.append(hTimes.getString(i));
                cs.append(hTemps.getDouble(i));
                ps.append(hPop == null ? -1 : Math.round(hPop.getDouble(i)));
                ws.append(hCodes == null ? 0 : hCodes.getInt(i));
            }
            e.putString("hT", ts.toString()).putString("hC", cs.toString()).putString("hP", ps.toString())
                    .putString("hW", ws.toString())
                    .putInt("hOff", new JSONObject(json).getInt("utc_offset_seconds"));
            e.putString("url", url).putLong("at", System.currentTimeMillis()).apply();
            Log.i(TAG, "weather: fetch ok here=" + t + " code=" + cur.getInt("weather_code")
                    + " is_day=" + (cur.getInt("is_day") == 1 ? 1 : 0)
                    + " max=" + daily.getJSONArray("temperature_2m_max").getDouble(0)
                    + " min=" + daily.getJSONArray("temperature_2m_min").getDouble(0)
                    + " hours=" + hTimes.length());
            return true;
        } catch (Exception ex) {
            Log.w(TAG, "weather: fetch failed: " + ex);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * The hourly points behind the 24-hour curve. Times are the city's local hours encoded as UTC
     * (parsed with the response's utc_offset_seconds), so the curve's window can be picked with one
     * arithmetic clock that does not depend on the TV's time zone.
     */
    static final class Hourly {
        final long[] atLocal;
        final float[] temps;
        /** Precipitation probability 0–100, -1 when the model gave none. */
        final int[] pop;
        final int[] codes;
        /** The city's seconds east of UTC, for turning the TV's now into the city's local scale. */
        final int offsetSec;

        Hourly(long[] atLocal, float[] temps, int[] pop, int[] codes, int offsetSec) {
            this.atLocal = atLocal;
            this.temps = temps;
            this.pop = pop;
            this.codes = codes;
            this.offsetSec = offsetSec;
        }
    }

    /** The hourly points from the same fetch as current(), or null when weather is hidden. */
    Hourly hourly() {
        if (!enabled || !url.equals(prefs.getString("url", ""))) return null;
        long at = prefs.getLong("at", 0);
        if (at == 0 || System.currentTimeMillis() - at > HIDE_MS) return null;
        String tS = prefs.getString("hT", ""), cS = prefs.getString("hC", ""), pS = prefs.getString("hP", "");
        if (tS.isEmpty()) return null;
        try {
            String[] ts = tS.split(",");
            String[] cs = cS.split(",");
            String[] ps = pS.isEmpty() ? new String[0] : pS.split(",");
            String[] ws = prefs.getString("hW", "").split(",");
            int off = prefs.getInt("hOff", 0);
            long[] atLocal = new long[ts.length];
            float[] temps = new float[ts.length];
            int[] pop = new int[ts.length];
            int[] codes = new int[ts.length];
            for (int i = 0; i < ts.length; i++) {
                // The ISO times are the city's local wall clock; encoding them as UTC minus the
                // offset gives a single monotonic scale for the window arithmetic below.
                atLocal[i] = java.time.LocalDateTime.parse(ts[i]).toEpochSecond(java.time.ZoneOffset.UTC);
                temps[i] = Float.parseFloat(cs[i]);
                pop[i] = i < ps.length ? Integer.parseInt(ps[i]) : -1;
                codes[i] = i < ws.length ? Integer.parseInt(ws[i]) : 0;
            }
            return new Hourly(atLocal, temps, pop, codes, off);
        } catch (Exception ex) {
            Log.w(TAG, "weather: bad hourly cache: " + ex);
            return null;
        }
    }

    /** Rain and snow families of the WMO table: things that fall out of the sky. */
    static boolean isWet(int code) {
        return (code >= 51 && code <= 67) || (code >= 71 && code <= 77) || (code >= 80 && code <= 82)
                || code == 85 || code == 86 || code >= 95;
    }

    /** The line's condition words for a WMO code (strings, see wx_cond_*). */
    static int conditionRes(int code) {
        if (code <= 1) return R.string.wx_cond_clear;
        if (code == 2) return R.string.wx_cond_partly;
        if (code == 3) return R.string.wx_cond_cloudy;
        if (code == 45 || code == 48) return R.string.wx_cond_fog;
        if (code >= 51 && code <= 57) return R.string.wx_cond_drizzle;
        if (code == 66 || code == 67) return R.string.wx_cond_freezing;
        if (code >= 71 && code <= 77) return R.string.wx_cond_snow;
        if (code == 85 || code == 86) return R.string.wx_cond_snow;
        if (code >= 95) return R.string.wx_cond_storm;
        if (code >= 80) return R.string.wx_cond_showers;
        return R.string.wx_cond_rain;
    }

    /**
     * What the next twelve hours bring, when the air is not doing it already: 0 nothing, 1 rain,
     * 2 snow — the first kind the model brings beats whatever comes later.
     */
    static int laterKind(Hourly h, long nowMillis) {
        if (h == null) return 0;
        long hourNow = Math.floorDiv(nowMillis / 1000L + h.offsetSec, 3600L) * 3600L;
        int seen = 0;
        for (int i = 0; i < h.atLocal.length && h.atLocal[i] < hourNow + 12 * 3600L; i++) {
            if (h.atLocal[i] < hourNow) continue;
            if (!isWet(h.codes[i])) continue;
            int kind = (h.codes[i] >= 71 && h.codes[i] <= 77) || h.codes[i] == 85 || h.codes[i] == 86 ? 2 : 1;
            if (seen == 0) seen = kind;
        }
        return seen;
    }

    /** WMO weather code to one of the seven thin-line icons. */
    static int icon(City c) {
        int w = c.code;
        if (w == 0) return c.day ? R.drawable.wx_clear_day : R.drawable.wx_clear_night;
        if (w == 1 || w == 2) return c.day ? R.drawable.wx_partly_day : R.drawable.wx_partly_night;
        if (w == 3) return R.drawable.wx_cloudy;
        if (w == 45 || w == 48) return R.drawable.wx_fog;
        if ((w >= 71 && w <= 77) || w == 85 || w == 86) return R.drawable.wx_snow;
        if (w >= 95) return R.drawable.wx_thunder;
        if ((w >= 51 && w <= 67) || (w >= 80 && w <= 82)) return R.drawable.wx_rain;
        return R.drawable.wx_cloudy;
    }

    void shutdown() {
        net.shutdownNow();
    }
}
