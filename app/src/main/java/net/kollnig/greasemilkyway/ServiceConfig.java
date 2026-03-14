package net.kollnig.greasemilkyway;

import android.content.Context;
import android.content.SharedPreferences;

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

    public void setPackageDisabled(String packageName, boolean disabled) {
        String key = KEY_PACKAGE_DISABLED + packageName;
        prefs.edit().putBoolean(key, disabled).apply();
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
            rules.addAll(ruleParser.parseRules(customRules));
        }

        // Apply saved enabled states
        for (FilterRule rule : rules) {
            String key = KEY_RULE_ENABLED + rule.hashCode();
            // Default all rules to disabled (opt-in system)
            boolean ruleEnabled = prefs.getBoolean(key, false);
            // If the package is disabled, force disable all rules for that package
            if (rule.packageName != null && isPackageDisabled(rule.packageName)) {
                rule.enabled = false;
            } else {
                rule.enabled = ruleEnabled;
            }
        }

        return rules;
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
}