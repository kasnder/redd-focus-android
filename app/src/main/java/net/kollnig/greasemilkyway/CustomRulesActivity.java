package net.kollnig.greasemilkyway;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.appbar.MaterialToolbar;

import net.kollnig.distractionlib.FilterRuleParser;

public class CustomRulesActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "picker_prefs";
    private static final String KEY_PICKER_INTRO_SHOWN = "picker_intro_shown";

    private EditText rulesEditor;
    private ServiceConfig config;

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    showPickerNotification();
                }
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putBoolean(KEY_PICKER_INTRO_SHOWN, true).apply();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_custom_rules);
        
        // Setup navigation bar color to match app background
        NavigationBarHelper.setup(this);

        // Setup toolbar
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.custom_rules_title);
        }

        // Initialize config
        config = new ServiceConfig(this);

        // Initialize views
        rulesEditor = findViewById(R.id.rules_editor);

        // Setup README link (after loading rules to avoid any interference)
        TextView readmeLink = findViewById(R.id.readme_link);
        if (readmeLink != null) {
            setupReadmeLink(readmeLink);
        }

        // Setup FAB to show element picker notification
        View fab = findViewById(R.id.custom_rules_button);
        if (getResources().getBoolean(R.bool.show_custom_rules_fab)) {
            fab.setOnClickListener(v -> onFabClicked());
        } else {
            fab.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload rules from SharedPreferences every time the activity becomes visible.
        // This ensures that rules added externally (e.g. via the element picker) are
        // reflected in the editor instead of being silently overwritten on the next onPause.
        String[] customRules = config.getCustomRules();
        rulesEditor.setText(customRules != null ? String.join("\n", customRules) : "");
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveRules();
    }

    private void saveRules() {
        String rulesText = rulesEditor.getText().toString();
        String[] rawLines = rulesText.split("\n");

        // Filter out empty lines to avoid accumulating blanks
        java.util.List<String> filtered = new java.util.ArrayList<>();
        for (String line : rawLines) {
            if (!line.trim().isEmpty()) {
                filtered.add(line);
            }
        }
        String[] rules = filtered.toArray(new String[0]);

        // Parse rules
        FilterRuleParser parser = new FilterRuleParser();
        try {
            parser.parseRules(rules);
            config.saveCustomRules(rules);
            
            // Update service rules
            DistractionControlService service = DistractionControlService.getInstance();
            if (service != null) {
                service.updateRules();
            }
        } catch (Exception e) {
            Toast.makeText(this, R.string.invalid_rules, Toast.LENGTH_LONG).show();
        }
    }

    private void onFabClicked() {
        boolean introShown = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_PICKER_INTRO_SHOWN, false);

        if (!introShown) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.picker_intro_title)
                    .setMessage(R.string.picker_intro_message)
                    .setPositiveButton(R.string.picker_intro_enable, (dialog, which) -> requestNotificationPermissionAndShow())
                    .setNegativeButton(R.string.picker_intro_cancel, null)
                    .show();
        } else {
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
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(KEY_PICKER_INTRO_SHOWN, true).apply();
            showPickerNotification();
        }
    }

    private void showPickerNotification() {
        ElementPickerNotification notification = new ElementPickerNotification(this);
        notification.showNotification();
        Toast.makeText(this, R.string.picker_notification_shown, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setupReadmeLink(TextView textView) {
        if (textView == null) {
            return;
        }
        
        try {
            String fullText = getString(R.string.custom_rules_readme_link);
            SpannableString spannableString = new SpannableString(fullText);
            
            String linkText = "Custom Rules README";
            int start = fullText.indexOf(linkText);
            if (start >= 0) {
                int end = start + linkText.length();
                
                // Make "README" clickable (no special styling)
                ClickableSpan clickableSpan = new ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/kasnder/GreaseMilkyway/blob/main/docs/CUSTOM_RULES.md"));
                        startActivity(browserIntent);
                    }
                    
                    @Override
                    public void updateDrawState(android.text.TextPaint ds) {
                        // Keep default text color, underline to show it's a link
                        ds.setUnderlineText(true);
                        ds.setColor(textView.getCurrentTextColor());
                    }
                };
                
                spannableString.setSpan(clickableSpan, start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            
            textView.setText(spannableString);
            textView.setMovementMethod(LinkMovementMethod.getInstance());
        } catch (Exception e) {
            // If setup fails, just set the plain text
            textView.setText(getString(R.string.custom_rules_readme_link));
        }
    }
} 
