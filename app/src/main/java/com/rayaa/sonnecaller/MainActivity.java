package com.rayaa.sonnecaller;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private TextView statusText;
    private Button startButton;
    private Button stopButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);

        startButton.setOnClickListener(v -> startService());
        stopButton.setOnClickListener(v -> stopService());

        updateStatus();
        requestCallPermissions();
    }

    private void requestCallPermissions() {
        if (CallerService.USE_WHATSAPP) return;
        String[] perms = {Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS};
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(perms, 1);
                return;
            }
        }
    }

    private String waitingText() {
        return CallerService.USE_WHATSAPP
            ? "Service actif — En attente d'appels WhatsApp..."
            : "Service actif — En attente d'appels...";
    }

    private void startService() {
        if (!CallerService.USE_WHATSAPP
                && checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Autorisez les appels téléphoniques pour Sonne Caller", Toast.LENGTH_LONG).show();
            requestCallPermissions();
            return;
        }

        if (CallerService.USE_WHATSAPP && !HangUpService.isAvailable()) {
            Toast.makeText(this,
                "Activez le service d'accessibilit\u00e9 'Sonne Caller' dans les param\u00e8tres",
                Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(this, CallerService.class);
        startForegroundService(intent);
        statusText.setText(waitingText());
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
    }

    private void stopService() {
        Intent intent = new Intent(this, CallerService.class);
        stopService(intent);
        statusText.setText("Service arr\u00eat\u00e9");
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
    }

    private void updateStatus() {
        if (CallerService.isRunning) {
            statusText.setText(waitingText());
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
        } else {
            statusText.setText("Service arr\u00eat\u00e9");
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
        }
    }
}
