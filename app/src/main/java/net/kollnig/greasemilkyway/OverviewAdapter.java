package net.kollnig.greasemilkyway;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import net.kollnig.distractionlib.FilterRule;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The overview intentionally contains only app-level state; individual rules live in detail. */
final class OverviewAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int STATUS = 1;
    private static final int APP = 2;
    private static final int FOOTER = 3;
    private static final int NOT_INSTALLED = 4;

    private final Context context;
    private final ServiceConfig config;
    private final Runnable enableService;
    private final List<Object> items = new ArrayList<>();
    private List<FilterRule> allRules = new ArrayList<>();
    private boolean serviceEnabled;
    private boolean missingExpanded;

    OverviewAdapter(Context context, ServiceConfig config, Runnable enableService) {
        this.context = context;
        this.config = config;
        this.enableService = enableService;
    }

    void setRules(List<FilterRule> rules, boolean serviceEnabled) {
        this.allRules = rules;
        this.serviceEnabled = serviceEnabled;
        items.clear();

        Map<String, List<FilterRule>> byPackage = new LinkedHashMap<>();
        for (FilterRule rule : rules) {
            byPackage.computeIfAbsent(rule.packageName, ignored -> new ArrayList<>()).add(rule);
        }

        int activeRows = 0;
        int activeApps = 0;
        int pausedApps = 0;
        List<String> activePackages = new ArrayList<>();
        List<AppItem> installed = new ArrayList<>();
        List<AppItem> missing = new ArrayList<>();
        for (Map.Entry<String, List<FilterRule>> entry : byPackage.entrySet()) {
            AppItem item = new AppItem(entry.getKey(), entry.getValue());
            boolean isInstalled = AppCatalog.isInstalled(context, item.packageName);
            if (isInstalled && item.pausedUntil > System.currentTimeMillis()) {
                pausedApps++;
            }
            int activeActions = item.activeRows + (item.destination.isEmpty() ? 0 : 1);
            if (isInstalled && activeActions > 0 && !config.isPackageDisabled(item.packageName)
                    && item.pausedUntil <= System.currentTimeMillis()) {
                activeRows += activeActions;
                activeApps++;
                activePackages.add(item.packageName);
            }
            (isInstalled ? installed : missing).add(item);
        }

        items.add(new StatusItem(serviceEnabled, activeRows, activeApps, pausedApps, activePackages));
        items.addAll(installed);
        if (!missing.isEmpty()) {
            items.add(new MissingItem(missing));
            if (missingExpanded) {
                items.addAll(missing);
            }
        }
        items.add(new FooterItem());
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        Object item = items.get(position);
        if (item instanceof StatusItem) return STATUS;
        if (item instanceof AppItem) return APP;
        if (item instanceof MissingItem) return NOT_INSTALLED;
        return FOOTER;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (type == STATUS) {
            return new StatusHolder(inflater.inflate(R.layout.item_status_card, parent, false));
        }
        if (type == APP || type == NOT_INSTALLED) {
            return new AppHolder(inflater.inflate(R.layout.item_app_group, parent, false));
        }
        return new FooterHolder(inflater.inflate(R.layout.item_footer, parent, false));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object model = items.get(position);
        if (holder instanceof StatusHolder) {
            bindStatus((StatusHolder) holder, (StatusItem) model);
        } else if (holder instanceof AppHolder && model instanceof AppItem) {
            AppItem app = (AppItem) model;
            bindApp((AppHolder) holder, app, !AppCatalog.isInstalled(context, app.packageName));
        } else if (holder instanceof AppHolder) {
            bindMissingHeader((AppHolder) holder, (MissingItem) model);
        } else if (holder instanceof FooterHolder) {
            FooterHolder footer = (FooterHolder) holder;
            footer.footer.setText(R.string.footer_branding);
            footer.footer.setVisibility(context.getResources().getBoolean(R.bool.show_footer_branding)
                    ? View.VISIBLE : View.GONE);
            footer.recruitment.setText(R.string.recruitment_message);
        }
    }

    private void bindStatus(StatusHolder holder, StatusItem status) {
        holder.title.setText(status.serviceEnabled
                ? R.string.focus_status_on : R.string.focus_status_off);
        holder.details.setText(status.serviceEnabled
                ? context.getString(R.string.focus_status_active, status.activeRows,
                        status.activeApps, status.pausedApps)
                : context.getString(R.string.focus_status_enable_hint));
        holder.action.setVisibility(status.serviceEnabled && !status.activePackages.isEmpty()
                ? View.VISIBLE : View.GONE);
        holder.action.setOnClickListener(view -> {
            if (context instanceof FrictionGateHost) {
                ((FrictionGateHost) context).runWithFrictionGate(
                        context.getString(R.string.pause_all_title), () -> {
                            PauseManager.applyPackagePauses(context, status.activePackages);
                            reload();
                        });
            }
        });
        holder.itemView.setOnClickListener(status.serviceEnabled ? null : view -> enableService.run());
    }

    private void bindMissingHeader(AppHolder holder, MissingItem missing) {
        holder.name.setText(context.getString(R.string.not_installed_group, missing.apps.size()));
        holder.subtitle.setText(missingExpanded
                ? R.string.not_installed_collapse : R.string.not_installed_expand);
        holder.subtitle.setTextColor(ContextCompat.getColor(context, R.color.text_light));
        holder.icon.setImageResource(android.R.drawable.sym_def_app_icon);
        holder.switchView.setVisibility(View.GONE);
        holder.chevron.setVisibility(View.VISIBLE);
        holder.chevron.setText(missingExpanded
                ? R.string.chevron_expanded : R.string.chevron_collapsed);
        holder.itemView.setOnClickListener(view -> {
            missingExpanded = !missingExpanded;
            reload();
        });
    }

    private void bindApp(AppHolder holder, AppItem item, boolean missing) {
        holder.name.setText(AppCatalog.getDisplayName(context, item.packageName));
        holder.icon.setImageDrawable(AppCatalog.getIcon(context, item.packageName));
        if (missing) {
            holder.subtitle.setText(R.string.not_installed);
            holder.subtitle.setTextColor(ContextCompat.getColor(context, R.color.text_light));
            holder.switchView.setVisibility(View.GONE);
            holder.chevron.setVisibility(View.INVISIBLE);
            holder.itemView.setOnClickListener(null);
            return;
        }

        boolean paused = item.pausedUntil > System.currentTimeMillis();
        boolean enabled = !config.isPackageDisabled(item.packageName) && !paused;
        if (paused) {
            holder.subtitle.setText(context.getString(R.string.app_paused_resumes,
                    formatTime(item.pausedUntil)));
            holder.subtitle.setTextColor(ContextCompat.getColor(context, R.color.state_paused));
        } else if (enabled) {
            holder.subtitle.setText(item.destination.isEmpty()
                    ? context.getResources().getQuantityString(
                            R.plurals.hides_elements, item.activeRows, item.activeRows)
                    : context.getString(R.string.app_hidden_and_opens,
                            item.activeRows, item.destination));
            holder.subtitle.setTextColor(ContextCompat.getColor(context, R.color.accent_green));
        } else {
            holder.subtitle.setText(R.string.click_to_hide_elements);
            holder.subtitle.setTextColor(ContextCompat.getColor(context, R.color.text_light));
        }

        holder.switchView.setVisibility(View.VISIBLE);
        holder.chevron.setVisibility(View.VISIBLE);
        holder.chevron.setText(R.string.chevron_collapsed);
        holder.switchView.setOnCheckedChangeListener(null);
        holder.switchView.setChecked(enabled);
        holder.switchView.setContentDescription(context.getString(enabled
                ? R.string.disable_all_rules_for_app : R.string.enable_all_rules_for_app));
        holder.switchView.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                config.enablePackageRules(item.packageName, item.rules);
                notifyService();
                reload();
                return;
            }
            holder.switchView.setOnCheckedChangeListener(null);
            holder.switchView.setChecked(true);
            if (context instanceof FrictionGateHost) {
                ((FrictionGateHost) context).runWithFrictionGate(
                        context.getString(R.string.disable_app_title, holder.name.getText()),
                        () -> showPauseOrDisable(item));
            }
        });
        holder.itemView.setOnClickListener(view -> context.startActivity(
                new Intent(context, AppDetailActivity.class)
                        .putExtra(AppDetailActivity.EXTRA_PACKAGE_NAME, item.packageName)));
    }

    private void showPauseOrDisable(AppItem item) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.pause_or_disable_title)
                .setMessage(R.string.pause_or_disable_message)
                .setPositiveButton(R.string.pause_default_action, (dialog, which) -> {
                    PauseManager.applyPackagePause(context, item.packageName);
                    reload();
                })
                .setNegativeButton(R.string.disable_permanently_action, (dialog, which) -> {
                    config.setPackageDisabled(item.packageName, true);
                    config.setPackagePausedUntil(item.packageName, 0);
                    notifyService();
                    reload();
                })
                .setOnCancelListener(dialog -> reload())
                .show();
    }

    private void reload() {
        List<FilterRule> rules = new ArrayList<>(config.getRules());
        rules.addAll(config.getNavigationRules());
        setRules(rules, serviceEnabled);
    }

    private void notifyService() {
        DistractionControlService service = DistractionControlService.getInstance();
        if (service != null) {
            service.updateRules();
        }
    }

    private String formatTime(long time) {
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(time));
    }

    private static final class StatusItem {
        final boolean serviceEnabled;
        final int activeRows;
        final int activeApps;
        final int pausedApps;
        final List<String> activePackages;

        StatusItem(boolean serviceEnabled, int activeRows, int activeApps, int pausedApps,
                   List<String> activePackages) {
            this.serviceEnabled = serviceEnabled;
            this.activeRows = activeRows;
            this.activeApps = activeApps;
            this.pausedApps = pausedApps;
            this.activePackages = activePackages;
        }
    }

    private static final class AppItem {
        final String packageName;
        final List<FilterRule> rules;
        final int activeRows;
        final long pausedUntil;
        final String destination;

        AppItem(String packageName, List<FilterRule> rules) {
            this.packageName = packageName;
            this.rules = rules;
            int active = 0;
            String navigationDestination = "";
            for (List<FilterRule> row : RuleRows.mergeRules(rules)) {
                if (RuleRows.isNavigationRow(row)) {
                    if (RuleRows.isRowEnabled(row)) {
                        navigationDestination = RuleRows.compactNavigationDestination(row.get(0));
                    }
                } else if (RuleRows.isRowEnabled(row)) {
                    active++;
                }
            }
            activeRows = active;
            destination = navigationDestination;
            long pause = 0;
            for (FilterRule rule : rules) {
                pause = Math.max(pause, rule.pausedUntil);
            }
            pausedUntil = pause;
        }
    }

    private static final class MissingItem {
        final List<AppItem> apps;

        MissingItem(List<AppItem> apps) {
            this.apps = apps;
        }
    }

    private static final class FooterItem {
    }

    static final class StatusHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView details;
        final MaterialButton action;

        StatusHolder(View view) {
            super(view);
            title = view.findViewById(R.id.status_title);
            details = view.findViewById(R.id.status_details);
            action = view.findViewById(R.id.status_action);
        }
    }

    static final class AppHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView subtitle;
        final TextView chevron;
        final MaterialSwitch switchView;

        AppHolder(View view) {
            super(view);
            icon = view.findViewById(R.id.app_icon);
            name = view.findViewById(R.id.app_name);
            subtitle = view.findViewById(R.id.package_name);
            chevron = view.findViewById(R.id.app_chevron);
            switchView = view.findViewById(R.id.package_switch);
        }
    }

    static final class FooterHolder extends RecyclerView.ViewHolder {
        final TextView footer;
        final TextView recruitment;

        FooterHolder(View view) {
            super(view);
            footer = view.findViewById(R.id.footer_text);
            recruitment = view.findViewById(R.id.recruitment_text);
        }
    }
}
