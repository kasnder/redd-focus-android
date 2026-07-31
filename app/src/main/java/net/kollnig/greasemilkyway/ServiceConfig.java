package net.kollnig.greasemilkyway;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import net.kollnig.distractionlib.FilterRule;
import net.kollnig.distractionlib.FilterRuleParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    private static final String KEY_PREFS_VERSION = "prefs_version";
    /**
     * Navigation rules get their own key space. They are matched like blocking rules and so
     * can share an identity with one, but they are a separate opt-in: enabling "hide the feed"
     * must not silently also start moving the user to another screen.
     */
    public static final String KEY_NAVIGATION_RULE_ENABLED = "nav_rule_enabled_";
    private static final String KEY_CUSTOM_NAVIGATION_RULES = "custom_navigation_rules";
    private static final String DEFAULT_RULES_FILE = "distraction_rules.txt";
    private static final String NAVIGATION_RULES_FILE = "navigation_rules.txt";
    /**
     * The bundled rules as shipped by the last release that used legacy
     * preference keys. Frozen; see the file's own header.
     */
    private static final String LEGACY_RULES_V0_FILE = "legacy_rules_v0.txt";
    /** 1: rule keys derived from rule identity rather than ruleString.hashCode(). */
    private static final int PREFS_VERSION = 1;

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

    /**
     * Preference suffix identifying a rule. Derived from the rule's matching
     * fields rather than its raw text, so editing a bundled rule's comment,
     * category or colour preserves the user's saved state for it. Truncated to
     * 64 bits, which is ample for a rule set of this size and far more
     * collision-resistant than the 32-bit String.hashCode() used previously.
     */
    static String ruleKeySuffix(FilterRule rule) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(rule.identity().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated on every Android platform.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Reads a bundled rules file, dropping blank lines. Returns null -- as
     * distinct from an empty list -- if the asset could not be read at all,
     * since callers treat a failed read as "rule set incomplete" rather than
     * "no rules".
     */
    private List<String> readAssetLines(String assetName) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(assetName), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lines.add(line);
                }
            }
            return lines;
        } catch (IOException e) {
            Log.e(TAG, "Failed to read " + assetName, e);
            return null;
        }
    }

    public void setRuleEnabled(FilterRule rule, boolean enabled) {
        String key = KEY_RULE_ENABLED + ruleKeySuffix(rule);
        prefs.edit().putBoolean(key, enabled).apply();
    }

    public boolean isRuleEnabled(FilterRule rule) {
        return prefs.getBoolean(KEY_RULE_ENABLED + ruleKeySuffix(rule), false);
    }

    public void setRulePausedUntil(FilterRule rule, long timestampMillis) {
        String key = KEY_PAUSE_UNTIL_RULE_ + ruleKeySuffix(rule);
        prefs.edit().putLong(key, timestampMillis).apply();
    }

    public long getRulePausedUntil(FilterRule rule) {
        String key = KEY_PAUSE_UNTIL_RULE_ + ruleKeySuffix(rule);
        return prefs.getLong(key, 0);
    }

    /**
     * Moves saved state from the legacy ruleString.hashCode() keys to the
     * identity-derived ones. Runs once per rule set it is given; without it,
     * upgrading would silently reset every rule the user had enabled or
     * paused.
     *
     * <p>The legacy key is the hash of the rule's <em>text</em>, so it can
     * only be recomputed from the text the user's previous version shipped,
     * not from the current rules: bundled rule lines get edited between
     * releases (adding a category, fixing a comment) without that being meant
     * to reset anything. {@link #LEGACY_RULES_V0_FILE} therefore holds a
     * frozen copy of the rules as last shipped under the old scheme, and the
     * legacy keys are derived from those lines. Their destination is the
     * identity-derived key, which is unaffected by such edits, so the state
     * lands on the current rule regardless of how its text has changed.
     * {@code knownRules} is migrated too, which is what covers custom rules:
     * those are stored verbatim, so their text is its own snapshot.
     *
     * <p>{@code completeRuleSet} must be false whenever {@code knownRules}
     * might be missing rules -- e.g. the bundled rules file failed to load.
     * In that case legacy keys for the rules present (typically just custom
     * ones) are still migrated, but the prefs version is left unbumped so a
     * later call with the full rule set can finish migrating the rest,
     * rather than the partial run being mistaken for a complete one.
     */
    private void migrateRuleKeys(List<FilterRule> knownRules, boolean completeRuleSet) {
        if (prefs.getInt(KEY_PREFS_VERSION, 0) >= PREFS_VERSION) {
            return;
        }

        List<FilterRule> toMigrate = new ArrayList<>(knownRules);
        List<String> legacyLines = readAssetLines(LEGACY_RULES_V0_FILE);
        if (legacyLines != null && !legacyLines.isEmpty()) {
            toMigrate.addAll(ruleParser.parseRules(legacyLines.toArray(new String[0])));
        }

        SharedPreferences.Editor editor = prefs.edit();
        for (FilterRule rule : toMigrate) {
            String legacy = String.valueOf(rule.ruleString.hashCode());
            String current = ruleKeySuffix(rule);

            String legacyEnabled = KEY_RULE_ENABLED + legacy;
            String currentEnabled = KEY_RULE_ENABLED + current;
            if (prefs.contains(legacyEnabled)) {
                if (!prefs.contains(currentEnabled)) {
                    editor.putBoolean(currentEnabled, prefs.getBoolean(legacyEnabled, false));
                }
                editor.remove(legacyEnabled);
            }

            String legacyPause = KEY_PAUSE_UNTIL_RULE_ + legacy;
            String currentPause = KEY_PAUSE_UNTIL_RULE_ + current;
            if (prefs.contains(legacyPause)) {
                if (!prefs.contains(currentPause)) {
                    editor.putLong(currentPause, prefs.getLong(legacyPause, 0));
                }
                editor.remove(legacyPause);
            }
        }
        // A missing snapshot leaves the bundled rules' legacy state untouched,
        // which is the same partial-migration case as a missing rules file.
        if (completeRuleSet && legacyLines != null) {
            editor.putInt(KEY_PREFS_VERSION, PREFS_VERSION);
        }
        editor.apply();
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
        
        // Add default rules from file. Collected first and parsed in one call:
        // parsing line by line allocated a single-element array and re-entered
        // the parser for every rule in the file.
        List<String> defaultRuleLines = readAssetLines(DEFAULT_RULES_FILE);
        boolean defaultRulesReadOk = defaultRuleLines != null;
        if (defaultRuleLines != null && !defaultRuleLines.isEmpty()) {
            rules.addAll(ruleParser.parseRules(defaultRuleLines.toArray(new String[0])));
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

        // Rules are all known at this point, so this is where legacy keys can
        // be mapped onto their identity-derived replacements. If the bundled
        // rules file failed to load, the rule set here is incomplete, so the
        // migration must not be recorded as done -- otherwise the bundled
        // rules' legacy state would be orphaned permanently instead of picked
        // up on a later, successful load.
        migrateRuleKeys(rules, defaultRulesReadOk);

        // Apply saved enabled states
        long currentTime = System.currentTimeMillis();
        for (FilterRule rule : rules) {
            boolean ruleEnabled = isRuleEnabled(rule);
            
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

    /**
     * The bundled navigation rules, each carrying its saved on/off state.
     *
     * <p>Not folded into {@link #getRules()}: those drive overlays, and a navigation rule that
     * reached that pipeline would paint a box over the very control it needs to click. Nor is
     * it gated on {@link #isPackageDisabled}, which governs blocking -- someone who wants
     * WhatsApp to open on a chat list has not thereby asked for anything to be hidden.
     */
    public List<FilterRule> getNavigationRules() {
        List<FilterRule> rules = new ArrayList<>();

        List<String> lines = readAssetLines(NAVIGATION_RULES_FILE);
        if (lines != null && !lines.isEmpty()) {
            rules.addAll(ruleParser.parseRules(lines.toArray(new String[0])));
        }

        // Rules the user built with the element picker. Appended rather than merged: a failed
        // asset read must not take the user's own rules down with it.
        String[] custom = getCustomNavigationRules();
        if (custom != null) {
            List<FilterRule> parsed = ruleParser.parseRules(custom);
            for (FilterRule rule : parsed) {
                rule.isCustom = true;
            }
            rules.addAll(parsed);
        }

        for (FilterRule rule : rules) {
            rule.isNavigation = true;
            // The app switch in the rules list is the master switch for everything ReDD Focus
            // does inside that app. Navigation rules are shown under it, so they have to obey
            // it too -- an app switched off that still moved the user around would be lying.
            rule.enabled = isNavigationRuleEnabled(rule)
                    && !isPackageDisabled(rule.packageName);
        }
        return rules;
    }

    /**
     * Whether a navigation rule with the same target is already stored, so the picker does not
     * append a second copy. Compared by identity rather than text: two rules differing only in
     * their comment share a preference key, so both would be driven by one switch.
     */
    public boolean hasNavigationRuleLike(FilterRule rule) {
        String key = ruleKeySuffix(rule);
        for (FilterRule existing : getNavigationRules()) {
            if (ruleKeySuffix(existing).equals(key)) {
                return true;
            }
        }
        return false;
    }

    public String[] getCustomNavigationRules() {
        String rules = prefs.getString(KEY_CUSTOM_NAVIGATION_RULES, "");
        return rules.isEmpty() ? null : rules.split("\n");
    }

    public void addCustomNavigationRule(String ruleString) {
        String existing = prefs.getString(KEY_CUSTOM_NAVIGATION_RULES, "");
        String updated = existing.isEmpty() ? ruleString : existing + "\n" + ruleString;
        prefs.edit().putString(KEY_CUSTOM_NAVIGATION_RULES, updated).apply();
    }

    /** Removes the first custom navigation rule matching the given text exactly. */
    public void removeCustomNavigationRule(String ruleString) {
        String[] existing = getCustomNavigationRules();
        if (existing == null) return;

        List<String> updated = new ArrayList<>();
        boolean removed = false;
        for (String rule : existing) {
            if (!removed && rule.equals(ruleString)) {
                removed = true;
                continue;
            }
            updated.add(rule);
        }

        if (removed) {
            prefs.edit()
                    .putString(KEY_CUSTOM_NAVIGATION_RULES, String.join("\n", updated))
                    .apply();
        }
    }

    public boolean isNavigationRuleEnabled(FilterRule rule) {
        // Opt-in, like blocking rules: nothing starts moving the user around unasked.
        return prefs.getBoolean(KEY_NAVIGATION_RULE_ENABLED + ruleKeySuffix(rule), false);
    }

    /**
     * Switches a navigation rule on or off, switching off any other rule for the same app.
     *
     * <p>Only one navigation rule per app can actually run: {@code AutoNavigator} resolves a
     * package to a single rule. Letting several appear enabled was worse than the limitation
     * itself, because the rules list counted them all and reported "2 of 3 active" while one of
     * the two was never going to fire. Enforcing the limit here makes what the list shows true.
     *
     * <p>This method is what maintains the limit, so there is only ever one rule to switch off;
     * it clears any others by iteration rather than assuming, but the plural case does not
     * arise and is not something to design around.
     *
     * @return whether another rule was switched off, for telling the user why
     */
    public boolean setNavigationRuleEnabled(FilterRule rule, boolean enabled) {
        String key = ruleKeySuffix(rule);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(KEY_NAVIGATION_RULE_ENABLED + key, enabled);

        boolean displaced = false;
        if (enabled && rule.packageName != null) {
            for (FilterRule other : getNavigationRules()) {
                String otherKey = ruleKeySuffix(other);
                if (otherKey.equals(key) || !rule.packageName.equals(other.packageName)) {
                    continue;
                }
                // Read from prefs rather than the rule's enabled flag: that one is already
                // masked by the package switch, so a rule under a disabled app would look off
                // and be silently left on, to surface again when the app was switched back on.
                if (isNavigationRuleEnabled(other)) {
                    editor.putBoolean(KEY_NAVIGATION_RULE_ENABLED + otherKey, false);
                    displaced = true;
                }
            }
        }

        editor.apply();
        return displaced;
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
