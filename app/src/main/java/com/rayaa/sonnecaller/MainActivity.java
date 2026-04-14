package com.rayaa.sonnecaller;

import android.app.Activity;
import android.content.Intent;
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
    }

    private void startService() {
        if (!HangUpService.isAvailable()) {
            Toast.makeText(this,
                "Activez le service d'accessibilit\u00e9 'Sonne Caller' dans les param\u00e8tres",
                Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(this, CallerService.class);
        startForegroundService(intent);
        statusText.setText("Service actif \u2014 En attente d'appels WhatsApp...");
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
            statusText.setText("Service actif \u2014 En attente d'appels WhatsApp...");
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
        } else {
            statusText.setText("Service arr\u00eat\u00e9");
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
        }
    }
}
