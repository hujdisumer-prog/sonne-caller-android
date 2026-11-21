package com.rayaa.sonnecaller;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telecom.TelecomManager;
import android.util.Log;

import java.util.concurrent.TimeUnit;

/**
 * Raccroche un appel téléphonique classique (GSM).
 * 1) TelecomManager.endCall() : marche sans root sur beaucoup de téléphones qui ne sont pas des Samsung.
 * 2) Root : simule la touche « raccrocher » (KEYCODE_ENDCALL = 6) avec su.
 */
public final class CallHangUp {

    private static final String TAG = "SonneCaller";

    private CallHangUp() {}

    @SuppressWarnings("deprecation")
    public static boolean endCall(Context ctx) {
        // 1. Méthode officielle
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    && ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
                if (tm != null && tm.endCall()) {
                    Log.d(TAG, "Raccroché via TelecomManager");
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "TelecomManager.endCall a échoué : " + e.getMessage());
        }

        // 2. Root
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "input keyevent 6"});
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                Log.d(TAG, "Raccroché via root (KEYCODE_ENDCALL)");
                return true;
            }
            p.destroy();
        } catch (Exception e) {
            Log.e(TAG, "Raccrochage root a échoué : " + e.getMessage());
        }

        Log.e(TAG, "Impossible de raccrocher automatiquement");
        return false;
    }
}
