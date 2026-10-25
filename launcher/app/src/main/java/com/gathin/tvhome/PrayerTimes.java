package com.gathin.tvhome;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Waktu solat JAKIM di bawah jam. Mula sendiri bila Home dibuka. */
public class PrayerTimes extends ContentProvider {
    private static final String[] NAMES = {"KL", "Lenggeng", "Batu Pahat"};
    private static final String[] ZONES = {"WLY01", "NGS02", "JHR04"};
    private static final String[] KEYS = {"fajr", "syuruk", "dhuhr", "asr", "maghrib", "isha"};
    private static final String[] LABELS = {"Subuh", "Syuruk", "Zohor", "Asar", "Maghrib", "Isyak"};
    private static final String TAG = "prayerTimes";
    private static final long ROTATE_MS = 10000;
    private static Controller current;

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx == null || !(ctx.getApplicationContext() instanceof Application)) return true;
        Application app = (Application) ctx.getApplicationContext();
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity a) {
                if (a instanceof MainActivity) attach(a);
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
        return true;
    }

    private static void attach(Activity a) {
        try {
            View date = a.findViewById(R.id.date);
            if (date == null || !(date.getParent() instanceof LinearLayout)) return;
            ViewGroup parent = (ViewGroup) date.getParent();
            TextView tv = (TextView) parent.findViewWithTag(TAG);
            if (tv == null) {
                tv = new TextView(a);
                tv.setTag(TAG);
                tv.setGravity(Gravity.START);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setIncludeFontPadding(false);
                tv.setTextColor(0xCCFFFFFF);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                tv.setLetterSpacing(0.02f);
                if (date instanceof TextView) tv.setTypeface(((TextView) date).getTypeface());
                tv.setVisibility(View.GONE);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6,
                        a.getResources().getDisplayMetrics());
                parent.addView(tv, parent.indexOfChild(date) + 1, lp);
            }
            if (current == null || current.view != tv) {
                if (current != null) current.stop();
                current = new Controller(tv);
            }
            current.start();
        } catch (Exception ignored) {
        }
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static String hhmm(String s) {
        String[] p = s.trim().split(":");
        return String.format(Locale.US, "%02d:%02d", Integer.parseInt(p[0]), Integer.parseInt(p[1]));
    }

    private static String fetch(String zone) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("https://www.e-solat.gov.my/index.php?r=esolatApi/takwimsolat&period=today&zone=" + zone);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) GlassTV");
            if (c.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONArray arr = new JSONObject(sb.toString()).getJSONArray("prayerTime");
            JSONObject o = arr.getJSONObject(0);
            StringBuilder t = new StringBuilder();
            for (int k = 0; k < KEYS.length; k++) {
                if (k > 0) t.append(',');
                t.append(hhmm(o.getString(KEYS[k])));
            }
            return t.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static final class Controller {
        final TextView view;
        final SharedPreferences prefs;
        final String[] times = new String[ZONES.length];
        final String[] days = new String[ZONES.length];
        int shown = 0;
        long lastTry = 0;
        boolean running = false;

        final Runnable rotate = new Runnable() {
            @Override public void run() {
                if (!running) return;
                if (!view.isAttachedToWindow()) { running = false; return; }
                shown = (shown + 1) % ZONES.length;
                render();
                fetchIfNeeded();
                view.postDelayed(this, ROTATE_MS);
            }
        };

        Controller(TextView v) {
            view = v;
            prefs = v.getContext().getSharedPreferences("prayer", Context.MODE_PRIVATE);
        }

        void start() {
            if (running) return;
            running = true;
            for (int i = 0; i < ZONES.length; i++) {
                String saved = prefs.getString("z_" + ZONES[i], "");
                int bar = saved.indexOf('|');
                if (bar > 0) {
                    days[i] = saved.substring(0, bar);
                    times[i] = saved.substring(bar + 1);
                }
            }
            render();
            fetchIfNeeded();
            view.postDelayed(rotate, ROTATE_MS);
        }

        void stop() {
            running = false;
            view.removeCallbacks(rotate);
        }

        void render() {
            String day = today();
            for (int n = 0; n < ZONES.length; n++) {
                int i = (shown + n) % ZONES.length;
                if (times[i] != null && day.equals(days[i])) {
                    shown = i;
                    String[] t = times[i].split(",");
                    StringBuilder sb = new StringBuilder(NAMES[i]).append("   ");
                    for (int k = 0; k < t.length && k < LABELS.length; k++) {
                        if (k > 0) sb.append("  ");
                        sb.append(LABELS[k]).append(' ').append(t[k]);
                    }
                    view.setText(sb.toString());
                    view.setVisibility(View.VISIBLE);
                    return;
                }
            }
            view.setVisibility(View.GONE);
        }

        void fetchIfNeeded() {
            long now = System.currentTimeMillis();
            if (now - lastTry < 60000) return;
            final String day = today();
            boolean need = false;
            for (int i = 0; i < ZONES.length; i++) if (!day.equals(days[i])) need = true;
            if (!need) return;
            lastTry = now;
            for (int i = 0; i < ZONES.length; i++) {
                if (day.equals(days[i])) continue;
                final int idx = i;
                new Thread(new Runnable() {
                    @Override public void run() {
                        final String t = fetch(ZONES[idx]);
                        if (t == null) return;
                        prefs.edit().putString("z_" + ZONES[idx], day + "|" + t).apply();
                        view.post(new Runnable() {
                            @Override public void run() {
                                days[idx] = day;
                                times[idx] = t;
                                render();
                            }
                        });
                    }
                }).start();
            }
        }
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
    }
