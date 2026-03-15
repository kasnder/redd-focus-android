package net.kollnig.greasemilkyway;

import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class MainActivity extends AppCompatActivity {
    private ServiceConfig config;
    private RecyclerView rulesList;
    private RulesAdapter adapter;

    private Runnable onFrictionGatePassed;

    private final ActivityResultLauncher<Intent> frictionGateLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && onFrictionGatePassed != null) {
                    onFrictionGatePassed.run();
                }
                onFrictionGatePassed = null;
                // Re-enable UI or rebuild list if needed
                loadSettings();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Setup navigation bar color and icon appearance
        setupNavigationBarColor();

        // Setup toolbar
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setTitle(R.string.app_name);

        // Initialize config
        config = new ServiceConfig(this);

        // Initialize views
        rulesList = findViewById(R.id.rules_list);

        // Setup RecyclerView
        rulesList.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RulesAdapter(this, config);
        rulesList.setAdapter(adapter);

        // Load current settings
        loadSettings();

        // Setup footer with clickable link
        setupFooter();

        // Setup settings button in footer
        findViewById(R.id.settings_button).setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        });
        
        // Setup navigation bar padding - reduces available height to push content above nav bar
        setupNavigationBarPadding();
    }

    private void showAccessibilityPrompt() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.accessibility_prompt_title)
                .setMessage(R.string.accessibility_prompt_message)
                .setPositiveButton(R.string.accessibility_prompt_enable, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                })
                .setNeutralButton(R.string.accessibility_prompt_help, (dialog, which) -> {
                    showAccessibilityHelpDialog(this);
                })
                .setNegativeButton(R.string.picker_intro_cancel, (dialog, which) -> {
                    finish();
                })
                .setCancelable(false)
                .show();
    }

    static void showAccessibilityHelpDialog(AppCompatActivity activity) {
        View helpView = LayoutInflater.from(activity).inflate(R.layout.dialog_accessibility_help, null);

        // Detect device flow type
        boolean usesTwoStepFlow = detectTwoStepFlow();

        // Setup step text
        TextView step1Text = helpView.findViewById(R.id.step1_text);
        TextView step2Text = helpView.findViewById(R.id.step2_text);
        TextView step3Text = helpView.findViewById(R.id.step3_text);
        TextView step4Text = helpView.findViewById(R.id.step4_text);
        TextView step5Text = helpView.findViewById(R.id.step5_text);

        if (usesTwoStepFlow) {
            step1Text.setText(R.string.onboarding_step1_twostep);
            step2Text.setText(R.string.onboarding_step2_twostep);
            step3Text.setText(R.string.onboarding_step3_twostep);
            step4Text.setText(R.string.onboarding_step4_twostep);
            step5Text.setText(R.string.onboarding_step5_twostep);
            helpView.findViewById(R.id.step4_container).setVisibility(View.VISIBLE);
            helpView.findViewById(R.id.step5_container).setVisibility(View.VISIBLE);
            helpView.findViewById(R.id.arrow_step4_step5).setVisibility(View.VISIBLE);
        } else {
            step1Text.setText(R.string.onboarding_step1_onestep);
            step2Text.setText(R.string.onboarding_step2_onestep);
            step3Text.setText(R.string.onboarding_step3_onestep);
            step4Text.setText(R.string.onboarding_step4_onestep);
            helpView.findViewById(R.id.step4_container).setVisibility(View.VISIBLE);
            helpView.findViewById(R.id.step5_container).setVisibility(View.GONE);
            helpView.findViewById(R.id.arrow_step4_step5).setVisibility(View.GONE);
        }

        // Setup step images
        setupStepImage(activity, helpView, R.id.step1_image, 1, usesTwoStepFlow);
        setupStepImage(activity, helpView, R.id.step2_image, 2, usesTwoStepFlow);
        setupStepImage(activity, helpView, R.id.step3_image, 3, usesTwoStepFlow);
        setupStepImage(activity, helpView, R.id.step4_image, 4, usesTwoStepFlow);
        if (usesTwoStepFlow) {
            setupStepImage(activity, helpView, R.id.step5_image, 5, usesTwoStepFlow);
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.accessibility_help_title)
                .setView(helpView)
                .setPositiveButton(R.string.accessibility_prompt_enable, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                })
                .setNegativeButton(R.string.picker_intro_cancel, (dialog, which) -> {
                    activity.finish();
                })
                .setCancelable(false)
                .show();
    }

    private static void setupStepImage(AppCompatActivity activity, View parent, int imageViewId, int stepNumber, boolean usesTwoStepFlow) {
        ImageView imageView = parent.findViewById(imageViewId);
        String flowType = usesTwoStepFlow ? "twostep" : "onestep";
        String resourceName = "step" + stepNumber + "_" + flowType;
        int resourceId = activity.getResources().getIdentifier(resourceName, "drawable", activity.getPackageName());
        if (resourceId != 0) {
            imageView.setImageResource(resourceId);
        }
    }

    private static boolean detectTwoStepFlow() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        return manufacturer.contains("samsung") ||
                manufacturer.contains("xiaomi") ||
                manufacturer.contains("oppo") ||
                manufacturer.contains("vivo") ||
                manufacturer.contains("oneplus");
    }

    private void setupFooter() {
        TextView footerText = findViewById(R.id.footer_text);
        
        String fullText = "Made with ❤️ by reddfocus.org";
        SpannableString spannableString = new SpannableString(fullText);
        
        int start = fullText.indexOf("reddfocus.org");
        int end = start + "reddfocus.org".length();
        
        // Make "reddfocus.org" clickable (no special styling)
        ClickableSpan clickableSpan = new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://reddfocus.org"));
                startActivity(browserIntent);
            }
            
            @Override
            public void updateDrawState(android.text.TextPaint ds) {
                // Keep default text color, no underline
                ds.setUnderlineText(false);
                ds.setColor(footerText.getCurrentTextColor());
            }
        };
        spannableString.setSpan(clickableSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        
        footerText.setText(spannableString);
        footerText.setMovementMethod(LinkMovementMethod.getInstance());
        footerText.setHighlightColor(android.graphics.Color.TRANSPARENT);
    }

    public void runWithFrictionGate(String contextTitle, Runnable action) {
        int wordCount = config.getFrictionWordCount();
        if (wordCount <= 0) {
            action.run();
            return;
        }

        this.onFrictionGatePassed = action;
        Intent intent = new Intent(this, FrictionGateActivity.class);
        intent.putExtra("WORD_COUNT", wordCount);
        intent.putExtra("CONTEXT_TITLE", contextTitle);
        frictionGateLauncher.launch(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Gate: if accessibility service is not enabled, prompt and block
        if (!isAccessibilityServiceEnabled()) {
            showAccessibilityPrompt();
            return;
        }

        // Reload settings to pick up any new custom rules
        loadSettings();
    }

    private void loadSettings() {
        List<FilterRule> rules = config.getRules();
        Log.d("SettingsActivity", "Loading " + rules.size() + " rules");
        for (FilterRule rule : rules) {
            Log.d("SettingsActivity", "Rule for " + rule.packageName + " with description: " + rule.description);
        }
        adapter.setRules(rules);
    }

    private void setupNavigationBarColor() {
        // Get the background color from theme
        int backgroundColor = getResources().getColor(R.color.background_main, getTheme());
        // Set navigation bar color to match app background
        getWindow().setNavigationBarColor(backgroundColor);
        
        // Set navigation bar icon color: grey in light mode, white in dark mode
        boolean isLightMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) 
                             != Configuration.UI_MODE_NIGHT_YES;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int appearance = isLightMode ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0;
                controller.setSystemBarsAppearance(appearance, WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            View decorView = getWindow().getDecorView();
            int flags = decorView.getSystemUiVisibility();
            if (isLightMode) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decorView.setSystemUiVisibility(flags);
        }
    }

    private void setupNavigationBarPadding() {
        // Apply window insets to root CoordinatorLayout to reduce available height
        // This pushes all content (footer, FAB, RecyclerView) up by nav bar height
        View rootLayout = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            Insets navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            Insets statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            
            // Set padding on root layout: top = status bar, bottom = nav bar
            // This reduces available height, pushing all content up uniformly
            v.setPadding(
                v.getPaddingLeft(),
                statusBarInsets.top,
                v.getPaddingRight(),
                navBarInsets.bottom
            );
            
            return insets;
        });
    }

    private boolean isAccessibilityServiceEnabled() {
        String serviceName = getPackageName() + "/" + DistractionControlService.class.getCanonicalName();
        int accessibilityEnabled = 0;
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                    getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            return false;
        }

        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                return settingValue.contains(serviceName);
            }
        }
        return false;
    }
} 