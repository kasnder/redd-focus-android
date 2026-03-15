package net.kollnig.greasemilkyway;

import java.util.Set;

/**
 * Represents a single content blocking rule.
 */
public class FilterRule {
    final String packageName;
    final String targetViewId;
    final Set<String> contentDescriptions;
    final String targetClassName;
    final String targetText;
    final String targetPath;
    final int color;
    final String description;
    final String ruleString;
    final boolean blockTouches;
    boolean enabled;
    boolean isCustom;
    boolean isPaused;
    long pausedUntil;

    FilterRule(String pkg, String viewId, Set<String> descs, String className, String text,
               String path, int color, String description, String ruleString, boolean blockTouches) {
        this.packageName = pkg;
        this.targetViewId = viewId;
        this.contentDescriptions = descs;
        this.targetClassName = className;
        this.targetText = text;
        this.targetPath = path;
        this.color = color;
        this.description = description;
        this.ruleString = ruleString;
        this.blockTouches = blockTouches;
        this.enabled = true;
        this.isCustom = false;
        this.isPaused = false;
        this.pausedUntil = 0;
    }

    boolean matchesPackage(CharSequence pkgName) {
        return pkgName != null && packageName.contentEquals(pkgName);
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
