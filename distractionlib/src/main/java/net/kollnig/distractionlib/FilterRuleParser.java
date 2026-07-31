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
     * Width in dp an image must reach for its element to count as a media card, when
     * hasThumbnail is given as a plain "true" rather than an explicit width. Sits well above
     * the avatars and icons of a text row, and well below a video thumbnail.
     */
    public static final int DEFAULT_MIN_THUMBNAIL_WIDTH_DP = 100;

    /** Below this an image is an icon, not a thumbnail, so smaller widths are rejected. */
    private static final int MIN_ACCEPTED_THUMBNAIL_WIDTH_DP = 48;

    /**
     * Parses raw filter rules into structured FilterRule objects.
     * Rules follow the format: <package-name>##viewId=<view-id>##desc=<pipe-separated-list>##category=<group-name>##color=<hex-color>##blockTouches=<true|false>##enabled=<true|false>
     * A viewId may be combined with childPath=<path> to match a path below that view instead
     * of below the window root, and with hasThumbnail=<width-in-dp|true> to keep only matches
     * that contain an image at least that wide.
     * requiresViewId=<view-id> and requiresSelected=<view-id>[><path>] restrict a rule to
     * screens carrying those structural markers.
     * If color is not specified, defaults to white (#FFFFFF)
     * If blockTouches is not specified, defaults to true
     * If enabled is not specified, defaults to true
     */
    public List<FilterRule> parseRules(String[] raw) {
        List<FilterRule> rules = new ArrayList<>();
        String currentComment = null;

        boolean debug = Log.isLoggable(TAG, Log.DEBUG);

        for (String line : raw) {
            if (debug) {
                Log.d(TAG, "Parsing line: " + line);
            }

            if (TextUtils.isEmpty(line)) {
                continue;
            }

            if (line.trim().startsWith("//")) {
                currentComment = line.trim().substring(2).trim();
                if (debug) {
                    Log.d(TAG, "Found comment: " + currentComment);
                }
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
            String targetChildPath = null;
            int minThumbnailWidthDp = 0;
            String requiredViewId = null;
            String selectedViewId = null;
            String selectedChildPath = null;
            String category = null;
            int color = Color.WHITE;
            boolean blockTouches = true;
            boolean malformedScreenMarker = false;
            FilterRule.DescriptionMatch descriptionMatch = FilterRule.DescriptionMatch.EXACT;
            boolean malformedDescriptionMatch = false;

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
                    case "childPath":
                        targetChildPath = value;
                        break;
                    case "hasThumbnail":
                        minThumbnailWidthDp = parseThumbnailWidth(value);
                        break;
                    case "requiresViewId":
                        if (value.isEmpty()) {
                            Log.e(TAG, "Invalid requiresViewId value: " + value);
                            malformedScreenMarker = true;
                        } else {
                            requiredViewId = value;
                        }
                        break;
                    case "requiresSelected": {
                        int split = value.indexOf('>');
                        if (split < 0) {
                            if (value.isEmpty()) {
                                Log.e(TAG, "Invalid requiresSelected value: " + value);
                                malformedScreenMarker = true;
                            } else {
                                selectedViewId = value;
                                selectedChildPath = null;
                            }
                        } else {
                            String anchor = value.substring(0, split).trim();
                            String childPath = value.substring(split + 1).trim();
                            if (anchor.isEmpty() || childPath.isEmpty()) {
                                Log.e(TAG, "Invalid requiresSelected value: " + value);
                                malformedScreenMarker = true;
                            } else {
                                selectedViewId = anchor;
                                selectedChildPath = childPath;
                            }
                        }
                        break;
                    }
                    case "descMatch": {
                        FilterRule.DescriptionMatch parsed =
                                FilterRule.DescriptionMatch.parse(value);
                        if (parsed == null) {
                            Log.e(TAG, "Invalid descMatch value: " + value);
                            malformedDescriptionMatch = true;
                        } else {
                            descriptionMatch = parsed;
                        }
                        break;
                    }
                    case "comment":
                        currentComment = value;
                        break;
                    case "category":
                        category = value;
                        break;
                }
            }

            if (malformedDescriptionMatch) {
                // An unreadable comparison mode would silently fall back to exact matching,
                // which is a different rule from the one that was written. Dropping it is the
                // same fail-closed choice made for screen markers below.
                Log.e(TAG, "Dropping rule with malformed descMatch: " + line);
                continue;
            }

            if (malformedScreenMarker) {
                // A screen marker exists to keep a rule off screens it was not written for.
                // A marker that cannot be read is a broken guardrail, not an absent one, so the
                // rule is dropped rather than applied without the restriction it was given.
                Log.e(TAG, "Dropping rule with malformed screen marker: " + line);
                continue;
            }

            FilterRule.ScreenCondition screenCondition =
                    requiredViewId == null && selectedViewId == null
                            ? null
                            : new FilterRule.ScreenCondition(requiredViewId, selectedViewId,
                                    selectedChildPath);

            rules.add(new FilterRule(packageName, targetViewId, descriptions, descriptionMatch,
                    targetClassName, targetText, targetPath, targetChildPath, minThumbnailWidthDp,
                    screenCondition, color, currentComment, category, line, blockTouches));
            currentComment = null;
        }

        return rules;
    }

    /**
     * Reads the hasThumbnail value as a width in dp. Only an explicit "false" turns the check
     * off: an unreadable value falls back to the default, because dropping the check would
     * widen the rule to every element it is paired with rather than narrow it.
     */
    private int parseThumbnailWidth(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return DEFAULT_MIN_THUMBNAIL_WIDTH_DP;
        }
        if ("false".equalsIgnoreCase(value)) {
            return 0;
        }
        try {
            int widthDp = Integer.parseInt(value.endsWith("dp")
                    ? value.substring(0, value.length() - 2).trim()
                    : value);
            if (widthDp >= MIN_ACCEPTED_THUMBNAIL_WIDTH_DP) {
                return widthDp;
            }
            Log.e(TAG, "hasThumbnail width too small, using default: " + value);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid hasThumbnail value, using default: " + value);
        }
        return DEFAULT_MIN_THUMBNAIL_WIDTH_DP;
    }
}
