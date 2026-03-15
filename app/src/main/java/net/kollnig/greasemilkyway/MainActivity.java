package net.kollnig.greasemilkyway;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import android.view.MenuItem;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.appbar.MaterialToolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "picker_prefs";
    private static final String KEY_PICKER_INTRO_SHOWN = "picker_intro_shown";

    private ServiceConfig config;
    private RecyclerView rulesList;
    private RulesAdapter adapter;

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    showPickerNotification();
                }
                // Mark intro as shown regardless of grant result
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putBoolean(KEY_PICKER_INTRO_SHOWN, true).apply();
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

        // Setup FAB to show element picker notification
        findViewById(R.id.custom_rules_button).setOnClickListener(v -> onFabClicked());

        // Load current settings
        loadSettings();

        // Setup footer with clickable link
        setupFooter();

        // Setup settings button in footer
        findViewById(R.id.settings_button).setOnClickListener(v -> {
            Intent intent = new Intent(this, CustomRulesActivity.class);
            startActivity(intent);
        });
        
        // Setup navigation bar padding - reduces available height to push content above nav bar
        setupNavigationBarPadding();
    }

    private void onFabClicked() {
        boolean introShown = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_PICKER_INTRO_SHOWN, false);

        if (!introShown) {
            // First time: show explanation dialog, then request notification permission
            new AlertDialog.Builder(this)
                    .setTitle(R.string.picker_intro_title)
                    .setMessage(R.string.picker_intro_message)
                    .setPositiveButton(R.string.picker_intro_enable, (dialog, which) -> {
                        requestNotificationPermissionAndShow();
                    })
                    .setNegativeButton(R.string.picker_intro_cancel, null)
                    .show();
        } else {
            // Subsequent use: check permission and show notification directly
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionAndShow();
            } else {
                showPickerNotification();
            }
        }
    }

    private void requestNotificationPermissionAndShow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            // Pre-Android 13: no runtime permission needed
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(KEY_PICKER_INTRO_SHOWN, true).apply();
            showPickerNotification();
        }
    }

    private void showPickerNotification() {
        DistractionControlService service = DistractionControlService.getInstance();
        if (service != null) {
            ElementPickerNotification notification = new ElementPickerNotification(this);
            notification.showNotification();
            Toast.makeText(this, R.string.picker_notification_shown, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.enable_service_first, Toast.LENGTH_SHORT).show();
        }
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

    @Override
    protected void onResume() {
        super.onResume();
        // Reload settings to pick up any new custom rules
        loadSettings();
        // Update adapter to grey out items when service disabled
        adapter.refreshServiceState();
        // Check if accessibility service is enabled
        String serviceName = getPackageName() + "/" + DistractionControlService.class.getCanonicalName();
        int accessibilityEnabled = 0;
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                    getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            return;
        }

        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            // Rules are already reloaded at the start of onResume()
        }

        // Notify the adapter to update the service header
        adapter.notifyItemChanged(0);  // Service header is always at position 0
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


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
} 