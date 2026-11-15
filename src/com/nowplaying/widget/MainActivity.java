package com.nowplaying.widget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Setup screen: grant notification access, preview the widget, pin it to the home screen. */
public class MainActivity extends Activity {

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        findViewById(R.id.grant).setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            MediaListener.component(this).flattenToString());
            try {
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
        });

        AppWidgetManager m = AppWidgetManager.getInstance(this);
        Button add = findViewById(R.id.add);
        add.setVisibility(m.isRequestPinAppWidgetSupported() ? View.VISIBLE : View.GONE);
        add.setOnClickListener(v ->
                m.requestPinAppWidget(new ComponentName(this, NowPlayingWidget.class), null, null));
    }

    @Override protected void onResume() {
        super.onResume();
        boolean access = MediaListener.hasAccess(this);
        ((TextView) findViewById(R.id.status)).setText(access ? R.string.status_ok : R.string.status_needed);
        findViewById(R.id.grant).setVisibility(access ? View.GONE : View.VISIBLE);

        FrameLayout preview = findViewById(R.id.preview);
        preview.removeAllViews();
        NowPlayingWidget.State s = NowPlayingWidget.State.current(this);
        preview.addView(NowPlayingWidget.build(this, R.layout.widget_art, s).apply(this, preview));
        NowPlayingWidget.render(this);
    }
}
