package dev.mwkov.spoilershield;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/** Invisible screen that shows Android's "start capturing?" prompt and starts fast mode. */
public class ConsentActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;   // prompt already showing
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        Intent ask = Build.VERSION.SDK_INT >= 34
                ? mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : mpm.createScreenCaptureIntent();
        try {
            startActivityForResult(ask, 1);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Couldn't open the screen capture prompt", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            Intent svc = new Intent(this, CaptureService.class)
                    .putExtra(CaptureService.EXTRA_CODE, resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data);
            try {
                startForegroundService(svc);
            } catch (RuntimeException e) {
                Toast.makeText(this, "Couldn't start fast mode", Toast.LENGTH_LONG).show();
            }
        }
        finish();
    }
}
