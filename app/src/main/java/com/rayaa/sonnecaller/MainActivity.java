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

    private static final int PERMISSION_REQUEST = 1;
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

        // Request CALL_PHONE permission
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, PERMISSION_REQUEST);
        }

        startButton.setOnClickListener(v -> startService());
        stopButton.setOnClickListener(v -> stopService());

        updateStatus();
    }

    private void startService() {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission d'appel requise", Toast.LENGTH_SHORT).show();
            requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, PERMISSION_REQUEST);
            return;
        }

        Intent intent = new Intent(this, CallerService.class);
        startForegroundService(intent);
        statusText.setText("Service actif — En attente d'appels...");
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
    }

    private void stopService() {
        Intent intent = new Intent(this, CallerService.class);
        stopService(intent);
        statusText.setText("Service arrêté");
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
    }

    private void updateStatus() {
        if (CallerService.isRunning) {
            statusText.setText("Service actif — En attente d'appels...");
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
        } else {
            statusText.setText("Service arrêté");
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission accordée", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Permission refusée — l'app ne peut pas fonctionner", Toast.LENGTH_LONG).show();
            }
        }
    }
}
