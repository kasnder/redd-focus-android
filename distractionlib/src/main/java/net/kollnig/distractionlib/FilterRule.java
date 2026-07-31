package net.kollnig.distractionlib;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Represents a single content blocking rule.
 */
public class FilterRule {
    public final String packageName;
    public final String targetViewId;
    public final Set<String> contentDescriptions;
    /**
     * How {@link #contentDescriptions} are compared against a node's description.
     *
     * <p>A content description exists to be read aloud, so it carries live status: badge counts
     * and selected states are appended to the label ("Unread filter, 25, unselected",
     * "someone's story, 3 of 27, Unseen."). Matching such a description exactly means the rule
     * stops working the moment the count changes. That is a property of the platform, not of
     * any one app, so the choice is offered on every rule rather than special-cased anywhere.
     */
    public final DescriptionMatch descriptionMatch;
    public final String targetClassName;
    public final String targetText;
    public final String targetPath;
    /**
     * Path matched below the node identified by {@link #targetViewId} instead of below the
     * window root. Layout changes above the anchor cannot break such a rule.
     */
    public final String targetChildPath;
    /**
     * Minimum width in dp of an image inside the matched element for that element to count as
     * a media card. Zero disables the check. Measured absolutely rather than relative to the
     * element, because apps switch to compact card layouts on larger screens: a thumbnail
     * keeps roughly the same dp size there while its share of the row width halves.
     */
    public final int minThumbnailWidthDp;
    /**
     * Conditions on the surrounding screen that must hold before the rule is applied at all,
     * or null to apply it on every screen of the app. Lets one rule target a single screen
     * where several screens share the same list view ID.
     */
    public final ScreenCondition screenCondition;
    public final int color;
    public final String description;
    public final String category;
    public final String ruleString;
    public final boolean blockTouches;
    public boolean enabled;
    public boolean isCustom;
    /**
     * Whether this rule opens a screen rather than hiding one. Presentation and persistence
     * state, like {@link #isCustom}, so deliberately absent from {@link #identity()}: the two
     * kinds are keyed in separate preference name spaces rather than distinguished by identity.
     */
    public boolean isNavigation;
    public boolean isPaused;
    public long pausedUntil;

    public FilterRule(String pkg, String viewId, Set<String> descs, String className, String text,
                      String path, int color, String description, String ruleString,
                      boolean blockTouches) {
        this(pkg, viewId, descs, className, text, path, color, description, null,
                ruleString, blockTouches);
    }

    public FilterRule(String pkg, String viewId, Set<String> descs, String className, String text,
                      String path, int color, String description, String category,
                      String ruleString, boolean blockTouches) {
        this(pkg, viewId, descs, className, text, path, null, 0, color, description, category,
                ruleString, blockTouches);
    }

    /** How a rule's content descriptions are compared against a node's. */
    public enum DescriptionMatch {
        /** The description must be exactly the stored value. The default. */
        EXACT,
        /**
         * The description must begin with the stored value. Deliberately a plain prefix with no
         * knowledge of how status is appended: encoding a separator convention in the matcher
         * would be a rule the user cannot see from reading their own rule text. The picker
         * suggests where to cut, and checks the result is unambiguous before offering it.
         */
        PREFIX,
        /**
         * The description must contain the stored value anywhere. The loosest option and the
         * only one the picker never generates -- it is for hand-written rules whose stable part
         * sits at the end ("3 unread messages"), where the author can see what they are doing.
         */
        SUBSTRING;

        static DescriptionMatch parse(String value) {
            if (value == null) return null;
            switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "exact":
                    return EXACT;
                case "prefix":
                    return PREFIX;
                case "substring":
                    return SUBSTRING;
                default:
                    return null;
            }
        }

        String ruleValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Whether a node's description satisfies this rule, under whichever comparison the rule
     * asked for. Shared by every match site so the three of them cannot drift apart.
     */
    public boolean matchesDescription(CharSequence candidate) {
        if (candidate == null || contentDescriptions == null || contentDescriptions.isEmpty()) {
            return false;
        }
        String value = candidate.toString();
        for (String target : contentDescriptions) {
            switch (descriptionMatch) {
                case PREFIX:
                    if (value.startsWith(target)) return true;
                    break;
                case SUBSTRING:
                    if (value.contains(target)) return true;
                    break;
                default:
                    if (value.equals(target)) return true;
                    break;
            }
        }
        return false;
    }

    /**
     * Marks which screen a rule belongs to, using structure rather than any visible label, so
     * the same rule works whatever language the app is displayed in.
     *
     * <p>Both conditions are stated positively: a rule applies only once its markers are found.
     * If an app update removes or renames a marker, the rule therefore stops matching and the
     * content stays visible, rather than spreading to screens it was never meant to cover.
     */
    public static class ScreenCondition {
        /** View ID that must be present and visible somewhere on the screen. */
        public final String requiredViewId;
        /** View ID of the anchor whose node, or descendant at {@link #selectedChildPath}, must be selected. */
        public final String selectedViewId;
        /** Path below the anchor to the node that must be selected, or null for the anchor itself. */
        public final String selectedChildPath;

