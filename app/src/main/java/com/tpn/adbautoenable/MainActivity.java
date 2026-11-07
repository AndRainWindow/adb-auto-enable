package com.tpn.adbautoenable;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int WEB_PORT = 9093;
    private static final String PREFS_NAME = "ADBAutoEnablePrefs";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Read web server preference
        SharedPreferences prefs = NetworkUtils.getDeviceProtectedPrefs(this, PREFS_NAME);
        boolean webServerEnabled = prefs.getBoolean("web_server_enabled", true);

        // The ADB service must run independently of the optional web interface.
        // Disabling the web UI must never prevent Wireless Debugging recovery.
        Intent serviceIntent = new Intent(this, AdbConfigService.class);
        serviceIntent.putExtra("boot_config", false);
        startForegroundService(serviceIntent);

        // Create UI
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);

        TextView titleText = new TextView(this);
        titleText.setText("ADB Auto-Enable");
        titleText.setTextSize(24);

        TextView statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setText(webServerEnabled
                ? "\
Web interface running on:\
"
                : "\
Web interface disabled (ADB service remains active).\
");

        TextView urlText = new TextView(this);
        urlText.setTextSize(18);
        urlText.setTextColor(0xFF2196F3);
        urlText.setText("http://" + getLocalIpAddress() + ":" + WEB_PORT);

        // Native On-Device Toggle Switch for Web Server
        Switch webServerSwitch = new Switch(this);
        webServerSwitch.setTextSize(16);
        webServerSwitch.setText("\nEnable Web Interface on Boot");
        webServerSwitch.setChecked(webServerEnabled);
        webServerSwitch.setPadding(0, 30, 0, 10);

        webServerSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("web_server_enabled", isChecked).apply();

            // Notify the service to start/stop only NanoHTTPD, not the ADB
            // configuration foreground service itself.
            Intent serviceIntent = new Intent(this, AdbConfigService.class);
            serviceIntent.putExtra("boot_config", false);
            startForegroundService(serviceIntent);
        });

        TextView instructionText = new TextView(this);
        instructionText.setText("\nOpen this URL in your browser to configure pairing, target ports, and manage the app.\n\nYou can also toggle the web server directly from this screen if you want it disabled on future reboots.");

        layout.addView(titleText);
        layout.addView(statusText);
        layout.addView(urlText);
        layout.addView(webServerSwitch);
        layout.addView(instructionText);

        setContentView(layout);
    }

    private String getLocalIpAddress() {
        return NetworkUtils.getLiveDeviceIP(this);
    }
}