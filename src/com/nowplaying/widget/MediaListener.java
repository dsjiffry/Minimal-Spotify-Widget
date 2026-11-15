package com.nowplaying.widget;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import java.util.List;

/**
 * Holds notification access, which is what lets us see Spotify's media session.
 * Tracks that session and re-renders the widget whenever the track or play state changes.
 */
public class MediaListener extends NotificationListenerService {
    static final String SPOTIFY = "com.spotify.music";

    private static MediaController current;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable push = () -> NowPlayingWidget.render(this);
    private MediaSessionManager sessions;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { schedule(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { schedule(); }
        @Override public void onSessionDestroyed() { attach(null); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener onSessions = this::attach;

    static ComponentName component(Context ctx) {
        return new ComponentName(ctx, MediaListener.class);
    }

    static boolean hasAccess(Context ctx) {
        return ctx.getSystemService(NotificationManager.class)
                .isNotificationListenerAccessGranted(component(ctx));
    }

    /** Spotify's media session, or null if Spotify has none (or we lack access). */
    static MediaController controller(Context ctx) {
        MediaController c = current;
        if (c != null) return c;
        if (!hasAccess(ctx)) return null;
        try {
            return pick(ctx.getSystemService(MediaSessionManager.class)
                    .getActiveSessions(component(ctx)));
        } catch (SecurityException e) {
            return null;
        }
    }

    private static MediaController pick(List<MediaController> list) {
        if (list != null) {
            for (MediaController c : list) {
                if (SPOTIFY.equals(c.getPackageName())) return c;
            }
        }
        return null;
    }

    @Override public void onListenerConnected() {
        sessions = getSystemService(MediaSessionManager.class);
        try {
            sessions.addOnActiveSessionsChangedListener(onSessions, component(this), handler);
            attach(sessions.getActiveSessions(component(this)));
        } catch (SecurityException e) {
            attach(null);
        }
    }

    @Override public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(onSessions);
        attach(null);
    }

    private void attach(List<MediaController> list) {
        MediaController next = pick(list);
        MediaController old = current;
        if (old != null && next != null && old.getSessionToken().equals(next.getSessionToken())) return;
        if (old != null) old.unregisterCallback(callback);
        current = next;
        if (next != null) next.registerCallback(callback, handler);
        schedule();
    }

    /** Spotify fires several callbacks per track change; coalesce them into one render. */
    private void schedule() {
        handler.removeCallbacks(push);
        handler.postDelayed(push, 60);
    }
}
