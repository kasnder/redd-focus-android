package net.kollnig.greasemilkyway;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import com.google.android.material.materialswitch.MaterialSwitch;
import android.widget.TextView;
import android.app.AlertDialog;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import net.kollnig.distractionlib.FilterRule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import android.widget.Toast;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;

public class RulesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_APP_HEADER = 1;
    private static final int TYPE_RULE = 2;
    private static final int TYPE_FOOTER = 3;

    private static final String PREFS_NAME = "AppCollapseStates";
    private static final String KEY_FIRST_RUN = "first_run";

    private final Context context;
    private final ServiceConfig config;
    private final PackageManager packageManager;
    private final List<Object> items = new ArrayList<>();
    private List<FilterRule> currentRules = new ArrayList<>();

    // Hardcoded app names for known packages
    private static final Map<String, String> KNOWN_APP_NAMES = new HashMap<>() {
        {
            put("com.whatsapp", "WhatsApp");
            put("com.google.android.youtube", "YouTube");
            put("com.instagram.android", "Instagram");
            put("com.linkedin.android", "LinkedIn");
        }
    };

    // Hardcoded app icons for known packages
    private static final Map<String, Integer> KNOWN_APP_ICONS = new HashMap<>() {
        {
            put("com.whatsapp", R.drawable.ic_whatsapp);
            put("com.google.android.youtube", R.drawable.ic_youtube);
            put("com.instagram.android", R.drawable.ic_instagram);
            put("com.linkedin.android", R.drawable.ic_linkedin);
        }
    };

    public RulesAdapter(Context context, ServiceConfig config) {
        this.context = context;
        this.config = config;
        this.packageManager = context.getPackageManager();
        SharedPreferences collapsePrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Check if this is first run
        if (collapsePrefs.getBoolean(KEY_FIRST_RUN, true)) {
            // First run - mark as no longer first run
            collapsePrefs.edit().putBoolean(KEY_FIRST_RUN, false).apply();
            // All apps will default to collapsed (false) on first run
        }
    }

    public void setRules(List<FilterRule> rules) {
        this.currentRules = rules;
        rebuildItemsList();
    }

    private void rebuildItemsList() {
        // Preserve full rule state (enabled, paused, pausedUntil) from existing items
        // so that external setRules() calls don't overwrite local changes
        Map<Integer, FilterRule> existingRules = new HashMap<>();
        for (Object item : items) {
            if (item instanceof RuleItem) {
                FilterRule rule = ((RuleItem) item).rule;
                existingRules.put(rule.hashCode(), rule);
            }
        }

        this.items.clear();

        // Group rules by package name
        Map<String, List<FilterRule>> rulesByPackage = new HashMap<>();
        for (FilterRule rule : currentRules) {
            // Preserve state from existing rules
            FilterRule existing = existingRules.get(rule.hashCode());
            if (existing != null) {
                rule.enabled = existing.enabled;
                rule.isPaused = existing.isPaused;
                rule.pausedUntil = existing.pausedUntil;
            }
            rulesByPackage.computeIfAbsent(rule.packageName, k -> new ArrayList<>()).add(rule);
        }

        // Add items in order: app header followed by its rules (if expanded)
        for (Map.Entry<String, List<FilterRule>> entry : rulesByPackage.entrySet()) {
            String packageName = entry.getKey();
            List<FilterRule> packageRules = entry.getValue();

            // Sort rules by description
            packageRules.sort((r1, r2) -> {
                String d1 = r1.description != null ? r1.description : "";
                String d2 = r2.description != null ? r2.description : "";
                return d1.compareToIgnoreCase(d2);
            });

            // Count only enabled rules
            int enabledCount = 0;
            for (FilterRule rule : packageRules) {
                if (rule.enabled) enabledCount++;
            }

            // Add app header
            items.add(new AppHeaderItem(packageName, enabledCount));

            // Show rules only when the package is enabled (not disabled)
            boolean isPackageEnabled = !config.isPackageDisabled(packageName);
            if (isPackageEnabled) {
                for (FilterRule rule : packageRules) {
                    items.add(new RuleItem(rule));
                }
            }
        }

        // Add footer at end of list
        items.add(new FooterItem());

        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        Object item = items.get(position);
        if (item instanceof AppHeaderItem)
            return TYPE_APP_HEADER;
        if (item instanceof FooterItem)
            return TYPE_FOOTER;
        return TYPE_RULE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_APP_HEADER) {
            return new AppHeaderViewHolder(inflater.inflate(R.layout.item_app_group, parent, false));
        }
        if (viewType == TYPE_FOOTER) {
            return new FooterViewHolder(inflater.inflate(R.layout.item_footer, parent, false));
        }
        return new RuleViewHolder(inflater.inflate(R.layout.item_rule, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = items.get(position);
        if (holder instanceof AppHeaderViewHolder && item instanceof AppHeaderItem) {
            AppHeaderViewHolder viewHolder = (AppHeaderViewHolder) holder;
            AppHeaderItem appItem = (AppHeaderItem) item;
            String packageName = appItem.packageName;

            // Check if this is a known app
            String displayName = KNOWN_APP_NAMES.get(packageName);
            Integer iconRes = KNOWN_APP_ICONS.get(packageName);

            // Try to get app info to check if installed
            boolean isInstalled = false;
            try {
                packageManager.getApplicationInfo(packageName, 0);
                isInstalled = true;
            } catch (PackageManager.NameNotFoundException e) {
                // do nothing
            }
            final boolean finalIsInstalled = isInstalled;

            // Determine if the app toggle is currently enabled
            boolean isAppEnabled = !config.isPackageDisabled(packageName);

            // Set subtitle text based on state
            if (!finalIsInstalled) {
                viewHolder.packageName.setText(context.getString(R.string.not_installed));
            } else if (isAppEnabled) {
                if (appItem.ruleCount > 0) {
                    viewHolder.packageName.setText(context.getResources().getQuantityString(
                            R.plurals.hides_elements, appItem.ruleCount, appItem.ruleCount));
                } else {
                    viewHolder.packageName.setText(context.getString(R.string.click_to_hide_elements));
                }
            } else {
                long pauseUntil = config.getPackagePausedUntil(packageName);
                if (pauseUntil > System.currentTimeMillis()) {
                    SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
                    String timeStr = sdf.format(new Date(pauseUntil));
                    viewHolder.packageName.setText(context.getString(R.string.paused_until, timeStr));
                } else {
                    viewHolder.packageName.setText(context.getString(R.string.click_to_hide_elements));
                }
            }

            // Set name and icon
            if (displayName != null && iconRes != null) {
                viewHolder.appName.setText(displayName);
                viewHolder.appIcon.setImageResource(iconRes);
            } else {
                try {
                    ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
                    viewHolder.appName.setText(packageManager.getApplicationLabel(appInfo));
                    viewHolder.appIcon.setImageDrawable(packageManager.getApplicationIcon(appInfo));
                } catch (PackageManager.NameNotFoundException e) {
                    viewHolder.appName.setText(packageName);
                    viewHolder.appIcon.setImageResource(android.R.drawable.sym_def_app_icon);
                }
            }

            // Gray out icon if not installed
            if (!finalIsInstalled) {
                ColorMatrix matrix = new ColorMatrix();
                matrix.setSaturation(0);
                viewHolder.appIcon.setColorFilter(new ColorMatrixColorFilter(matrix));
                viewHolder.appIcon.setAlpha(0.5f);
            } else {
                viewHolder.appIcon.clearColorFilter();
                viewHolder.appIcon.setAlpha(1.0f);
            }

            // Set up package switch
            viewHolder.packageSwitch.setOnCheckedChangeListener(null);
            viewHolder.packageSwitch.setChecked(isAppEnabled);
            viewHolder.packageSwitch.setOnClickListener(v -> {
                if (!finalIsInstalled) {
                    viewHolder.packageSwitch.setChecked(isAppEnabled);
                    Toast.makeText(context, R.string.app_not_installed, Toast.LENGTH_SHORT).show();
                }
            });
            viewHolder.packageSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!finalIsInstalled) return;

                if (!isChecked) {
                    // Intercept disabling
                    viewHolder.packageSwitch.setOnCheckedChangeListener(null);
                    viewHolder.packageSwitch.setChecked(true); // Revert visually
                    
                    if (context instanceof MainActivity) {
                        ((MainActivity) context).runWithFrictionGate("Disable " + viewHolder.appName.getText(), () -> {
                            showPauseDialog(packageName, null);
                        });
                    }
                    viewHolder.packageSwitch.setOnCheckedChangeListener(((buttonView1, isChecked1) -> { /* Re-register will happen in rebuild */ }));
                    return;
                }

                config.setPackageDisabled(packageName, false);
                config.setPackagePausedUntil(packageName, 0);

                // Restore each rule's saved enabled state from SharedPreferences.
                // In-memory state was set to false for UI during disable, but prefs were
                // intentionally preserved, so this correctly restores individual selections.
                // If no rule has ever been explicitly saved (first-time enable), enable all.
                boolean anyRuleSavedEnabled = false;
                for (FilterRule rule : currentRules) {
                    if (rule.packageName.equals(packageName) && config.isRuleEnabled(rule)) {
                        anyRuleSavedEnabled = true;
                        break;
                    }
                }
                for (FilterRule rule : currentRules) {
                    if (rule.packageName.equals(packageName)) {
                        rule.isPaused = false;
                        rule.pausedUntil = 0;
                        if (anyRuleSavedEnabled) {
                            // Restore the individually saved state
                            rule.enabled = config.isRuleEnabled(rule);
                        } else {
                            // First-time enable: turn everything on
                            rule.enabled = true;
                            config.setRuleEnabled(rule, true);
                            config.setRulePausedUntil(rule, 0);
                        }
                    }
                }

                // Rebuild to show/hide rules
                rebuildItemsList();

                // Notify the service to update its rules
                notifyService();
            });

            // Whole-row click toggles the switch (when installed)
            viewHolder.itemView.setOnClickListener(v -> {
                if (!finalIsInstalled) {
                    Toast.makeText(context, R.string.app_not_installed, Toast.LENGTH_SHORT).show();
                    return;
                }
                viewHolder.packageSwitch.toggle();
            });
        } else if (holder instanceof RuleViewHolder && item instanceof RuleItem) {
            RuleViewHolder viewHolder = (RuleViewHolder) holder;
            RuleItem ruleItem = (RuleItem) item;
            FilterRule rule = ruleItem.rule;

            viewHolder.ruleDescription.setText(rule.description);

            // Set subtitle text based on state
            if (rule.enabled) {
                viewHolder.ruleDetails.setVisibility(View.GONE);
            } else if (rule.isPaused && rule.pausedUntil > System.currentTimeMillis()) {
                SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
                String timeStr = sdf.format(new Date(rule.pausedUntil));
                viewHolder.ruleDetails.setText(context.getString(R.string.paused_until, timeStr));
                viewHolder.ruleDetails.setVisibility(View.VISIBLE);
            } else if (!rule.enabled) {
                viewHolder.ruleDetails.setText(R.string.rule_disabled);
                viewHolder.ruleDetails.setVisibility(View.VISIBLE);
            } else {
                viewHolder.ruleDetails.setVisibility(View.GONE);
            }

            // Check if the package is disabled
            boolean isPackageDisabled = config.isPackageDisabled(rule.packageName);

            // Remove any existing listener to prevent duplicate callbacks
            viewHolder.ruleSwitch.setOnCheckedChangeListener(null);
            // Set the current state
            viewHolder.ruleSwitch.setChecked(rule.enabled);
            // Disable the switch if the package is disabled
            viewHolder.ruleSwitch.setEnabled(!isPackageDisabled);
            // Add the listener back
            viewHolder.ruleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                int adapterPosition = viewHolder.getAdapterPosition();
                if (adapterPosition == RecyclerView.NO_POSITION)
                    return;
                Object currentItem = items.get(adapterPosition);
                if (currentItem instanceof RuleItem) {
                    RuleItem currentRuleItem = (RuleItem) currentItem;
                    FilterRule currentRule = currentRuleItem.rule;
                    if (currentRule.enabled != isChecked) { // Only update if the state actually changed
                        if (!isChecked) {
                            // Intercept disabling
                            viewHolder.ruleSwitch.setOnCheckedChangeListener(null);
                            viewHolder.ruleSwitch.setChecked(true); // Revert visually
                            
                            if (context instanceof MainActivity) {
                                ((MainActivity) context).runWithFrictionGate("Disable Rule", () -> showPauseDialog(currentRule.packageName, currentRule));
                            }
                            return;
                        }

                        currentRule.enabled = true;
                        currentRule.isPaused = false;
                        config.setRuleEnabled(currentRule, true);
                        config.setRulePausedUntil(currentRule, 0);

                        // Rebuild to update the package switch UI, showing rules, and updated counts
                        rebuildItemsList();

                        // Notify the service to update its rules
                        notifyService();
                    }
                }
            });

            // Set up long click to delete custom rules
            viewHolder.itemView.setOnLongClickListener(v -> {
                if (rule.isCustom) {
                    if (context instanceof MainActivity) {
                        ((MainActivity) context).runWithFrictionGate("Delete Rule", () -> new AlertDialog.Builder(context)
                                .setTitle(R.string.delete_rule_title)
                                .setMessage(R.string.delete_rule_message)
                                .setPositiveButton(R.string.delete_rule_confirm, (dialog, which) -> {
                                    config.removeCustomRule(rule.ruleString);

                                    // Clean up the current rules list
                                    currentRules.remove(rule);

                                    // Check if we need to disable the package if it was the last rule
                                    boolean anyRulesStillEnabled = false;
                                    for (FilterRule r : currentRules) {
                                        if (r.packageName.equals(rule.packageName) && r.enabled) {
                                            anyRulesStillEnabled = true;
                                            break;
                                        }
                                    }
                                    if (!anyRulesStillEnabled) {
                                        config.setPackageDisabled(rule.packageName, true);
                                    }

                                    rebuildItemsList();
                                    notifyService();

                                    Toast.makeText(context, R.string.rule_deleted, Toast.LENGTH_SHORT).show();
                                })
                                .setNegativeButton(R.string.delete_rule_cancel, null)
                                .show());
                    }
                    return true;
                } else {
                    Toast.makeText(context, R.string.builtin_rule_no_delete, Toast.LENGTH_SHORT).show();
                    return true; // Consume the long click anyway to show the feedback
                }
            });
        } else if (holder instanceof FooterViewHolder) {
            FooterViewHolder viewHolder = (FooterViewHolder) holder;

            if (context.getResources().getBoolean(R.bool.show_footer_branding)) {
                // "Made with ❤️ by reddfocus.org" with clickable link
                String fullText = "Made with ❤️ by reddfocus.org";
                SpannableString spannableString = new SpannableString(fullText);
                int start = fullText.indexOf("reddfocus.org");
                int end = start + "reddfocus.org".length();
                ClickableSpan clickableSpan = new ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://reddfocus.org"));
                        context.startActivity(browserIntent);
                    }
                    @Override
                    public void updateDrawState(android.text.TextPaint ds) {
                        ds.setUnderlineText(false);
                        ds.setColor(viewHolder.footerText.getCurrentTextColor());
                    }
                };
                spannableString.setSpan(clickableSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                viewHolder.footerText.setText(spannableString);
                viewHolder.footerText.setMovementMethod(LinkMovementMethod.getInstance());
                viewHolder.footerText.setHighlightColor(android.graphics.Color.TRANSPARENT);
            } else {
                viewHolder.footerText.setVisibility(View.GONE);
            }

            // Recruitment message with clickable email (always shown)
            String recruitmentFull = context.getString(R.string.recruitment_message);
            String email = "konrad.kollnig@maastrichtuniversity.nl";
            SpannableString recruitmentSpannable = new SpannableString(recruitmentFull);
            int emailStart = recruitmentFull.indexOf(email);
            int emailEnd = emailStart + email.length();
            ClickableSpan emailSpan = new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    Intent emailIntent = new Intent(Intent.ACTION_SENDTO);
                    emailIntent.setData(Uri.parse("mailto:" + email));
                    context.startActivity(emailIntent);
                }
                @Override
                public void updateDrawState(android.text.TextPaint ds) {
                    ds.setUnderlineText(true);
                    ds.setColor(viewHolder.recruitmentText.getCurrentTextColor());
                }
            };
            recruitmentSpannable.setSpan(emailSpan, emailStart, emailEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            viewHolder.recruitmentText.setText(recruitmentSpannable);
            viewHolder.recruitmentText.setMovementMethod(LinkMovementMethod.getInstance());
            viewHolder.recruitmentText.setHighlightColor(android.graphics.Color.TRANSPARENT);
        }
    }

    private void showPauseDialog(String packageName, FilterRule rule) {
        int durationMins = config.getPauseDurationMins();
        String message = "Do you want to pause for " + durationMins + " minutes or disable permanently?";
        
        new AlertDialog.Builder(context)
                .setTitle("Disable Rule")
                .setMessage(message)
                .setPositiveButton("Pause (" + durationMins + "m)", (dialog, which) -> {
                    long until = System.currentTimeMillis() + (durationMins * 60 * 1000L);
                    if (rule != null) {
                        config.setRuleEnabled(rule, false);
                        config.setRulePausedUntil(rule, until);
                        rule.enabled = false;
                        rule.isPaused = true;
                        rule.pausedUntil = until;
                    } else {
                        config.setPackageDisabled(packageName, true);
                        config.setPackagePausedUntil(packageName, until);
                        // Update in-memory state for UI only; individual rule prefs are
                        // intentionally left untouched so they can be restored on re-enable.
                        for (FilterRule r : currentRules) {
                            if (r.packageName.equals(packageName)) {
                                r.enabled = false;
                                r.isPaused = true;
                                r.pausedUntil = until;
                            }
                        }
                    }
                    rebuildItemsList();
                    notifyService();
                })
                .setNeutralButton("Disable Permanently", (dialog, which) -> {
                    if (rule != null) {
                        config.setRuleEnabled(rule, false);
                        config.setRulePausedUntil(rule, 0);
                        rule.enabled = false;
                        rule.isPaused = false;
                        rule.pausedUntil = 0;
                    } else {
                        config.setPackageDisabled(packageName, true);
                        config.setPackagePausedUntil(packageName, 0);
                        // Update in-memory state for UI only; individual rule prefs are
                        // intentionally left untouched so they can be restored on re-enable.
                        for (FilterRule r : currentRules) {
                            if (r.packageName.equals(packageName)) {
                                r.enabled = false;
                                r.isPaused = false;
                                r.pausedUntil = 0;
                            }
                        }
                    }
                    rebuildItemsList();
                    notifyService();
                })
                .setNegativeButton("Cancel", (dialog, which) -> rebuildItemsList())
                .setOnCancelListener(dialog -> rebuildItemsList())
                .show();
    }

    private void notifyService() {
        DistractionControlService service = DistractionControlService.getInstance();
        if (service != null) {
            service.updateRules();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // Item classes for different view types
    private static class AppHeaderItem {
        final String packageName;
        final int ruleCount;

        AppHeaderItem(String packageName, int ruleCount) {
            this.packageName = packageName;
            this.ruleCount = ruleCount;
        }
    }

    private static class RuleItem {
        final FilterRule rule;

        RuleItem(FilterRule rule) {
            this.rule = rule;
        }
    }

    private static class FooterItem {
    }

    static class AppHeaderViewHolder extends RecyclerView.ViewHolder {
        TextView appName;
        TextView packageName;
        ImageView appIcon;
        MaterialSwitch packageSwitch;

        AppHeaderViewHolder(View itemView) {
            super(itemView);
            appName = itemView.findViewById(R.id.app_name);
            packageName = itemView.findViewById(R.id.package_name);
            appIcon = itemView.findViewById(R.id.app_icon);
            packageSwitch = itemView.findViewById(R.id.package_switch);
        }
    }

    static class FooterViewHolder extends RecyclerView.ViewHolder {
        final TextView footerText;
        final TextView recruitmentText;

        FooterViewHolder(View itemView) {
            super(itemView);
            footerText = itemView.findViewById(R.id.footer_text);
            recruitmentText = itemView.findViewById(R.id.recruitment_text);
        }
    }

    public static class RuleViewHolder extends RecyclerView.ViewHolder {
        final TextView ruleDescription;
        final TextView ruleDetails;
        final MaterialSwitch ruleSwitch;
        // Removed position field

        RuleViewHolder(View itemView) {
            super(itemView);
            ruleDescription = itemView.findViewById(R.id.rule_description);
            ruleDetails = itemView.findViewById(R.id.rule_details);
            ruleSwitch = itemView.findViewById(R.id.rule_switch);
        }
    }
}
