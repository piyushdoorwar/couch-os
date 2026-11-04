package dev.couch.launcher.fixture;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** Runs in the test APK's own process to exercise cross-app media session access. */
public class SamplePlayerActivity extends Activity {
    private MediaSession session;
    private TextView status;
    private String title;
    private long position;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(32);
        setContentView(status);
        session = new MediaSession(this, "SamplePlayer");
        Intent reopen = new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        session.setSessionActivity(PendingIntent.getActivity(this, 0, reopen, PendingIntent.FLAG_IMMUTABLE));
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() {
                setPlayback(PlaybackState.STATE_PLAYING);
            }
        });
        publish(getIntent());
        session.setActive(true);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.hasExtra("title")) publish(intent);
    }

    private void publish(Intent intent) {
        String suppliedTitle = intent.getStringExtra("title");
        title = suppliedTitle == null ? null : suppliedTitle.replace('_', ' ');
        position = 300_000;
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 1_200_000);
        if (title != null) {
            Bitmap artwork = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(artwork);
            canvas.drawColor(Color.rgb(28, 63, 97));
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.rgb(255, 184, 112));
            canvas.drawCircle(420, 135, 108, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(42);
            canvas.drawText(title, 38, 310, paint);
            metadata.putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, "Season 1, Episode 2")
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, title + ":2")
                .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork);
        }
        session.setMetadata(metadata.build());
        setPlayback(PlaybackState.STATE_PLAYING);
    }

    private void setPlayback(int state) {
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY)
            .setState(state, position, state == PlaybackState.STATE_PLAYING ? 1f : 0f)
            .build());
        status.setText((state == PlaybackState.STATE_PLAYING ? "Playing " : "Paused ") + title);
    }

    @Override protected void onPause() {
        position = 600_000;
        setPlayback(PlaybackState.STATE_PAUSED);
        super.onPause();
    }

    @Override protected void onDestroy() {
        session.release();
        super.onDestroy();
    }
}
