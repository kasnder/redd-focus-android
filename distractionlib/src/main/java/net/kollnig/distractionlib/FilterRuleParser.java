package net.kollnig.distractionlib;

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
public class FilterRuleParser {
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

            if (TextUtils.isEmpty(line)) {
                continue;
            }

            if (line.trim().startsWith("//")) {
                currentComment = line.trim().substring(2).trim();
                Log.d(TAG, "Found comment: " + currentComment);
                continue;
            }

            String[] parts = line.split("##");
            if (parts.length < 2) {
                Log.w(TAG, "Invalid rule format: " + line);
                continue;
            }

            String packageName = parts[0].trim();
            if (packageName.isEmpty()) {
                continue;
            }

            String targetViewId = null;
            Set<String> descriptions = new HashSet<>();
            String targetClassName = null;
            String targetText = null;
            String targetPath = null;
            int color = Color.WHITE;
            boolean blockTouches = true;

            for (int i = 1; i < parts.length; i++) {
                String part = parts[i];
                if (!part.contains("=")) {
                    continue;
                }

                String[] kv = part.split("=", 2);
                String key = kv[0].trim();
                String value = kv[1].trim();

                switch (key) {
                    case "viewId":
                        targetViewId = value;
                        break;
                    case "desc":
                        for (String desc : value.split("\\|")) {
                            desc = desc.trim();
                            if (!desc.isEmpty()) {
                                descriptions.add(desc);
                            }
                        }
                        break;
                    case "color":
                        try {
                            color = Color.parseColor(value.startsWith("#") ? value : "#" + value);
                        } catch (IllegalArgumentException e) {
                            Log.e(TAG, "Invalid color format: " + value);
                        }
                        break;
                    case "blockTouches":
                        blockTouches = Boolean.parseBoolean(value);
                        break;
                    case "className":
                        targetClassName = value;
                        break;
                    case "text":
                        targetText = value;
                        break;
                    case "path":
                        targetPath = value;
                        break;
                    case "comment":
                        currentComment = value;
                        break;
                }
            }

            rules.add(new FilterRule(packageName, targetViewId, descriptions, targetClassName,
                    targetText, targetPath, color, currentComment, line, blockTouches));
        }

        return rules;
    }
}
