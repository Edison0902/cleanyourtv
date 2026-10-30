package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
                + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
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
            SharedPreferences.Editor e = prefs.edit();
            double t = cur.getDouble("temperature_2m");
            e.putInt("t", (int) Math.round(t))
                    .putInt("c", cur.getInt("weather_code"))
                    .putBoolean("d", cur.getInt("is_day") == 1)
                    .putInt("x", (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)))
                    .putInt("n", (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)));
            e.putString("url", url).putLong("at", System.currentTimeMillis()).apply();
            Log.i(TAG, "weather: fetch ok here=" + t + " code=" + cur.getInt("weather_code")
                    + " is_day=" + (cur.getInt("is_day") == 1 ? 1 : 0)
                    + " max=" + daily.getJSONArray("temperature_2m_max").getDouble(0)
                    + " min=" + daily.getJSONArray("temperature_2m_min").getDouble(0));
            return true;
        } catch (Exception ex) {
            Log.w(TAG, "weather: fetch failed: " + ex);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
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
