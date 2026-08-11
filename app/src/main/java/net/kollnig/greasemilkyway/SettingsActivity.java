package net.kollnig.greasemilkyway;

import android.content.Intent;
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

import net.kollnig.distractionlib.FrictionGateActivity;
import net.kollnig.distractionlib.FilterRuleParser;

public class SettingsActivity extends AppCompatActivity {

    private ServiceConfig config;
    private TextView tvFrictionGateSubtitle;
    private TextView tvPauseDurationSubtitle;
    private TextView tvNotificationTimeoutSubtitle;
    private TextView tvCustomRulesSubtitle;
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
        tvCustomRulesSubtitle = findViewById(R.id.tv_custom_rules_subtitle);

        updateSubtitles();

        findViewById(R.id.btn_custom_rules).setOnClickListener(v -> startActivity(new Intent(SettingsActivity.this, CustomRulesActivity.class)));

        findViewById(R.id.btn_friction_gate).setOnClickListener(v -> runWithFrictionGate(
                getString(R.string.unlock_friction_settings),
                () -> showNumberPickerDialog(getString(R.string.friction_gate_dialog_title),
                        getString(R.string.friction_gate_dialog_message), 0, 15,
                        config.getFrictionWordCount(), newValue -> {
            config.setFrictionWordCount(newValue);
            updateSubtitles();
        })));

        findViewById(R.id.btn_pause_duration).setOnClickListener(v -> runWithFrictionGate(
                getString(R.string.unlock_friction_settings),
                () -> showNumberPickerDialog(getString(R.string.pause_duration_dialog_title),
                        getString(R.string.pause_duration_dialog_message), 1, 120,
                        config.getPauseDurationMins(), newValue -> {
            config.setPauseDurationMins(newValue);
            updateSubtitles();
        })));

        findViewById(R.id.btn_notification_timeout).setOnClickListener(v -> {
            final String[] labels = getResources().getStringArray(R.array.response_speed_choices);
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
                .setTitle(R.string.response_speed_dialog_title)
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
            int words = config.getFrictionWordCount();
            tvFrictionGateSubtitle.setText(getResources().getQuantityString(
                    R.plurals.friction_gate_word_count, words, words));
        }
        if (tvPauseDurationSubtitle != null) {
            int minutes = config.getPauseDurationMins();
            tvPauseDurationSubtitle.setText(getResources().getQuantityString(
                    R.plurals.pause_duration_minute_count, minutes, minutes));
        }
        if (tvNotificationTimeoutSubtitle != null) {
            long ms = config.getNotificationTimeoutMs();
            String label;
            if (ms <= 0) {
                label = getString(R.string.response_speed_immediate_consequence);
            } else if (ms >= 300) {
                label = getString(R.string.response_speed_battery_consequence);
            } else {
                label = getString(R.string.response_speed_default_consequence);
            }
            tvNotificationTimeoutSubtitle.setText(label);
        }
        if (tvCustomRulesSubtitle != null) {
            int count = 0;
            FilterRuleParser parser = new FilterRuleParser();
            String[] blocking = config.getCustomRules();
            String[] navigation = config.getCustomNavigationRules();
            if (blocking != null) count += parser.parseRules(blocking).size();
            if (navigation != null) count += parser.parseRules(navigation).size();
            String ruleCount = getResources().getQuantityString(
                    R.plurals.rule_count, count, count);
            tvCustomRulesSubtitle.setText(
                    getString(R.string.custom_rules_count_summary, ruleCount));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (config != null) updateSubtitles();
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
