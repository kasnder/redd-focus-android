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
     */
    public String identity() {
        List<String> descs = contentDescriptions == null
                ? Collections.emptyList()
                : new ArrayList<>(contentDescriptions);
        Collections.sort(descs);

        return new StringBuilder()
                .append(packageName).append('\n')
                .append(targetViewId == null ? "" : targetViewId).append('\n')
                .append(String.join("|", descs)).append('\n')
                .append(targetClassName == null ? "" : targetClassName).append('\n')
                .append(targetText == null ? "" : targetText).append('\n')
                .append(targetPath == null ? "" : targetPath).append('\n')
                .append(targetChildPath == null ? "" : targetChildPath).append('\n')
                .append(minThumbnailWidthDp).append('\n')
                .append(screenCondition == null ? "" : screenCondition.identity()).append('\n')
                .append(blockTouches)
                .toString();
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
