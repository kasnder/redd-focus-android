package net.kollnig.greasemilkyway;

import android.content.Context;
import android.content.SharedPreferences;

import net.kollnig.distractionlib.FilterRule;
import net.kollnig.distractionlib.FilterRuleParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages configuration for the LayoutDumpAccessibilityService.
 */
public class ServiceConfig {
    private static final String TAG = "ServiceConfig";
    private static final String PREFS_NAME = "LayoutDumpServicePrefs";
    public static final String KEY_RULE_ENABLED = "rule_enabled_";
    private static final String KEY_CUSTOM_RULES = "custom_rules";
    private static final String KEY_PACKAGE_DISABLED = "package_disabled_";
    private static final String KEY_PAUSE_UNTIL_RULE_ = "pause_until_rule_";
    private static final String KEY_PAUSE_UNTIL_PACKAGE_ = "pause_until_package_";
    private static final String KEY_FRICTION_WORD_COUNT = "friction_word_count";
    private static final String KEY_PAUSE_DURATION_MINS = "pause_duration_mins";
    private static final String KEY_NOTIFICATION_TIMEOUT_MS = "notification_timeout_ms";
    private static final String DEFAULT_RULES_FILE = "distraction_rules.txt";

    private final SharedPreferences prefs;
    private final FilterRuleParser ruleParser;
    private final Context context;

    public ServiceConfig(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.ruleParser = new FilterRuleParser();
    }

    public SharedPreferences getPrefs() {
        return prefs;
    }

    public void setRuleEnabled(FilterRule rule, boolean enabled) {
        String key = KEY_RULE_ENABLED + rule.hashCode();
        prefs.edit().putBoolean(key, enabled).apply();
    }

    public boolean isRuleEnabled(FilterRule rule) {
        return prefs.getBoolean(KEY_RULE_ENABLED + rule.hashCode(), false);
    }
    
    public void setRulePausedUntil(FilterRule rule, long timestampMillis) {
        String key = KEY_PAUSE_UNTIL_RULE_ + rule.hashCode();
        prefs.edit().putLong(key, timestampMillis).apply();
    }
    
    public long getRulePausedUntil(FilterRule rule) {
        String key = KEY_PAUSE_UNTIL_RULE_ + rule.hashCode();
        return prefs.getLong(key, 0);
    }

    public void setPackageDisabled(String packageName, boolean disabled) {
        String key = KEY_PACKAGE_DISABLED + packageName;
        prefs.edit().putBoolean(key, disabled).apply();
    }
    
    public void setPackagePausedUntil(String packageName, long timestampMillis) {
        String key = KEY_PAUSE_UNTIL_PACKAGE_ + packageName;
        prefs.edit().putLong(key, timestampMillis).apply();
    }
    
    public long getPackagePausedUntil(String packageName) {
        String key = KEY_PAUSE_UNTIL_PACKAGE_ + packageName;
        return prefs.getLong(key, 0);
    }

    public boolean isPackageDisabled(String packageName) {
        String key = KEY_PACKAGE_DISABLED + packageName;
        return prefs.getBoolean(key, true); // Default to disabled (opt-in)
    }

