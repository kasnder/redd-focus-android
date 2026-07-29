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
    public final int color;
    public final String description;
    public final String category;
    public final String ruleString;
    public final boolean blockTouches;
    public boolean enabled;
    public boolean isCustom;
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
        this.packageName = pkg;
        this.targetViewId = viewId;
        this.contentDescriptions = descs;
        this.targetClassName = className;
        this.targetText = text;
        this.targetPath = path;
        this.color = color;
        this.description = description;
        this.category = category;
        this.ruleString = ruleString;
        this.blockTouches = blockTouches;
        this.enabled = true;
        this.isCustom = false;
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
