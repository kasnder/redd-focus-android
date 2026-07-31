package net.kollnig.greasemilkyway;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
import android.widget.NumberPicker;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.appbar.MaterialToolbar;

import net.kollnig.distractionlib.FilterRule;
import net.kollnig.distractionlib.FrictionGateActivity;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {

    private ServiceConfig config;
    private TextView tvFrictionGateSubtitle;
    private TextView tvPauseDurationSubtitle;
    private TextView tvNotificationTimeoutSubtitle;
    private TextView tvAutoNavigationSubtitle;
    private Runnable pendingFrictionAction;

    private final ActivityResultLauncher<Intent> frictionGateLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Runnable action = pendingFrictionAction;
                    pendingFrictionAction = null;
                    if (action != null) {
                        action.run();
                    }
                } else {
                    pendingFrictionAction = null;
                    Toast.makeText(this, R.string.friction_gate_cancelled, Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        config = new ServiceConfig(this);

        NavigationBarHelper.setup(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        setupWindowInsets();

        initViews();
    }

    private void initViews() {
        findViewById(R.id.main).setVisibility(View.VISIBLE);
        
        tvFrictionGateSubtitle = findViewById(R.id.tv_friction_gate_subtitle);
        tvPauseDurationSubtitle = findViewById(R.id.tv_pause_duration_subtitle);
        tvNotificationTimeoutSubtitle = findViewById(R.id.tv_notification_timeout_subtitle);
        tvAutoNavigationSubtitle = findViewById(R.id.tv_auto_navigation_subtitle);

        updateSubtitles();

        findViewById(R.id.btn_custom_rules).setOnClickListener(v -> startActivity(new Intent(SettingsActivity.this, CustomRulesActivity.class)));

        findViewById(R.id.btn_auto_navigation).setOnClickListener(v -> showAutoNavigationDialog());

        findViewById(R.id.btn_friction_gate).setOnClickListener(v -> runWithFrictionGate(
                getString(R.string.unlock_friction_settings),
                () -> showNumberPickerDialog("Friction Gate Words", "Choose number of words (0-15)", 0, 15, config.getFrictionWordCount(), newValue -> {
            config.setFrictionWordCount(newValue);
            updateSubtitles();
        })));

        findViewById(R.id.btn_pause_duration).setOnClickListener(v -> runWithFrictionGate(
                getString(R.string.unlock_friction_settings),
                () -> showNumberPickerDialog("Pause Duration", "Choose default pause in minutes (1-120)", 1, 120, config.getPauseDurationMins(), newValue -> {
            config.setPauseDurationMins(newValue);
            updateSubtitles();
        })));

        findViewById(R.id.btn_notification_timeout).setOnClickListener(v -> {
            final String[] labels = {"Immediate response", "Default (recommended)", "Battery saver"};
            final long[] values = {0, 100, 300};
            long current = config.getNotificationTimeoutMs();
            int checkedItem = 1;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == current) {
                    checkedItem = i;
                    break;
                }
            }
            new AlertDialog.Builder(this)
                .setTitle("Response Speed")
                .setSingleChoiceItems(labels, checkedItem, (dialog, which) -> {
                    config.setNotificationTimeoutMs(values[which]);
                    updateSubtitles();
                    DistractionControlService svc = DistractionControlService.getInstance();
                    if (svc != null) {
                        svc.updateRules();
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        });
    }

    /**
     * Multi-choice list of the bundled navigation rules. Turning one on is the only step:
     * these targets are picked per app rather than authored, so there is nothing to configure
     * beyond which apps should skip their landing screen.
     */
    private void showAutoNavigationDialog() {
        final List<FilterRule> rules = config.getNavigationRules();
        if (rules.isEmpty()) {
            Toast.makeText(this, R.string.auto_navigation_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }

        final String[] labels = new String[rules.size()];
        final boolean[] checked = new boolean[rules.size()];
        for (int i = 0; i < rules.size(); i++) {
            FilterRule rule = rules.get(i);
            labels[i] = getString(R.string.auto_navigation_entry,
                    getAppLabel(rule.packageName), rule.description);
            checked[i] = rule.enabled;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.auto_navigation_title)
                .setMultiChoiceItems(labels, checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    for (int i = 0; i < rules.size(); i++) {
                        config.setNavigationRuleEnabled(rules.get(i), checked[i]);
                    }
                    updateSubtitles();
                    // The service holds its own copy of the rules, so it has to be told.
                    DistractionControlService svc = DistractionControlService.getInstance();
                    if (svc != null) {
                        svc.updateRules();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String getAppLabel(String packageName) {
        try {
            return getPackageManager()
                    .getApplicationLabel(getPackageManager().getApplicationInfo(packageName, 0))
                    .toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private void runWithFrictionGate(String contextTitle, Runnable action) {
        int requiredWords = config.getFrictionWordCount();
        if (requiredWords <= 0) {
            action.run();
            return;
        }

        pendingFrictionAction = action;
        Intent intent = new Intent(this, FrictionGateActivity.class);
        intent.putExtra(FrictionGateActivity.EXTRA_WORD_COUNT, requiredWords);
        intent.putExtra(FrictionGateActivity.EXTRA_CONTEXT_TITLE, contextTitle);
        frictionGateLauncher.launch(intent);
    }

    private void updateSubtitles() {
        if (tvFrictionGateSubtitle != null) {
            tvFrictionGateSubtitle.setText(getString(R.string.friction_gate_words, config.getFrictionWordCount()));
        }
        if (tvPauseDurationSubtitle != null) {
            tvPauseDurationSubtitle.setText(getString(R.string.pause_duration_minutes, config.getPauseDurationMins()));
        }
        if (tvNotificationTimeoutSubtitle != null) {
            long ms = config.getNotificationTimeoutMs();
            String label;
            if (ms <= 0) {
                label = getString(R.string.response_speed_immediate);
            } else if (ms >= 300) {
                label = getString(R.string.response_speed_battery_saver);
            } else {
                label = getString(R.string.response_speed_default);
            }
            tvNotificationTimeoutSubtitle.setText(label);
        }
        if (tvAutoNavigationSubtitle != null) {
            List<String> enabled = new ArrayList<>();
            for (FilterRule rule : config.getNavigationRules()) {
                if (rule.enabled) {
                    enabled.add(getAppLabel(rule.packageName));
                }
            }
            tvAutoNavigationSubtitle.setText(enabled.isEmpty()
                    ? getString(R.string.auto_navigation_none)
                    : String.join(", ", enabled));
        }
    }

    private void showNumberPickerDialog(String title, String message, int min, int max, int currentValue, final NumberPickerCallback callback) {
        final NumberPicker numberPicker = new NumberPicker(this);
        numberPicker.setMinValue(min);
        numberPicker.setMaxValue(max);
        numberPicker.setValue(currentValue);

        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(numberPicker)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> callback.onNumberPicked(numberPicker.getValue()))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void setupWindowInsets() {
        View rootLayout = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            Insets statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            Insets navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(
                v.getPaddingLeft(),
                statusBarInsets.top,
                v.getPaddingRight(),
                navBarInsets.bottom
            );
            return insets;
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    private interface NumberPickerCallback {
        void onNumberPicked(int value);
    }
}
