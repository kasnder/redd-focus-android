package net.kollnig.greasemilkyway;

import android.content.Intent;
import android.provider.Settings;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import net.kollnig.distractionlib.ElementPickerOverlay;
import net.kollnig.distractionlib.FilterRule;
import net.kollnig.distractionlib.FrictionGateActivity;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;

public class AppDetailActivity extends AppCompatActivity implements FrictionGateHost {
    public static final String EXTRA_PACKAGE_NAME =
            "net.kollnig.greasemilkyway.extra.PACKAGE_NAME";

    private ServiceConfig config;
    private String packageName;
    private List<FilterRule> rules = new ArrayList<>();
    private AppDetailAdapter adapter;
    private Runnable afterGate;

    private final ActivityResultLauncher<Intent> frictionGateLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Runnable action = afterGate;
                afterGate = null;
                if (result.getResultCode() == RESULT_OK && action != null) {
                    action.run();
                }
                load();
            });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_app_detail);
        NavigationBarHelper.setup(this);
        setupInsets();

        packageName = getIntent().getStringExtra(EXTRA_PACKAGE_NAME);
        if (packageName == null || packageName.isEmpty()) {
            finish();
            return;
        }
        config = new ServiceConfig(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        String appName = AppCatalog.getDisplayName(this, packageName);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(appName);
        }
        ((TextView) findViewById(R.id.app_detail_name)).setText(appName);
        ((ImageView) findViewById(R.id.app_detail_icon))
                .setImageDrawable(AppCatalog.getIcon(this, packageName));

        RecyclerView list = findViewById(R.id.app_detail_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppDetailAdapter(this, config, packageName);
        list.setAdapter(adapter);

        findViewById(R.id.pause_shorter).setOnClickListener(view -> pauseForChip(0));
        findViewById(R.id.pause_default).setOnClickListener(view -> pauseForChip(1));
        findViewById(R.id.pause_longer).setOnClickListener(view -> pauseForChip(2));

        MaterialButton picker = findViewById(R.id.guided_picker);
        picker.setVisibility(getResources().getBoolean(R.bool.show_custom_rules_fab)
                ? View.VISIBLE : View.GONE);
        picker.setOnClickListener(view -> showPickerChoice());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (config != null) {
            load();
        }
    }

    private void load() {
        rules = new ArrayList<>(config.getRules());
        rules.addAll(config.getNavigationRules());
        List<FilterRule> appRules = new ArrayList<>();
        for (FilterRule rule : rules) {
            if (packageName.equals(rule.packageName)) {
                appRules.add(rule);
            }
        }

        long pausedUntil = config.getPackagePausedUntil(packageName);
        boolean paused = pausedUntil > System.currentTimeMillis();
        boolean disabled = config.isPackageDisabled(packageName);
        boolean active = !disabled && !paused;

        MaterialSwitch master = findViewById(R.id.app_detail_switch);
        master.setOnCheckedChangeListener(null);
        master.setChecked(active);
        master.setContentDescription(getString(active
                ? R.string.disable_all_rules_for_app : R.string.enable_all_rules_for_app));
        master.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                config.enablePackageRules(packageName, rules);
                notifyService();
                load();
                return;
            }
            master.setOnCheckedChangeListener(null);
            master.setChecked(true);
            runWithFrictionGate(getString(R.string.disable_app_title,
                    AppCatalog.getDisplayName(this, packageName)), this::showPauseOrDisableDialog);
        });

        TextView state = findViewById(R.id.app_detail_state);
        if (paused) {
            String time = DateFormat.getTimeInstance(DateFormat.SHORT)
                    .format(new Date(pausedUntil));
            state.setText(getString(R.string.app_detail_paused_until, time));
        } else if (disabled) {
            state.setText(R.string.app_detail_off);
        } else {
            state.setText(R.string.app_detail_state);
        }
        int defaultMinutes = config.getPauseDurationMins();
        long[] pauseDurations = PauseChipDurations.aroundDefault(defaultMinutes);
        ((MaterialButton) findViewById(R.id.pause_shorter))
                .setText(formatPauseDuration(pauseDurations[0]));
        ((MaterialButton) findViewById(R.id.pause_default)).setText(
                getString(R.string.pause_chip_default,
                        formatPauseDuration(pauseDurations[1])));
        ((MaterialButton) findViewById(R.id.pause_longer))
                .setText(formatPauseDuration(pauseDurations[2]));
        adapter.setRules(appRules);
    }

    private void showPauseOrDisableDialog() {
        PauseOrDisableDialog.show(this, config,
                () -> {
                    PauseManager.applyPackagePause(this, packageName);
                    load();
                }, () -> {
                    config.setPackageDisabled(packageName, true);
                    config.setPackagePausedUntil(packageName, 0);
                    notifyService();
                    load();
                }, this::load);
    }

    private void showPickerChoice() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.picker_guided_title)
                .setMessage(getString(R.string.picker_guided_message,
                        AppCatalog.getDisplayName(this, packageName)))
                .setPositiveButton(R.string.hide_something_new, (dialog, which) ->
                        startGuidedPicker(EnumSet.of(ElementPickerOverlay.Mode.BLOCK,
                                ElementPickerOverlay.Mode.BLOCK_ALL)))
                .setNegativeButton(R.string.open_place_on_launch, (dialog, which) ->
                        startGuidedPicker(EnumSet.of(ElementPickerOverlay.Mode.NAVIGATE)))
                .show();
    }

    private void startGuidedPicker(EnumSet<ElementPickerOverlay.Mode> modes) {
        DistractionControlService service = DistractionControlService.getInstance();
        if (service == null) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.picker_service_unavailable)
                    .setPositiveButton(R.string.enable_service, (dialog, which) ->
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.picker_app_unavailable)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        service.startPickerMode(packageName, modes);
        startActivity(launch);
    }

    private void pauseForChip(int index) {
        long durationMillis = PauseChipDurations.aroundDefault(
                config.getPauseDurationMins())[index];
        runWithFrictionGate(getString(R.string.pause_app_title), () -> {
            PauseManager.applyPackagePauseUntil(this, packageName,
                    Math.addExact(System.currentTimeMillis(), durationMillis));
            load();
        });
    }

    private String formatPauseDuration(long durationMillis) {
        if (durationMillis < PauseChipDurations.MINUTE_MILLIS) {
            int seconds = (int) (durationMillis / PauseChipDurations.SECOND_MILLIS);
            return getResources().getQuantityString(
                    R.plurals.pause_chip_seconds, seconds, seconds);
        }
        int minutes = (int) (durationMillis / PauseChipDurations.MINUTE_MILLIS);
        if (minutes % 60 == 0) {
            int hours = minutes / 60;
            return getResources().getQuantityString(
                    R.plurals.pause_chip_hours, hours, hours);
        }
        return getResources().getQuantityString(
                R.plurals.pause_chip_minute_value, minutes, minutes);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    public void runWithFrictionGate(String title, Runnable action) {
        if (config.getFrictionWordCount() <= 0) {
            action.run();
            return;
        }
        afterGate = action;
        Intent intent = new Intent(this, FrictionGateActivity.class);
        intent.putExtra(FrictionGateActivity.EXTRA_WORD_COUNT, config.getFrictionWordCount());
        intent.putExtra(FrictionGateActivity.EXTRA_CONTEXT_TITLE, title);
        frictionGateLauncher.launch(intent);
    }

    private void notifyService() {
        DistractionControlService service = DistractionControlService.getInstance();
        if (service != null) {
            service.updateRules();
        }
    }

    private void setupInsets() {
        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets system = insets.getInsets(WindowInsetsCompat.Type.statusBars()
                    | WindowInsetsCompat.Type.navigationBars());
            view.setPadding(view.getPaddingLeft(), system.top,
                    view.getPaddingRight(), system.bottom);
            return insets;
        });
    }
}