    public List<FilterRule> getRules() {
        List<FilterRule> rules = new ArrayList<>();
        
        // Add default rules from file
        try {
            InputStream is = context.getAssets().open(DEFAULT_RULES_FILE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    rules.addAll(ruleParser.parseRules(new String[]{line}));
                }
            }
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        // Add custom rules
        String[] customRules = getCustomRules();
        if (customRules != null) {
            List<FilterRule> parsedCustomRules = ruleParser.parseRules(customRules);
            for (FilterRule rule : parsedCustomRules) {
                rule.isCustom = true;
            }
            rules.addAll(parsedCustomRules);
        }

        // Apply saved enabled states
        long currentTime = System.currentTimeMillis();
        for (FilterRule rule : rules) {
            String ruleEnabledKey = KEY_RULE_ENABLED + rule.hashCode();
            boolean ruleEnabled = prefs.getBoolean(ruleEnabledKey, false);
            
            long packagePauseUntil = 0;
            if (rule.packageName != null) {
                packagePauseUntil = getPackagePausedUntil(rule.packageName);
            }
            long rulePauseUntil = getRulePausedUntil(rule);

            // If the package is disabled (and not just temporarily paused), force disable all rules for that package
            if (rule.packageName != null && isPackageDisabled(rule.packageName)) {
                
                if (packagePauseUntil > currentTime) {
                    // It is paused but time hasn't expired yet -> it stays disabled
                    rule.enabled = false;
                    rule.isPaused = true;
                    rule.pausedUntil = packagePauseUntil;
                } else if (packagePauseUntil > 0) {
                    // It was paused and time expired -> clear pause and re-enable
                    setPackagePausedUntil(rule.packageName, 0);
                    setPackageDisabled(rule.packageName, false);
                    rule.enabled = true;
                    rule.isPaused = false;
                } else {
                    rule.enabled = false;
                }
            } else {
                if (!ruleEnabled && rulePauseUntil > currentTime) {
                    // Rule is explicitly paused
                    rule.enabled = false;
                    rule.isPaused = true;
                    rule.pausedUntil = rulePauseUntil;
                } else if (!ruleEnabled && rulePauseUntil > 0) {
                    // Rule was paused and time expired
                    setRulePausedUntil(rule, 0);
                    setRuleEnabled(rule, true);
                    rule.enabled = true;
                    rule.isPaused = false;
                    // Because setting ruleEnabled to true updates prefs, it will persist
                } else {
                    rule.enabled = ruleEnabled;
                }
            }
        }

        return rules;
    }

    public int getFrictionWordCount() {
        return prefs.getInt(KEY_FRICTION_WORD_COUNT, 0);
    }

    public void setFrictionWordCount(int count) {
        prefs.edit().putInt(KEY_FRICTION_WORD_COUNT, count).apply();
    }

    public int getPauseDurationMins() {
        // Default to 10 mins
        return prefs.getInt(KEY_PAUSE_DURATION_MINS, 10);
    }

    public void setPauseDurationMins(int mins) {
        prefs.edit().putInt(KEY_PAUSE_DURATION_MINS, mins).apply();
    }

    public long getNotificationTimeoutMs() {
        return prefs.getLong(KEY_NOTIFICATION_TIMEOUT_MS, 100);
    }

    public void setNotificationTimeoutMs(long ms) {
        prefs.edit().putLong(KEY_NOTIFICATION_TIMEOUT_MS, ms).apply();
    }

    public String[] getCustomRules() {
        String rules = prefs.getString(KEY_CUSTOM_RULES, "");
        return rules.isEmpty() ? null : rules.split("\n");
    }

    public void saveCustomRules(String[] rules) {
        prefs.edit().putString(KEY_CUSTOM_RULES, String.join("\n", rules)).apply();
    }

    /**
     * Appends a single custom rule to the existing custom rules.
     */
    public void addCustomRule(String ruleString) {
        String existing = prefs.getString(KEY_CUSTOM_RULES, "");
        String updated = existing.isEmpty() ? ruleString : existing + "\n" + ruleString;
        prefs.edit().putString(KEY_CUSTOM_RULES, updated).apply();
    }

    /**
     * Removes a custom rule exactly matching the given rule string.
     */
    public void removeCustomRule(String ruleString) {
        String[] existing = getCustomRules();
        if (existing == null) return;
        
        List<String> updated = new ArrayList<>();
        boolean removed = false;
        
        for (String rule : existing) {
            if (!removed && rule.equals(ruleString)) {
                removed = true; // Only remove the first exact match
                continue;
            }
            updated.add(rule);
        }
        
        if (removed) {
            saveCustomRules(updated.toArray(new String[0]));
        }
    }
}
