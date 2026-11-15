package com.nowplaying.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

public class NowPlayingWidget extends AppWidgetProvider {
    private static final String ACTION_PREV = "com.nowplaying.widget.PREV";
    private static final String ACTION_TOGGLE = "com.nowplaying.widget.TOGGLE";
    private static final String ACTION_NEXT = "com.nowplaying.widget.NEXT";

    private static final int DEFAULT_COLOR = 0xFF1E1E1E;
    private static final int ART_MAX_PX = 480;

    @Override public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
        render(ctx);
    }

    @Override public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager m, int id, Bundle o) {
        render(ctx);
    }

    @Override public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (!ACTION_PREV.equals(action) && !ACTION_TOGGLE.equals(action) && !ACTION_NEXT.equals(action)) {
            super.onReceive(ctx, intent);
            return;
        }
        MediaController mc = MediaListener.controller(ctx);
        if (ACTION_TOGGLE.equals(action)) {
            if (mc != null && Resumer.isPlaying(mc.getPlaybackState())) mc.getTransportControls().pause();
            else Resumer.play(ctx);
        } else if (mc != null) {
            if (ACTION_PREV.equals(action)) mc.getTransportControls().skipToPrevious();
            else mc.getTransportControls().skipToNext();
        }
        // The session callback normally re-renders; this covers the listener still reconnecting.
        Context app = ctx.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> render(app), 400);
    }

    static void render(Context context) {
        Context ctx = context.getApplicationContext();
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] ids = m.getAppWidgetIds(new ComponentName(ctx, NowPlayingWidget.class));
        if (ids.length == 0) return;
        State s = State.current(ctx);
        // The launcher picks the largest entry that fits the widget's current size (dp).
        Map<SizeF, RemoteViews> sizes = new ArrayMap<>();
        sizes.put(new SizeF(100, 40), build(ctx, R.layout.widget_strip, s));   // one row
        sizes.put(new SizeF(130, 150), build(ctx, R.layout.widget_art, s));    // 2x2-ish
        sizes.put(new SizeF(300, 150), build(ctx, R.layout.widget_wide, s));   // 4x2
        sizes.put(new SizeF(300, 290), build(ctx, R.layout.widget_art, s));    // 4x3 and up
        m.updateAppWidget(ids, new RemoteViews(sizes));
    }

    static RemoteViews build(Context ctx, int layout, State s) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), layout);
        if (s.art != null) v.setImageViewBitmap(R.id.art, s.art);
        else v.setImageViewResource(R.id.art, R.drawable.placeholder);
        v.setTextViewText(R.id.title, s.title);
        v.setTextViewText(R.id.artist, s.artist);
        v.setViewVisibility(R.id.artist, s.artist.isEmpty() ? View.GONE : View.VISIBLE);
        v.setImageViewResource(R.id.play, s.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        v.setContentDescription(R.id.play, ctx.getString(s.playing ? R.string.pause : R.string.play));
        if (layout != R.layout.widget_art) v.setInt(R.id.content, "setBackgroundColor", s.color);

        v.setOnClickPendingIntent(R.id.root, s.open);
        // Play/pause also works with no live session: it brings Spotify back in the background
        // (see Resumer). Skipping needs a live session; without one those buttons open Spotify.
        v.setOnClickPendingIntent(R.id.play, s.canResume ? action(ctx, ACTION_TOGGLE) : s.open);
        v.setOnClickPendingIntent(R.id.prev, s.live ? action(ctx, ACTION_PREV) : s.open);
        v.setOnClickPendingIntent(R.id.next, s.live ? action(ctx, ACTION_NEXT) : s.open);
        return v;
    }

    private static PendingIntent action(Context ctx, String action) {
        Intent i = new Intent(ctx, NowPlayingWidget.class).setAction(action);
        return PendingIntent.getBroadcast(ctx, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Everything one render needs. */
    static final class State {
        String title = "", artist = "";
        Bitmap art;
        int color = DEFAULT_COLOR;
        boolean playing, live;
        boolean canResume;
        PendingIntent open;

        static State current(Context ctx) {
            State s = new State();
            boolean access = MediaListener.hasAccess(ctx);
            MediaController mc = MediaListener.controller(ctx);
            Intent spotify = ctx.getPackageManager().getLaunchIntentForPackage(MediaListener.SPOTIFY);

            if (mc != null) {
                s.live = true;
                s.playing = Resumer.isPlaying(mc.getPlaybackState());
                MediaMetadata md = mc.getMetadata();
                if (md != null) Track.update(ctx, md);
                // Spotify's own "open the player" intent: the same one its media notification uses.
                s.open = mc.getSessionActivity();
            }
            if (s.open == null && access && spotify != null) {
                s.open = PendingIntent.getActivity(ctx, 1, spotify,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }
            if (s.open == null) {
                s.open = PendingIntent.getActivity(ctx, 2, new Intent(ctx, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }

            s.canResume = access && spotify != null;

            Track t = Track.get(ctx);
            if (!access) {
                s.title = ctx.getString(R.string.setup_title);
                s.artist = ctx.getString(R.string.setup_subtitle);
            } else if (spotify == null) {
                s.title = ctx.getString(R.string.no_spotify);
            } else if (t.title.isEmpty() && t.art == null) {
                s.title = ctx.getString(R.string.idle_title);
                s.artist = ctx.getString(R.string.idle_subtitle);
            } else {
                s.title = t.title;
                s.artist = t.artist;
                s.art = t.art;
                s.color = t.color;
            }
            return s;
        }
    }

    /**
     * Last track seen, kept on disk so the widget still shows it after Spotify
     * (or this process) goes away.
     */
    static final class Track {
        private static Track instance;

        String title = "", artist = "";
        Bitmap art;
        int color = DEFAULT_COLOR;
        private int artSig;

        static Track get(Context ctx) {
            if (instance == null) {
                Track t = new Track();
                SharedPreferences p = prefs(ctx);
                t.title = p.getString("title", "");
                t.artist = p.getString("artist", "");
                t.color = p.getInt("color", DEFAULT_COLOR);
                t.artSig = p.getInt("sig", 0);
                File f = artFile(ctx);
                if (f.exists()) t.art = BitmapFactory.decodeFile(f.getPath());
                instance = t;
            }
            return instance;
        }

        static void update(Context ctx, MediaMetadata md) {
            Track t = get(ctx);
            String title = first(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            String artist = first(md, MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);

            boolean sameTrack = title.equals(t.title) && artist.equals(t.artist);
            if (art == null) {
                // Art often arrives a moment after the title; keep the old art only for the same track.
                if (sameTrack) return;
                t.art = null;
                t.artSig = 0;
                t.color = DEFAULT_COLOR;
            } else {
                if (art.getConfig() == Bitmap.Config.HARDWARE) art = art.copy(Bitmap.Config.ARGB_8888, false);
                int[] px = new int[24 * 24];
                Bitmap.createScaledBitmap(art, 24, 24, true).getPixels(px, 0, 24, 0, 0, 24, 24);
                // Every getMetadata() call hands back a fresh Bitmap, so compare by content.
                int sig = Arrays.hashCode(px);
                if (sameTrack && sig == t.artSig && t.art != null) return;
                t.art = shrink(art);
                t.artSig = sig;
                t.color = backdrop(px);
            }
            t.title = title;
            t.artist = artist;
            t.save(ctx);
        }

        private void save(Context ctx) {
            prefs(ctx).edit().putString("title", title).putString("artist", artist)
                    .putInt("color", color).putInt("sig", artSig).apply();
            File f = artFile(ctx);
            if (art == null) {
                f.delete();
                return;
            }
            try (FileOutputStream out = new FileOutputStream(f)) {
                art.compress(Bitmap.CompressFormat.JPEG, 90, out);
            } catch (Exception ignored) {
                // Only the after-restart picture is lost.
            }
        }

        private static String first(MediaMetadata md, String... keys) {
            for (String k : keys) {
                CharSequence v = md.getText(k);
                if (v != null && v.length() > 0) return v.toString();
            }
            return "";
        }

        private static Bitmap shrink(Bitmap b) {
            int max = Math.max(b.getWidth(), b.getHeight());
            if (max <= ART_MAX_PX) return b;
            float k = ART_MAX_PX / (float) max;
            return Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
        }

        /** Average colour of the art, pushed dark enough for white text to sit on. */
        private static int backdrop(int[] px) {
            long r = 0, g = 0, b = 0;
            for (int p : px) {
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
            }
            float[] hsv = new float[3];
            Color.RGBToHSV((int) (r / px.length), (int) (g / px.length), (int) (b / px.length), hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.25f);
            hsv[2] = Math.max(0.16f, Math.min(0.30f, hsv[2]));
            return Color.HSVToColor(hsv);
        }

        private static SharedPreferences prefs(Context ctx) {
            return ctx.getSharedPreferences("track", Context.MODE_PRIVATE);
        }

        private static File artFile(Context ctx) {
            return new File(ctx.getFilesDir(), "art.jpg");
        }
    }
}
