package net.kollnig.greasemilkyway;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

final class CustomRulesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    interface Listener {
        void onToggle(List<FilterRule> row, boolean enabled);
        void onEdit(List<FilterRule> row);
    }

    private static final int TYPE_GROUP = 0;
    private static final int TYPE_RULE = 1;

    private final Context context;
    private final ServiceConfig config;
    private final Listener listener;
    private final List<Object> items = new ArrayList<>();

    CustomRulesAdapter(Context context, ServiceConfig config, Listener listener) {
        this.context = context;
        this.config = config;
        this.listener = listener;
    }

    void setRules(Map<String, List<List<FilterRule>>> rowsByPackage) {
        items.clear();
        for (Map.Entry<String, List<List<FilterRule>>> entry : rowsByPackage.entrySet()) {
            items.add(new GroupItem(entry.getKey()));
            for (List<FilterRule> row : entry.getValue()) {
                items.add(new RuleItem(row));
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof GroupItem ? TYPE_GROUP : TYPE_RULE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_GROUP) {
            return new GroupHolder(inflater.inflate(R.layout.item_custom_rule_group, parent, false));
        }
        return new RuleHolder(inflater.inflate(R.layout.item_custom_rule, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = items.get(position);
        if (holder instanceof GroupHolder && item instanceof GroupItem) {
            GroupItem group = (GroupItem) item;
            ((GroupHolder) holder).title.setText(
                    AppCatalog.getDisplayName(context, group.packageName));
            return;
        }

        RuleHolder ruleHolder = (RuleHolder) holder;
        List<FilterRule> row = ((RuleItem) item).parts;
        FilterRule primary = row.get(0);
        String name = primary.description == null || primary.description.trim().isEmpty()
                ? context.getString(R.string.rule_custom_fallback)
                : primary.description.trim();
        boolean appOff = config.isPackageDisabled(primary.packageName);
        long pausedUntil = 0;
        for (FilterRule rule : row) {
            if (rule.isPaused) pausedUntil = Math.max(pausedUntil, rule.pausedUntil);
        }

        ruleHolder.name.setText(name);
        if (appOff) {
            ruleHolder.subtitle.setText(R.string.custom_rule_app_off);
            ruleHolder.subtitle.setVisibility(View.VISIBLE);
        } else if (pausedUntil > System.currentTimeMillis()) {
            ruleHolder.subtitle.setText(context.getString(R.string.app_paused_resumes,
                    DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(new Date(pausedUntil))));
            ruleHolder.subtitle.setVisibility(View.VISIBLE);
        } else if (row.size() > 1) {
            ruleHolder.subtitle.setText(context.getResources().getQuantityString(
                    R.plurals.custom_rule_multiple_parts, row.size(), row.size()));
            ruleHolder.subtitle.setVisibility(View.VISIBLE);
        } else {
            ruleHolder.subtitle.setVisibility(View.GONE);
        }

        ruleHolder.toggle.setOnCheckedChangeListener(null);
        ruleHolder.toggle.setChecked(RuleRows.isRowEnabled(row));
        ruleHolder.toggle.setEnabled(!appOff);
        ruleHolder.toggle.setContentDescription(name);
        ruleHolder.toggle.setOnCheckedChangeListener((button, checked) ->
                listener.onToggle(row, checked));
        ruleHolder.itemView.setContentDescription(name);
        ruleHolder.itemView.setOnClickListener(v -> listener.onEdit(row));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static final class GroupItem {
        final String packageName;
        GroupItem(String packageName) { this.packageName = packageName; }
    }

    private static final class RuleItem {
        final List<FilterRule> parts;
        RuleItem(List<FilterRule> parts) { this.parts = parts; }
    }

    private static final class GroupHolder extends RecyclerView.ViewHolder {
        final TextView title;
        GroupHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.custom_rule_group_title);
        }
    }

    private static final class RuleHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView subtitle;
        final MaterialSwitch toggle;
        RuleHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.custom_rule_name);
            subtitle = itemView.findViewById(R.id.custom_rule_subtitle);
            toggle = itemView.findViewById(R.id.custom_rule_switch);
        }
    }
}
