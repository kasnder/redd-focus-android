package net.kollnig.greasemilkyway;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
import android.widget.NumberPicker;

import android.content.res.Configuration;
import android.view.WindowInsetsController;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.appbar.MaterialToolbar;

import net.kollnig.distractionlib.FrictionGateActivity;

public class SettingsActivity extends AppCompatActivity {

    private ServiceConfig config;
    private TextView tvFrictionGateSubtitle;
    private TextView tvPauseDurationSubtitle;
    private TextView tvNotificationTimeoutSubtitle;
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

        setupNavigationBarColor();

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

        updateSubtitles();

        findViewById(R.id.btn_custom_rules).setOnClickListener(v -> startActivity(new Intent(SettingsActivity.this, CustomRulesActivity.class)));

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

    private void setupNavigationBarColor() {
        int backgroundColor = getResources().getColor(R.color.background_main, getTheme());
        getWindow().setNavigationBarColor(backgroundColor);

        boolean isLightMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int appearance = isLightMode ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0;
                controller.setSystemBarsAppearance(appearance, WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