        public ScreenCondition(String requiredViewId, String selectedViewId,
                               String selectedChildPath) {
            this.requiredViewId = requiredViewId;
            this.selectedViewId = selectedViewId;
            this.selectedChildPath = selectedChildPath;
        }

        /** Canonical text for {@link FilterRule#identity()}. */
        String identity() {
            return (requiredViewId == null ? "" : requiredViewId) + '\n'
                    + (selectedViewId == null ? "" : selectedViewId) + '\n'
                    + (selectedChildPath == null ? "" : selectedChildPath);
        }
    }

    public FilterRule(String pkg, String viewId, Set<String> descs, String className, String text,
                      String path, String childPath, int minThumbnailWidthDp, int color,
                      String description, String category, String ruleString,
                      boolean blockTouches) {
        this(pkg, viewId, descs, className, text, path, childPath, minThumbnailWidthDp, null,
                color, description, category, ruleString, blockTouches);
    }

    public FilterRule(String pkg, String viewId, Set<String> descs, String className, String text,
                      String path, String childPath, int minThumbnailWidthDp,
                      ScreenCondition screenCondition, int color, String description,
                      String category, String ruleString, boolean blockTouches) {
        this(pkg, viewId, descs, DescriptionMatch.EXACT, className, text, path, childPath,
                minThumbnailWidthDp, screenCondition, color, description, category, ruleString,
                blockTouches);
    }

    public FilterRule(String pkg, String viewId, Set<String> descs,
                      DescriptionMatch descriptionMatch, String className, String text,
                      String path, String childPath, int minThumbnailWidthDp,
                      ScreenCondition screenCondition, int color, String description,
                      String category, String ruleString, boolean blockTouches) {
        this.descriptionMatch =
                descriptionMatch == null ? DescriptionMatch.EXACT : descriptionMatch;
        this.packageName = pkg;
        this.targetViewId = viewId;
        this.contentDescriptions = descs;
        this.targetClassName = className;
        this.targetText = text;
        this.targetPath = path;
        this.targetChildPath = childPath;
        this.minThumbnailWidthDp = minThumbnailWidthDp;
        this.screenCondition = screenCondition;
        this.color = color;
        this.description = description;
        this.category = category;
        this.ruleString = ruleString;
        this.blockTouches = blockTouches;
        this.enabled = true;
        this.isCustom = false;
        this.isNavigation = false;
        this.isPaused = false;
        this.pausedUntil = 0;
    }

    public boolean matchesPackage(CharSequence pkgName) {
        return pkgName != null && packageName.contentEquals(pkgName);
    }

    /**
     * A stable identifier for <em>what this rule matches</em>, for use as a
     * persistence key.
     *
     * <p>Deliberately built from the matching fields, plus blockTouches, which
     * is behavioural rather than presentational: two rules that target the
     * same element but handle touches differently are genuinely different
     * rules and must not share a key. The raw rule string also carries purely
     * presentational metadata -- comment, category, colour -- so keying saved
     * state on it means that fixing a typo in a bundled rule's comment
     * silently orphans every existing user's enable/pause state for that
     * rule.
     *
     * <p>Content descriptions are sorted so that the result does not depend on
     * the iteration order of the underlying set.
     *
     * <p>{@link #targetChildPath}, {@link #minThumbnailWidthDp} and {@link #screenCondition}
     * are matching fields too -- two rules that share a viewId but differ in one of these
     * select different elements -- so they go into the identity alongside the rest, rather
     * than being left out as if they were presentational.
     *
     * <p>{@link #descriptionMatch} is behavioural in the same way, but it is appended at the
     * end and only when it is not the default. Adding a field in the middle would change the
     * identity of every rule ever written and orphan the saved state of all of them; this way
     * a rule that does not use the feature hashes exactly as it did before it existed. Any
     * future addition here must follow the same shape.
     */
    public String identity() {
        List<String> descs = contentDescriptions == null
                ? Collections.emptyList()
                : new ArrayList<>(contentDescriptions);
        Collections.sort(descs);

        StringBuilder identity = new StringBuilder()
                .append(packageName).append('\n')
                .append(targetViewId == null ? "" : targetViewId).append('\n')
                .append(String.join("|", descs)).append('\n')
                .append(targetClassName == null ? "" : targetClassName).append('\n')
                .append(targetText == null ? "" : targetText).append('\n')
                .append(targetPath == null ? "" : targetPath).append('\n')
                .append(targetChildPath == null ? "" : targetChildPath).append('\n')
                .append(minThumbnailWidthDp).append('\n')
                .append(screenCondition == null ? "" : screenCondition.identity()).append('\n')
                .append(blockTouches);

        if (descriptionMatch != DescriptionMatch.EXACT) {
            identity.append('\n').append(descriptionMatch.name());
        }
        return identity.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FilterRule filterRule = (FilterRule) o;
        return ruleString.equals(filterRule.ruleString);
    }

    @Override
    public int hashCode() {
        return ruleString.hashCode();
    }
}
