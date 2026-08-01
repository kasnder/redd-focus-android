package net.kollnig.greasemilkyway;

import net.kollnig.distractionlib.FilterRule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared display-row model. A row always changes all of its rule parts together. */
final class RuleRows {
    private RuleRows() { }

    static List<List<FilterRule>> mergeRules(List<FilterRule> rules) {
        List<List<FilterRule>> rows = new ArrayList<>();
        Map<String, List<FilterRule>> byComment = new LinkedHashMap<>();
        for (FilterRule rule : rules) {
            String comment = rule.description == null ? "" : rule.description.trim();
            if (comment.isEmpty() || rule.isNavigation) {
                List<FilterRule> row = new ArrayList<>();
                row.add(rule);
                rows.add(row);
                continue;
            }
            String key = rule.isCustom + "\0" + comment;
            List<FilterRule> row = byComment.get(key);
            if (row == null) {
                row = new ArrayList<>();
                byComment.put(key, row);
                rows.add(row);
            }
            row.add(rule);
        }
        return rows;
    }

    static boolean isRowEnabled(List<FilterRule> row) {
        for (FilterRule rule : row) {
            if (!rule.enabled) return false;
        }
        return true;
    }

    static boolean isNavigationRow(List<FilterRule> row) {
        return !row.isEmpty() && row.get(0).isNavigation;
    }

    static void markOtherNavigationRulesDisabled(List<FilterRule> rules, List<FilterRule> kept) {
        if (kept.isEmpty()) return;
        String packageName = kept.get(0).packageName;
        for (FilterRule rule : rules) {
            if (rule.isNavigation && !kept.contains(rule)
                    && packageName.equals(rule.packageName)) {
                rule.enabled = false;
            }
        }
    }

    static Map<String, List<List<FilterRule>>> groupRows(List<List<FilterRule>> rows,
            String navigationTitle, String customTitle, String otherTitle) {
        Map<String, List<List<FilterRule>>> groups = new LinkedHashMap<>();
        for (List<FilterRule> row : rows) {
            FilterRule rule = row.get(0);
            String title;
            if (rule.isNavigation) title = navigationTitle;
            else if (rule.category != null && !rule.category.trim().isEmpty()) title = rule.category.trim();
            else if (rule.isCustom) title = customTitle;
            else title = otherTitle;
            groups.computeIfAbsent(title, ignored -> new ArrayList<>()).add(row);
        }
        return groups;
    }

    static String compactNavigationDestination(FilterRule rule) {
        String description = rule.description == null ? "" : rule.description.trim();
        if (description.startsWith("Go to ")) return description.substring("Go to ".length());
        if (description.startsWith("Open the ")) {
            String destination = description.substring("Open the ".length());
            int instead = destination.indexOf(" instead");
            return (instead >= 0 ? destination.substring(0, instead) : destination);
        }
        return description;
    }
}
