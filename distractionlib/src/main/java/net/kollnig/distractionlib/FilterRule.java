package net.kollnig.distractionlib;

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
    public final String ruleString;
    public final boolean blockTouches;
    public boolean enabled;
    public boolean isCustom;
    public boolean isPaused;
    public long pausedUntil;

    public FilterRule(String pkg, String viewId, Set<String> descs, String className, String text,
                      String path, int color, String description, String ruleString,
                      boolean blockTouches) {
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

    public boolean matchesPackage(CharSequence pkgName) {
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
