package com.nowplaying.widget;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.media.MediaBrowserService;
import android.util.Log;
import android.view.KeyEvent;

import java.util.List;

/**
 * Starts Spotify playing without opening it.
 *
 *  1. Live session: plain transport "play".
 *  2. No session (Spotify was killed): bind its MediaBrowserService, the same route the system's
 *     own "resume" media card uses, and send play through the session it hands back.
 *  3. If Spotify refuses the connection: a media PLAY key, which the system delivers to the
 *     app that played last.
 */
final class Resumer {
    private static final String TAG = "NowPlayingWidget";
    private static final long CONNECT_TIMEOUT_MS = 3000;
    private static final long GIVE_UP_MS = 6000;
    private static final long HOLD_CONNECTION_MS = 15000;

    private static boolean busy;

    static boolean isPlaying(PlaybackState ps) {
        if (ps == null) return false;
        int st = ps.getState();
        return st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING;
    }

    static void play(Context context) {
        if (busy) return;
        busy = true;
        Context ctx = context.getApplicationContext();
        Handler h = new Handler(Looper.getMainLooper());
        MediaController mc = MediaListener.controller(ctx);
        if (mc != null) {
            mc.getTransportControls().play();
        } else if (!viaBrowser(ctx, h)) {
            mediaKey(ctx);
        }
        // Redraw as soon as Spotify's session reports playing; stop waiting after GIVE_UP_MS.
        long deadline = SystemClock.uptimeMillis() + GIVE_UP_MS;
        h.postDelayed(new Runnable() {
            @Override public void run() {
                MediaController now = MediaListener.controller(ctx);
                if ((now != null && isPlaying(now.getPlaybackState())) || SystemClock.uptimeMillis() > deadline) {
                    busy = false;
                    NowPlayingWidget.render(ctx);
                } else {
                    h.postDelayed(this, 150);
                }
            }
        }, 150);
    }

    private static boolean viaBrowser(Context ctx, Handler h) {
        List<ResolveInfo> found = ctx.getPackageManager().queryIntentServices(
                new Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(MediaListener.SPOTIFY), 0);
        if (found.isEmpty()) return false;
        ComponentName service = new ComponentName(MediaListener.SPOTIFY, found.get(0).serviceInfo.name);

        // "Recent" asks for the resumable root, as the system media card does.
        Bundle hints = new Bundle();
        hints.putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true);

        MediaBrowser[] browser = new MediaBrowser[1];
        boolean[] settled = new boolean[1];
        Runnable fallback = () -> {
            if (settled[0]) return;
            settled[0] = true;
            Log.i(TAG, "browser route failed, sending media key");
            try { browser[0].disconnect(); } catch (RuntimeException ignored) { }
            mediaKey(ctx);
        };
        browser[0] = new MediaBrowser(ctx, service, new MediaBrowser.ConnectionCallback() {
            @Override public void onConnected() {
                if (settled[0]) return;
                settled[0] = true;
                Log.i(TAG, "browser connected, sending play");
                MediaController.TransportControls t =
                        new MediaController(ctx, browser[0].getSessionToken()).getTransportControls();
                t.prepare();
                t.play();
                // Stay bound while Spotify gets its own playback service going.
                h.postDelayed(() -> {
                    try { browser[0].disconnect(); } catch (RuntimeException ignored) { }
                }, HOLD_CONNECTION_MS);
            }
            @Override public void onConnectionFailed() { fallback.run(); }
            @Override public void onConnectionSuspended() { fallback.run(); }
        }, hints);
        try {
            browser[0].connect();
        } catch (RuntimeException e) {
            return false;
        }
        h.postDelayed(fallback, CONNECT_TIMEOUT_MS);
        return true;
    }

    private static void mediaKey(Context ctx) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
    }

    private Resumer() { }
}
