package com.jellytip.phone;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // App-local plugins must be registered before the bridge starts
        registerPlugin(DownloadsPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
