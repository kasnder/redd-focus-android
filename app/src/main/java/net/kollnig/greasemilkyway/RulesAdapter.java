package net.kollnig.greasemilkyway;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import com.google.android.material.materialswitch.MaterialSwitch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import android.widget.Toast;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;

public class RulesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_SERVICE_HEADER = 0;
    private static final int TYPE_APP_HEADER = 1;
    private static final int TYPE_RULE = 2;

    private static final String PREFS_NAME = "AppCollapseStates";
    private static final String KEY_FIRST_RUN = "first_run";
    private static final String KEY_APP_EXPANDED = "app_expanded_";

    private final Context context;
    private final ServiceConfig config;
    private final PackageManager packageManager;
    private final List<Object> items = new ArrayList<>();
    private OnRuleStateChangedListener onRuleStateChangedListener;
    private boolean serviceEnabled = false;
    private final Map<String, Boolean> appExpandedStates = new HashMap<>();
    private List<FilterRule> currentRules = new ArrayList<>();
    private final SharedPreferences collapsePrefs;
    private final boolean usesTwoStepFlow;

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
    private static final Map<String, Integer> KNOWN_APP_ICONS = new HashMap<String, Integer>() {
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
        this.collapsePrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.usesTwoStepFlow = detectTwoStepFlow();

        // Check if this is first run
        if (collapsePrefs.getBoolean(KEY_FIRST_RUN, true)) {
            // First run - mark as no longer first run
            collapsePrefs.edit().putBoolean(KEY_FIRST_RUN, false).apply();
            // All apps will default to collapsed (false) on first run
        }
    }

    /**
     * Detects if the device uses a two-step accessibility flow (Samsung/OneUI,
     * Xiaomi, etc.)
     * vs one-step flow (Pixel, stock Android)
     */
    private boolean detectTwoStepFlow() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        // Samsung, Xiaomi, Oppo, Vivo typically use two-step flow
        return manufacturer.contains("samsung") ||
                manufacturer.contains("xiaomi") ||
                manufacturer.contains("oppo") ||
                manufacturer.contains("vivo") ||
                manufacturer.contains("oneplus");
    }

    public void setOnRuleStateChangedListener(OnRuleStateChangedListener listener) {
        this.onRuleStateChangedListener = listener;
    }

    public boolean isAccessibilityServiceEnabled() {
        String serviceName = context.getPackageName() + "/" + DistractionControlService.class.getCanonicalName();
        int accessibilityEnabled = 0;
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            return false;
        }

        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                return settingValue.contains(serviceName);
            }
        }
        return false;
    }

    public void setRules(List<FilterRule> rules) {
        this.currentRules = rules;
        rebuildItemsList();
    }

    private void rebuildItemsList() {
        // Create a map of existing rules by their hash code for state preservation
        Map<Integer, Boolean> existingStates = new HashMap<>();
        for (Object item : items) {
            if (item instanceof RuleItem) {
                FilterRule rule = ((RuleItem) item).rule;
                existingStates.put(rule.hashCode(), rule.enabled);
            }
        }

        this.items.clear();

        // Add service header
        items.add(new ServiceHeaderItem());

        // Group rules by package name
        Map<String, List<FilterRule>> rulesByPackage = new HashMap<>();
        for (FilterRule rule : currentRules) {
            // Preserve the enabled state from existing rules
            Boolean existingState = existingStates.get(rule.hashCode());
            if (existingState != null) {
                rule.enabled = existingState;
            }
            rulesByPackage.computeIfAbsent(rule.packageName, k -> new ArrayList<>()).add(rule);
        }

        // Add items in order: app header followed by its rules (if expanded)
        for (Map.Entry<String, List<FilterRule>> entry : rulesByPackage.entrySet()) {
            String packageName = entry.getKey();
            List<FilterRule> packageRules = entry.getValue();

            // Sort rules by description
            packageRules.sort((r1, r2) -> r1.description.compareToIgnoreCase(r2.description));

            // Add app header
            items.add(new AppHeaderItem(packageName, packageRules.size()));

            // Only add rules if this app is expanded
            // Load from SharedPreferences, default to false (collapsed)
            boolean isExpanded = appExpandedStates.getOrDefault(
                    packageName,
                    collapsePrefs.getBoolean(KEY_APP_EXPANDED + packageName, false));
            if (isExpanded) {
                for (FilterRule rule : packageRules) {
                    items.add(new RuleItem(rule));
                }
            }
        }

        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        Object item = items.get(position);
        if (item instanceof ServiceHeaderItem)
            return TYPE_SERVICE_HEADER;
        if (item instanceof AppHeaderItem)
            return TYPE_APP_HEADER;
        return TYPE_RULE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_SERVICE_HEADER:
                return new ServiceHeaderViewHolder(inflater.inflate(R.layout.item_service_header, parent, false));
            case TYPE_APP_HEADER:
                return new AppHeaderViewHolder(inflater.inflate(R.layout.item_app_group, parent, false));
            default:
                return new RuleViewHolder(inflater.inflate(R.layout.item_rule, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = items.get(position);
        if (holder instanceof ServiceHeaderViewHolder && item instanceof ServiceHeaderItem) {
            ServiceHeaderViewHolder viewHolder = (ServiceHeaderViewHolder) holder;

            // Check if service is enabled
            String serviceName = context.getPackageName() + "/" + DistractionControlService.class.getCanonicalName();
            int accessibilityEnabled = 0;
            try {
                accessibilityEnabled = Settings.Secure.getInt(
                        context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED);
            } catch (Settings.SettingNotFoundException e) {
                viewHolder.serviceEnabled.setChecked(false);
                return;
            }

            boolean isServiceEnabled = false;
            if (accessibilityEnabled == 1) {
                String settingValue = Settings.Secure.getString(
                        context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                isServiceEnabled = settingValue != null && settingValue.contains(serviceName);
                viewHolder.serviceEnabled.setChecked(isServiceEnabled);
            } else {
                viewHolder.serviceEnabled.setChecked(false);
            }

            viewHolder.serviceEnabled.setOnClickListener(v -> {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            });

            // Setup onboarding text and images based on OS flow type
            viewHolder.setupOnboardingText(context, usesTwoStepFlow);
            viewHolder.setupOnboardingImages(context, usesTwoStepFlow);

            // Hide "Need help?" section when service is enabled
            if (isServiceEnabled) {
                viewHolder.helpToggleButton.setVisibility(View.GONE);
                viewHolder.helpContent.setVisibility(View.GONE);
            } else {
                viewHolder.helpToggleButton.setVisibility(View.VISIBLE);
                // Keep help content collapsed by default when service is disabled
                if (!viewHolder.isHelpExpanded) {
                    viewHolder.helpContent.setVisibility(View.GONE);
                }
            }
        } else if (holder instanceof AppHeaderViewHolder && item instanceof AppHeaderItem) {
            AppHeaderViewHolder viewHolder = (AppHeaderViewHolder) holder;
            AppHeaderItem appItem = (AppHeaderItem) item;

            // Set dynamic rule count as subtitle
            viewHolder.packageName.setText(context.getResources().getQuantityString(R.plurals.hides_elements,
                    appItem.ruleCount, appItem.ruleCount));

            // Grey out if service is disabled
            viewHolder.itemView.setAlpha(serviceEnabled ? 1.0f : 0.4f);
            viewHolder.packageSwitch.setEnabled(serviceEnabled);
            String packageName = appItem.packageName;

            // Check if this is a known app
            String displayName = KNOWN_APP_NAMES.get(packageName);
            Integer iconRes = KNOWN_APP_ICONS.get(packageName);

            // Try to get app info to check if installed
            boolean isInstalled = false;
            try {
                ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
                isInstalled = true;
            } catch (PackageManager.NameNotFoundException e) {
                isInstalled = false;
            }
            final boolean finalIsInstalled = isInstalled;

            // Get or initialize expanded state for this app
            boolean isExpanded = appExpandedStates.getOrDefault(
                    packageName,
                    collapsePrefs.getBoolean(KEY_APP_EXPANDED + packageName, false));
            viewHolder.isExpanded = isExpanded;
            appExpandedStates.put(packageName, isExpanded);

            // Set chevron rotation based on state
            viewHolder.expandChevron.setRotation(isExpanded ? 180f : 0f);
            viewHolder.expandChevron.setContentDescription(
                    context.getString(isExpanded ? R.string.collapse_app_rules : R.string.expand_app_rules));

            // Setup chevron click handler
            viewHolder.expandChevron.setOnClickListener(v -> {
                if (!finalIsInstalled) {
                    Toast.makeText(context, R.string.app_not_installed, Toast.LENGTH_SHORT).show();
                    return;
                }
                viewHolder.isExpanded = !viewHolder.isExpanded;
                appExpandedStates.put(packageName, viewHolder.isExpanded);

                // Save state to SharedPreferences
                collapsePrefs.edit()
                        .putBoolean(KEY_APP_EXPANDED + packageName, viewHolder.isExpanded)
                        .apply();

                // Animate chevron rotation
                viewHolder.expandChevron.animate()
                        .rotation(viewHolder.isExpanded ? 180f : 0f)
                        .setDuration(200)
                        .start();

                // Update contentDescription
                viewHolder.expandChevron.setContentDescription(
                        context.getString(
                                viewHolder.isExpanded ? R.string.collapse_app_rules : R.string.expand_app_rules));

                // Rebuild items list to show/hide rules
                rebuildItemsList();
            });

            // Set name and icon
            if (displayName != null && iconRes != null) {
                // Known app - use hardcoded name and icon
                viewHolder.appName.setText(displayName);
                viewHolder.appIcon.setImageResource(iconRes);
            } else {
                // Unknown app - try to get from PackageManager
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
                matrix.setSaturation(0); // Grayscale
                viewHolder.appIcon.setColorFilter(new ColorMatrixColorFilter(matrix));
                viewHolder.appIcon.setAlpha(0.5f);
            } else {
                viewHolder.appIcon.clearColorFilter();
                viewHolder.appIcon.setAlpha(1.0f);
            }

            // Set up package switch
            viewHolder.packageSwitch.setOnCheckedChangeListener(null); // Remove any existing listener
            viewHolder.packageSwitch.setChecked(!config.isPackageDisabled(packageName)); // Invert the disabled state
                                                                                         // for the switch
            viewHolder.packageSwitch.setOnClickListener(v -> {
                if (!finalIsInstalled) {
                    // Revert the visual toggle change, since we intercepted the click but it might
                    // still toggle visually
                    viewHolder.packageSwitch.setChecked(!config.isPackageDisabled(packageName));
                    Toast.makeText(context, R.string.app_not_installed, Toast.LENGTH_SHORT).show();
                }
            });
            viewHolder.packageSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!finalIsInstalled)
                    return; // Handled by onClickListener

                config.setPackageDisabled(packageName, !isChecked); // Invert the switch state for disabled state

                if (isChecked) {
                    // When enabling a collapsed app, force expand to show rules
                    if (!viewHolder.isExpanded) {
                        viewHolder.isExpanded = true;
                        appExpandedStates.put(packageName, true);
                        collapsePrefs.edit()
                                .putBoolean(KEY_APP_EXPANDED + packageName, true)
                                .apply();

                        // Animate chevron to expanded state
                        viewHolder.expandChevron.animate()
                                .rotation(180f)
                                .setDuration(200)
                                .start();
                        viewHolder.expandChevron.setContentDescription(
                                context.getString(R.string.collapse_app_rules));
                    }
                } else {
                    // When disabling app, force collapse to hide rules
                    if (viewHolder.isExpanded) {
                        viewHolder.isExpanded = false;
                        appExpandedStates.put(packageName, false);
                        collapsePrefs.edit()
                                .putBoolean(KEY_APP_EXPANDED + packageName, false)
                                .apply();

                        // Animate chevron to collapsed state
                        viewHolder.expandChevron.animate()
                                .rotation(0f)
                                .setDuration(200)
                                .start();
                        viewHolder.expandChevron.setContentDescription(
                                context.getString(R.string.expand_app_rules));
                    }
                }

                // When enabling app, if all rules are currently disabled, enable them all
                if (isChecked) {
                    // Check if all rules for this app are currently disabled
                    boolean allRulesDisabled = true;
                    for (FilterRule rule : currentRules) {
                        if (rule.packageName.equals(packageName) && rule.enabled) {
                            allRulesDisabled = false;
                            break;
                        }
                    }

                    // If all rules were off, turn them all on
                    if (allRulesDisabled) {
                        for (FilterRule rule : currentRules) {
                            if (rule.packageName.equals(packageName)) {
                                rule.enabled = true;
                                config.setRuleEnabled(rule, true);
                            }
                        }
                    }
                }

                // Note: When disabling app, we don't change rule states

                // When disabling a package, we don't change individual rule states
                // When enabling a package, we restore the individual rule states
                if (isChecked) {
                    // Update all rules for this package to their saved states
                    for (int i = position + 1; i < items.size(); i++) {
                        Object nextItem = items.get(i);
                        if (nextItem instanceof RuleItem) {
                            RuleItem ruleItem = (RuleItem) nextItem;
                            if (ruleItem.rule.packageName.equals(packageName)) {
                                // Get the saved state from SharedPreferences
                                String key = ServiceConfig.KEY_RULE_ENABLED + ruleItem.rule.hashCode();
                                boolean savedState = config.getPrefs().getBoolean(key, true);
                                ruleItem.rule.enabled = savedState;
                            }
                        } else if (nextItem instanceof AppHeaderItem) {
                            // Stop when we reach the next app header
                            break;
                        }
                    }
                }

                // Rebuild items list to show/hide expanded rules
                rebuildItemsList();

                // Notify the service to update its rules
                DistractionControlService service = DistractionControlService.getInstance();
                if (service != null) {
                    service.updateRules();
                }
            });
        } else if (holder instanceof RuleViewHolder && item instanceof RuleItem) {
            RuleViewHolder viewHolder = (RuleViewHolder) holder;
            RuleItem ruleItem = (RuleItem) item;
            FilterRule rule = ruleItem.rule;

            // Grey out if service is disabled
            viewHolder.itemView.setAlpha(serviceEnabled ? 1.0f : 0.4f);

            viewHolder.ruleDescription.setText(rule.description);

            // Hide ruleDetails by default
            viewHolder.ruleDetails.setVisibility(View.GONE);

            // If the rule has contentDescriptions (desc field), show the alert
            if (!rule.contentDescriptions.isEmpty()) {
                viewHolder.ruleDetails.setText(context.getString(R.string.rule_requires_english));
                viewHolder.ruleDetails.setVisibility(View.VISIBLE);
            }

            // Check if the package is disabled
            boolean isPackageDisabled = config.isPackageDisabled(rule.packageName);

            // Remove any existing listener to prevent duplicate callbacks
            viewHolder.ruleSwitch.setOnCheckedChangeListener(null);
            // Set the current state
            viewHolder.ruleSwitch.setChecked(rule.enabled);
            // Disable the switch if the package is disabled OR service is disabled
            viewHolder.ruleSwitch.setEnabled(serviceEnabled && !isPackageDisabled);
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
                        currentRule.enabled = isChecked;
                        config.setRuleEnabled(currentRule, isChecked);

                        // If disabling a rule, check if it's the last enabled rule for this app
                        if (!isChecked) {
                            boolean anyRulesStillEnabled = false;
                            for (FilterRule r : currentRules) {
                                if (r.packageName.equals(currentRule.packageName) && r.enabled) {
                                    anyRulesStillEnabled = true;
                                    break;
                                }
                            }

                            // If no rules are enabled for this app, disable the app entirely and collapse
                            if (!anyRulesStillEnabled) {
                                config.setPackageDisabled(currentRule.packageName, true);

                                // Collapse the app
                                appExpandedStates.put(currentRule.packageName, false);
                                collapsePrefs.edit()
                                        .putBoolean(KEY_APP_EXPANDED + currentRule.packageName, false)
                                        .apply();

                                // Rebuild to update the package switch UI and hide rules
                                rebuildItemsList();
                            }
                        }

                        // Notify the service to update its rules
                        DistractionControlService service = DistractionControlService.getInstance();
                        if (service != null) {
                            service.updateRules();
                        }

                        if (onRuleStateChangedListener != null) {
                            onRuleStateChangedListener.onRuleStateChanged(currentRule);
                        }
                    }
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public void refreshServiceState() {
        boolean newServiceEnabled = isAccessibilityServiceEnabled();
        if (this.serviceEnabled != newServiceEnabled) {
            this.serviceEnabled = newServiceEnabled;
            notifyDataSetChanged(); // Refresh all items to update alpha
        } else {
            // Find the service header position
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i) instanceof ServiceHeaderItem) {
                    notifyItemChanged(i);
                    break;
                }
            }
        }
    }

    public interface OnRuleStateChangedListener {
        void onRuleStateChanged(FilterRule rule);
    }

    // Item classes for different view types
    private static class ServiceHeaderItem {
    }

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

    static class ServiceHeaderViewHolder extends RecyclerView.ViewHolder {
        MaterialSwitch serviceEnabled;
        View helpToggleButton;
        ImageView helpChevron;
        View helpContent;
        TextView step1Text;
        TextView step2Text;
        TextView step3Text;
        TextView step4Text;
        TextView step5Text;
        ImageView step1Image;
        ImageView step2Image;
        ImageView step3Image;
        ImageView step4Image;
        ImageView step5Image;
        View step4Container;
        View step5Container;
        View arrowStep4Step5;
        boolean isHelpExpanded = false;

        ServiceHeaderViewHolder(View itemView) {
            super(itemView);
            serviceEnabled = itemView.findViewById(R.id.service_enabled);
            helpToggleButton = itemView.findViewById(R.id.help_toggle_button);
            helpChevron = itemView.findViewById(R.id.help_chevron);
            helpContent = itemView.findViewById(R.id.help_content);
            step1Text = itemView.findViewById(R.id.step1_text);
            step2Text = itemView.findViewById(R.id.step2_text);
            step3Text = itemView.findViewById(R.id.step3_text);
            step4Text = itemView.findViewById(R.id.step4_text);
            step5Text = itemView.findViewById(R.id.step5_text);
            step1Image = itemView.findViewById(R.id.step1_image);
            step2Image = itemView.findViewById(R.id.step2_image);
            step3Image = itemView.findViewById(R.id.step3_image);
            step4Image = itemView.findViewById(R.id.step4_image);
            step5Image = itemView.findViewById(R.id.step5_image);
            step4Container = itemView.findViewById(R.id.step4_container);
            step5Container = itemView.findViewById(R.id.step5_container);
            arrowStep4Step5 = itemView.findViewById(R.id.arrow_step4_step5);

            // Setup collapse/expand toggle
            helpToggleButton.setOnClickListener(v -> toggleHelpSection());
        }

        /**
         * Gets the resource ID for a step image based on step number, flow type, and
         * theme
         */
        private int getStepImageResource(Context context, int stepNumber, boolean usesTwoStepFlow) {
            // Detect dark mode
            boolean isDarkMode = (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

            // Build resource name: step[N]_[flow]
            String flowType = usesTwoStepFlow ? "twostep" : "onestep";
            String resourceName = "step" + stepNumber + "_" + flowType;

            // Get resource ID dynamically
            int resourceId = context.getResources().getIdentifier(
                    resourceName, "drawable", context.getPackageName());

            // Fallback to placeholder if image not found
            if (resourceId == 0) {
                // Return transparent drawable as fallback
                return android.R.color.transparent;
            }

            return resourceId;
        }

        void setupOnboardingText(Context context, boolean usesTwoStepFlow) {
            if (usesTwoStepFlow) {
                // Two-step flow (Samsung/OneUI, etc.) - 5 steps
                step1Text.setText(context.getString(R.string.onboarding_step1_twostep));
                step2Text.setText(context.getString(R.string.onboarding_step2_twostep));
                step3Text.setText(context.getString(R.string.onboarding_step3_twostep));
                step4Text.setText(context.getString(R.string.onboarding_step4_twostep));
                step5Text.setText(context.getString(R.string.onboarding_step5_twostep));

                // Show both step 4 and step 5, plus arrow between them
                step4Container.setVisibility(View.VISIBLE);
                step5Container.setVisibility(View.VISIBLE);
                arrowStep4Step5.setVisibility(View.VISIBLE);
            } else {
                // One-step flow (Pixel, stock Android) - 4 steps
                step1Text.setText(context.getString(R.string.onboarding_step1_onestep));
                step2Text.setText(context.getString(R.string.onboarding_step2_onestep));
                step3Text.setText(context.getString(R.string.onboarding_step3_onestep));
                step4Text.setText(context.getString(R.string.onboarding_step4_onestep));

                // Hide step 5 and arrow, show step 4 only
                step4Container.setVisibility(View.VISIBLE);
                step5Container.setVisibility(View.GONE);
                arrowStep4Step5.setVisibility(View.GONE);
            }
        }

        void setupOnboardingImages(Context context, boolean usesTwoStepFlow) {
            // Load images for all steps
            step1Image.setImageResource(getStepImageResource(context, 1, usesTwoStepFlow));
            step2Image.setImageResource(getStepImageResource(context, 2, usesTwoStepFlow));
            step3Image.setImageResource(getStepImageResource(context, 3, usesTwoStepFlow));
            step4Image.setImageResource(getStepImageResource(context, 4, usesTwoStepFlow));

            // Step 5 only exists for two-step flow
            if (usesTwoStepFlow) {
                step5Image.setImageResource(getStepImageResource(context, 5, usesTwoStepFlow));
            }
        }

        private void toggleHelpSection() {
            isHelpExpanded = !isHelpExpanded;

            if (isHelpExpanded) {
                // Expanding: measure target height first
                // Temporarily set to WRAP_CONTENT to get accurate measurement
                helpContent.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                helpContent.setVisibility(View.VISIBLE);
                helpContent.measure(
                        View.MeasureSpec.makeMeasureSpec(((View) helpContent.getParent()).getWidth(),
                                View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                final int targetHeight = helpContent.getMeasuredHeight();

                // Start from 0 height
                helpContent.getLayoutParams().height = 0;
                helpContent.setAlpha(0f);

                // Animate to target height with fade in
                ValueAnimator heightAnimator = ValueAnimator.ofInt(0, targetHeight);
                heightAnimator.setDuration(250);
                heightAnimator.setInterpolator(new DecelerateInterpolator());
                heightAnimator.addUpdateListener(animation -> {
                    helpContent.getLayoutParams().height = (int) animation.getAnimatedValue();
                    helpContent.setAlpha(animation.getAnimatedFraction());
                    helpContent.requestLayout();
                });
                heightAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        // Keep the fixed height to prevent jump - it's already at the correct size
                        helpContent.setAlpha(1f);
                    }
                });
                heightAnimator.start();
            } else {
                // Collapsing: animate from current height to 0
                final int startHeight = helpContent.getHeight();
                ValueAnimator heightAnimator = ValueAnimator.ofInt(startHeight, 0);
                heightAnimator.setDuration(200);
                heightAnimator.setInterpolator(new DecelerateInterpolator());
                heightAnimator.addUpdateListener(animation -> {
                    helpContent.getLayoutParams().height = (int) animation.getAnimatedValue();
                    helpContent.setAlpha(1f - animation.getAnimatedFraction());
                    helpContent.requestLayout();
                });
                heightAnimator.addListener(new android.animation.AnimatorListenerAdapter() {

                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        helpContent.setVisibility(View.GONE);
                        helpContent.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    }
                });
                heightAnimator.start();
            }

            // Smooth chevron rotation
            helpChevron.animate().rotation(isHelpExpanded ? 180f : 0f).setDuration(250).start();

            // Update contentDescription for accessibility
            helpChevron.setContentDescription(itemView.getContext()
                    .getString(isHelpExpanded ? R.string.collapse_help_content : R.string.expand_help_content));
        }
    }

    static class AppHeaderViewHolder extends RecyclerView.ViewHolder {
        TextView appName;
        TextView packageName;
        ImageView appIcon;
        MaterialSwitch packageSwitch;
        ImageView expandChevron;
        boolean isExpanded = true; // Default expanded

        AppHeaderViewHolder(View itemView) {
            super(itemView);
            appName = itemView.findViewById(R.id.app_name);
            packageName = itemView.findViewById(R.id.package_name);
            appIcon = itemView.findViewById(R.id.app_icon);
            packageSwitch = itemView.findViewById(R.id.package_switch);
            expandChevron = itemView.findViewById(R.id.expand_chevron);
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