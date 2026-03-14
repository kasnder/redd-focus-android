package net.kollnig.greasemilkyway;

import android.graphics.Color;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parser for the ad-block style filter syntax.
 */
class FilterRuleParser {
    private static final String TAG = "FilterRuleParser";

    /**
     * Parses raw filter rules into structured FilterRule objects.
     * Rules follow the format: <package-name>##viewId=<view-id>##desc=<pipe-separated-list>##color=<hex-color>##blockTouches=<true|false>##enabled=<true|false>
     * If color is not specified, defaults to white (#FFFFFF)
     * If blockTouches is not specified, defaults to true
     * If enabled is not specified, defaults to true
     */
    public List<FilterRule> parseRules(String[] raw) {
        List<FilterRule> rules = new ArrayList<>();
        String currentComment = null;

        for (String line : raw) {
            Log.d(TAG, "Parsing line: " + line);

            // Skip empty lines
            if (TextUtils.isEmpty(line)) {
                continue;
            }

            // Handle comments
            if (line.trim().startsWith("//")) {
                currentComment = line.trim().substring(2).trim();
                Log.d(TAG, "Found comment: " + currentComment);
                continue;
            }

            // Split into key-value pairs
            String[] parts = line.split("##");
            if (parts.length < 2) {
                Log.w(TAG, "Invalid rule format: " + line);
                continue;
            }

            // First part is the package name
            String packageName = parts[0].trim();
            if (packageName.isEmpty()) {
                continue;
            }

            Log.d(TAG, "Package name: " + packageName);

            String targetViewId = null;
            Set<String> descriptions = new HashSet<>();
            String targetClassName = null;
            String targetText = null;
            int color = Color.WHITE;  // Default to white
            boolean blockTouches = true;  // Default to blocking touches

            // Parse the rest of the key-value pairs
            for (int i = 1; i < parts.length; i++) {
                String part = parts[i];
                if (!part.contains("=")) {
                    continue;
                }

                String[] kv = part.split("=", 2);
                String key = kv[0].trim();
                String value = kv[1].trim();

                Log.d(TAG, "Parsing key-value: " + key + "=" + value);

                switch (key) {
                    case "viewId":
                        targetViewId = value;
                        Log.d(TAG, "Found view ID: " + targetViewId);
                        break;
                    case "desc":
                        // Split descriptions by pipe
                        for (String desc : value.split("\\|")) {
                            desc = desc.trim();
                            if (!desc.isEmpty()) {
                                descriptions.add(desc);
                                Log.d(TAG, "Added description: " + desc);
                            }
                        }
                        break;
                    case "color":
                        try {
                            color = Color.parseColor(value.startsWith("#") ? value : "#" + value);
                            Log.d(TAG, "Parsed color: " + color);
                        } catch (IllegalArgumentException e) {
                            Log.e(TAG, "Invalid color format: " + value);
                        }
                        break;
                    case "blockTouches":
                        blockTouches = Boolean.parseBoolean(value);
                        Log.d(TAG, "Parsed blockTouches: " + blockTouches);
                        break;
                    case "className":
                        targetClassName = value;
                        Log.d(TAG, "Found className: " + targetClassName);
                        break;
                    case "text":
                        targetText = value;
                        Log.d(TAG, "Found text: " + targetText);
                        break;
                    case "comment":
                        currentComment = value;
                        Log.d(TAG, "Found comment: " + currentComment);
                        break;
                }
            }

            // Create the rule
            FilterRule rule = new FilterRule(packageName, targetViewId, descriptions, targetClassName, targetText, color, currentComment, line, blockTouches);
            Log.d(TAG, "Created rule: package=" + packageName +
                    ", viewId=" + targetViewId +
                    ", descriptions=" + descriptions +
                    ", color=" + color +
                    ", blockTouches=" + blockTouches);
            rules.add(rule);
        }

        return rules;
    }
}
