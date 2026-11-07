package com.jellytip.phone;

import android.content.Intent;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // App-local plugins must be registered before the bridge starts
        registerPlugin(DownloadsPlugin.class);
        registerPlugin(PlayerPlugin.class);
        registerPlugin(RemindersPlugin.class);
        super.onCreate(savedInstanceState);
        takeReminderLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (takeReminderLink(intent) && getBridge() != null && getBridge().getWebView() != null) {
            // App already running: tell the web app to pick the link up now
            getBridge().getWebView().post(() -> getBridge().getWebView().evaluateJavascript("window.dispatchEvent(new Event('jellytip-native-deeplink'))", null));
        }
    }

    private boolean takeReminderLink(Intent intent) {
        if (intent == null || !intent.hasExtra(ReminderNotifier.EXTRA_DEEPLINK)) return false;
        RemindersPlugin.pendingDeepLink = intent.getStringExtra(ReminderNotifier.EXTRA_DEEPLINK);
        intent.removeExtra(ReminderNotifier.EXTRA_DEEPLINK);
        return true;
    }
}
