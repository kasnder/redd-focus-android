package net.kollnig.greasemilkyway;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;

import net.kollnig.distractionlib.FilterRule;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Per-app rules, with navigation deliberately kept out of blocking categories. */
final class AppDetailAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_LABEL = 0;
    private static final int TYPE_NAVIGATION = 1;
    private static final int TYPE_GROUP = 2;
    private static final int TYPE_RULE = 3;
    private static final String COLLAPSE_PREFS = "AppCollapseStates";

    private final Context context;
    private final ServiceConfig config;
    private final String packageName;
    private final SharedPreferences collapsePrefs;
    private final List<Object> items = new ArrayList<>();
    private List<FilterRule> currentRules = new ArrayList<>();

    AppDetailAdapter(Context context, ServiceConfig config, String packageName) {
        this.context = context;
        this.config = config;
        this.packageName = packageName;
        collapsePrefs = context.getSharedPreferences(COLLAPSE_PREFS, Context.MODE_PRIVATE);
    }

    void setRules(List<FilterRule> rules) {
        currentRules = new ArrayList<>(rules);
        rebuildItems();
    }

    private void rebuildItems() {
        items.clear();
        List<FilterRule> navigation = new ArrayList<>();
        List<FilterRule> blocking = new ArrayList<>();
        for (FilterRule rule : currentRules) {
            (rule.isNavigation ? navigation : blocking).add(rule);
        }

        if (!navigation.isEmpty()) {
            items.add(new LabelItem(context.getString(R.string.navigation_section_title,
                    AppCatalog.getDisplayName(context, packageName))));
            items.add(new NavigationItem(null));
            for (FilterRule rule : navigation) items.add(new NavigationItem(rule));
        }

        items.add(new LabelItem(context.getString(R.string.blocking_section_title)));
        Map<String, List<List<FilterRule>>> groups = RuleRows.groupRows(
                RuleRows.mergeRules(blocking), "",
                context.getString(R.string.rule_group_custom),
                context.getString(R.string.rule_group_other));
        for (Map.Entry<String, List<List<FilterRule>>> entry : groups.entrySet()) {
            boolean expanded = collapsePrefs.getBoolean(expansionKey(entry.getKey()), false);
            GroupItem group = new GroupItem(entry.getKey(), entry.getValue(), expanded);
            items.add(group);
            if (expanded) {
                for (List<FilterRule> row : entry.getValue()) items.add(new RuleItem(row));
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        Object item = items.get(position);
        if (item instanceof LabelItem) return TYPE_LABEL;
        if (item instanceof NavigationItem) return TYPE_NAVIGATION;
        if (item instanceof GroupItem) return TYPE_GROUP;
        return TYPE_RULE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (type == TYPE_LABEL) {
            return new LabelHolder(inflater.inflate(R.layout.item_custom_rule_group, parent, false));
        }
        if (type == TYPE_NAVIGATION) {
            return new NavigationHolder(
                    inflater.inflate(R.layout.item_navigation_option, parent, false));
        }
        if (type == TYPE_GROUP) {
            return new GroupHolder(inflater.inflate(R.layout.item_rule_section, parent, false));
        }
        return new RuleHolder(inflater.inflate(R.layout.item_rule, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = items.get(position);
        if (holder instanceof LabelHolder) {
            ((LabelHolder) holder).label.setText(((LabelItem) item).text);
        } else if (holder instanceof NavigationHolder) {
            bindNavigation((NavigationHolder) holder, (NavigationItem) item);
        } else if (holder instanceof GroupHolder) {
            bindGroup((GroupHolder) holder, (GroupItem) item);
        } else {
            bindRule((RuleHolder) holder, (RuleItem) item);
        }
    }

    private void bindNavigation(NavigationHolder holder, NavigationItem item) {
        boolean packageEnabled = !config.isPackageDisabled(packageName);
        FilterRule rule = item.rule;
        holder.label.setText(rule == null ? context.getString(R.string.open_normally)
                : displayName(rule, false));
        holder.radio.setChecked(rule == null ? !hasEnabledNavigationRule() : rule.enabled);
        holder.radio.setEnabled(packageEnabled);
        holder.itemView.setEnabled(packageEnabled);
        holder.itemView.setOnClickListener(packageEnabled ? v -> {
            if (rule == null) {
                config.disableAllNavigationRules(packageName);
            } else {
                config.setNavigationRuleEnabled(rule, true);
            }
            reloadNavigationState();
            notifyService();
        } : null);
    }

    private void bindGroup(GroupHolder holder, GroupItem group) {
        int active = 0;
        for (List<FilterRule> row : group.rows) {
            if (RuleRows.isRowEnabled(row)) active++;
        }
        boolean packageEnabled = !config.isPackageDisabled(packageName);
        holder.title.setText(group.title);
        holder.count.setText(context.getString(R.string.active_rule_fraction,
                active, group.rows.size()));
        holder.indicator.setVisibility(View.VISIBLE);
        holder.indicator.setText(group.expanded ? "⌄" : "›");
        holder.switchView.setOnCheckedChangeListener(null);
        holder.switchView.setChecked(active == group.rows.size() && !group.rows.isEmpty());
        holder.switchView.setEnabled(packageEnabled);
        holder.switchView.setContentDescription(group.title);
        holder.switchView.setOnCheckedChangeListener((button, enabled) -> {
            if (!enabled && context instanceof FrictionGateHost) {
                holder.switchView.setOnCheckedChangeListener(null);
                holder.switchView.setChecked(true);
                ((FrictionGateHost) context).runWithFrictionGate(
                        context.getString(R.string.disable_group_title, group.title),
                        () -> showPauseOrDisable(group.rows));
            } else if (enabled) {
                setRowsEnabled(group.rows, true);
            }
        });
        holder.itemView.setOnClickListener(v -> {
            collapsePrefs.edit().putBoolean(expansionKey(group.title), !group.expanded).apply();
            rebuildItems();
        });
    }

    private void bindRule(RuleHolder holder, RuleItem item) {
        FilterRule primary = item.parts.get(0);
        holder.description.setText(displayName(primary, true));
        long pausedUntil = 0;
        for (FilterRule rule : item.parts) {
            if (rule.isPaused) pausedUntil = Math.max(pausedUntil, rule.pausedUntil);
        }
        if (pausedUntil > System.currentTimeMillis()) {
            holder.details.setText(context.getString(R.string.app_paused_resumes,
                    DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(new Date(pausedUntil))));
            holder.details.setVisibility(View.VISIBLE);
        } else {
            holder.details.setVisibility(View.GONE);
        }

        boolean packageEnabled = !config.isPackageDisabled(packageName);
        holder.switchView.setOnCheckedChangeListener(null);
        holder.switchView.setChecked(RuleRows.isRowEnabled(item.parts));
        holder.switchView.setEnabled(packageEnabled);
        holder.switchView.setContentDescription(displayName(primary, true));
        holder.switchView.setOnCheckedChangeListener((button, enabled) -> {
            if (!enabled && context instanceof FrictionGateHost) {
                holder.switchView.setOnCheckedChangeListener(null);
                holder.switchView.setChecked(true);
                ((FrictionGateHost) context).runWithFrictionGate(
                        context.getString(R.string.disable_rule_title),
                        () -> showPauseOrDisable(
                                java.util.Collections.singletonList(item.parts)));
            } else if (enabled) {
                setRowsEnabled(java.util.Collections.singletonList(item.parts), true);
            }
        });
    }

    private void setRowsEnabled(List<List<FilterRule>> rows, boolean enabled) {
        for (List<FilterRule> row : rows) {
            for (FilterRule rule : row) {
                rule.enabled = enabled;
                rule.isPaused = false;
                rule.pausedUntil = 0;
                config.setRuleEnabled(rule, enabled);
                config.setRulePausedUntil(rule, 0);
            }
        }
        notifyService();
        rebuildItems();
    }

    private void showPauseOrDisable(List<List<FilterRule>> rows) {
        PauseOrDisableDialog.show(context, config,
                () -> pauseRows(rows),
                () -> setRowsEnabled(rows, false),
                this::rebuildItems);
    }

    private void pauseRows(List<List<FilterRule>> rows) {
        List<FilterRule> rules = new ArrayList<>();
        for (List<FilterRule> row : rows) rules.addAll(row);
        PauseManager.applyRulePauses(context, rules);
        rebuildItems();
    }

    private void reloadNavigationState() {
        for (FilterRule stored : config.getNavigationRules()) {
            if (!packageName.equals(stored.packageName)) continue;
            for (FilterRule current : currentRules) {
                if (current.isNavigation && current.identity().equals(stored.identity())) {
                    current.enabled = stored.enabled;
                }
            }
        }
        notifyDataSetChanged();
    }

    private boolean hasEnabledNavigationRule() {
        for (FilterRule rule : currentRules) {
            if (rule.isNavigation && rule.enabled) return true;
        }
        return false;
    }

    private String displayName(FilterRule rule, boolean removeHidePrefix) {
        if (rule.description != null && !rule.description.trim().isEmpty()) {
            String description = rule.description.trim();
            if (removeHidePrefix && description.startsWith("Hide ")) {
                return description.substring("Hide ".length());
            }
            return description;
        }
        return context.getString(rule.isCustom
                ? R.string.rule_custom_fallback : R.string.rule_builtin_fallback);
    }

    private String expansionKey(String title) {
        return "rule_group_expanded_" + packageName + "_" + title;
    }

    private void notifyService() {
        DistractionControlService service = DistractionControlService.getInstance();
        if (service != null) service.updateRules();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static final class LabelItem {
        final String text;
        LabelItem(String text) { this.text = text; }
    }

    private static final class NavigationItem {
        final FilterRule rule;
        NavigationItem(FilterRule rule) { this.rule = rule; }
    }

    private static final class GroupItem {
        final String title;
        final List<List<FilterRule>> rows;
        final boolean expanded;
        GroupItem(String title, List<List<FilterRule>> rows, boolean expanded) {
            this.title = title;
            this.rows = rows;
            this.expanded = expanded;
        }
    }

    private static final class RuleItem {
        final List<FilterRule> parts;
        RuleItem(List<FilterRule> parts) { this.parts = parts; }
    }

    private static final class LabelHolder extends RecyclerView.ViewHolder {
        final TextView label;
        LabelHolder(View view) {
            super(view);
            label = view.findViewById(R.id.custom_rule_group_title);
        }
    }

    private static final class NavigationHolder extends RecyclerView.ViewHolder {
        final RadioButton radio;
        final TextView label;
        NavigationHolder(View view) {
            super(view);
            radio = view.findViewById(R.id.navigation_radio);
            label = view.findViewById(R.id.navigation_label);
        }
    }

    private static final class GroupHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView count;
        final TextView indicator;
        final MaterialSwitch switchView;
        GroupHolder(View view) {
            super(view);
            title = view.findViewById(R.id.rule_section_title);
            count = view.findViewById(R.id.rule_section_count);
            indicator = view.findViewById(R.id.rule_section_indicator);
            switchView = view.findViewById(R.id.rule_section_switch);
        }
    }

    private static final class RuleHolder extends RecyclerView.ViewHolder {
        final TextView description;
        final TextView details;
        final MaterialSwitch switchView;
        RuleHolder(View view) {
            super(view);
            description = view.findViewById(R.id.rule_description);
            details = view.findViewById(R.id.rule_details);
            switchView = view.findViewById(R.id.rule_switch);
        }
    }
}
