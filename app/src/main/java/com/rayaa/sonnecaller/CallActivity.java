package com.rayaa.sonnecaller;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.WindowManager;

public class CallActivity extends Activity {

    private static final String TAG = "SonneCaller";
    private static final String WA_PACKAGE = "com.whatsapp.w4b";
    private PowerManager.WakeLock wakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "sonnecaller:call"
        );
        wakeLock.acquire(30000);

        String waNumber = getIntent().getStringExtra("waNumber");
        if (waNumber != null) {
            openWhatsAppChat(waNumber);
        } else {
            finish();
        }
    }

    private void openWhatsAppChat(String waNumber) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://wa.me/" + waNumber));
            intent.setPackage(WA_PACKAGE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.d(TAG, "CallActivity: WhatsApp chat opened for " + waNumber);
        } catch (Exception e) {
            Log.e(TAG, "CallActivity: failed to open WhatsApp: " + e.getMessage());
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
