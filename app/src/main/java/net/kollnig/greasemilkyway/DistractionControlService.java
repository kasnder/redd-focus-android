package net.kollnig.greasemilkyway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.core.content.ContextCompat;

import net.kollnig.distractionlib.BaseDistractionControlService;
import net.kollnig.distractionlib.ElementPickerOverlay;
import net.kollnig.distractionlib.FilterRule;
import net.kollnig.distractionlib.FilterRuleParser;

import java.util.List;

public class DistractionControlService extends BaseDistractionControlService {
    private static final String TAG = "DistractionControlService";

    private static DistractionControlService instance;

    private ServiceConfig config;
    private LayoutDumper layoutDumper;
    private ElementPickerNotification pickerNotification;
    private ElementPickerOverlay pickerOverlay;
    private BroadcastReceiver pickerReceiver;
    private final Runnable updateRulesRunnable = this::updateRules;

    public static DistractionControlService getInstance() {
        return instance;
    }

    public void updateRules() {
        if (instance == null) {
            return;
        }

        getUiHandler().removeCallbacks(updateRulesRunnable);
        reloadRulesFromSource();
    }

    @Override
    protected List<FilterRule> loadRules() {
        if (config == null) {
            config = new ServiceConfig(this);
        }
        return config.getRules();
    }

    @Override
    protected boolean shouldProcessRules() {
        return pickerOverlay == null || !pickerOverlay.isActive();
    }

    @Override
    protected long getNotificationTimeout() {
        if (config == null) {
            config = new ServiceConfig(this);
        }
        return config.getNotificationTimeoutMs();
    }

    @Override
    protected void onServiceReady() {
        instance = this;
        config = new ServiceConfig(this);

        layoutDumper = new LayoutDumper();
        layoutDumper.start();

        pickerNotification = new ElementPickerNotification(this);
        pickerOverlay = new ElementPickerOverlay(this, getOverlayWindowManager(),
                new ElementPickerOverlay.Listener() {
                    @Override
                    public void onRuleChosen(String ruleString) {
                        saveAndApplyPickerRule(ruleString);
                    }

                    @Override
                    public void onPickerDismissed() {
                        stopPickerMode();
                    }
                });

        pickerReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ElementPickerNotification.ACTION_START_PICKER.equals(action)) {
                    startPickerMode();
                } else if (ElementPickerNotification.ACTION_STOP_PICKER.equals(action)) {
                    stopPickerMode();
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ElementPickerNotification.ACTION_START_PICKER);
        filter.addAction(ElementPickerNotification.ACTION_STOP_PICKER);
        ContextCompat.registerReceiver(this, pickerReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onServiceTeardown() {
        instance = null;

        if (layoutDumper != null) {
            layoutDumper.stop();
        }
        if (pickerOverlay != null) {
            pickerOverlay.hide();
        }
        if (pickerNotification != null) {
            pickerNotification.cancelNotification();
        }
        if (pickerReceiver != null) {
            try {
                unregisterReceiver(pickerReceiver);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onRulesReloaded() {
        scheduleNextRuleUpdate();
    }

    public void startPickerMode() {
        if (pickerOverlay == null || pickerNotification == null) {
            return;
        }

        Log.i(TAG, "Starting picker mode");
        clearCurrentOverlays();
        pickerOverlay.show();
        pickerNotification.showPickerActiveNotification();
    }

    public void stopPickerMode() {
        if (pickerOverlay == null || pickerNotification == null) {
            return;
        }

        Log.i(TAG, "Stopping picker mode");
        pickerOverlay.hide();
        pickerNotification.showNotification();
        updateRules();
    }

    private void saveAndApplyPickerRule(String ruleString) {
        ServiceConfig config = new ServiceConfig(this);
        config.addCustomRule(ruleString);

        FilterRuleParser parser = new FilterRuleParser();
        List<FilterRule> parsed = parser.parseRules(new String[]{ruleString});
        if (!parsed.isEmpty()) {
            FilterRule rule = parsed.get(0);
            config.setRuleEnabled(rule, true);
            config.setPackageDisabled(rule.packageName, false);
        }

        updateRules();
    }

    private void scheduleNextRuleUpdate() {
        long nextUpdate = -1;
        long currentTime = System.currentTimeMillis();

        for (FilterRule rule : getRulesSnapshot()) {
            if (rule.isPaused && rule.pausedUntil > currentTime) {
                if (nextUpdate == -1 || rule.pausedUntil < nextUpdate) {
                    nextUpdate = rule.pausedUntil;
                }
            }
        }

        if (nextUpdate != -1) {
            long delay = Math.max(0, nextUpdate - currentTime);
            Log.d(TAG, "Scheduling rules update in " + (delay / 1000) + " seconds");
            getUiHandler().postDelayed(updateRulesRunnable, delay);
        }
    }
}
