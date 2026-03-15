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
import com.google.android.material.appbar.MaterialToolbar;

public class SettingsActivity extends AppCompatActivity {
    
    private ServiceConfig config;
    private TextView tvFrictionGateSubtitle;
    private TextView tvPauseDurationSubtitle;
    
    private final ActivityResultLauncher<Intent> frictionGateLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    // Passed the gate, reveal settings
                    initViews();
                } else {
                    // Failed or backed out, close settings
                    Toast.makeText(this, "Friction Gate cancelled. Settings locked.", Toast.LENGTH_SHORT).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        config = new ServiceConfig(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        // Hide main content initially if friction gate is active
        findViewById(R.id.main).setVisibility(View.INVISIBLE);

        int requiredWords = config.getFrictionWordCount();
        if (requiredWords > 0) {
            Intent intent = new Intent(this, FrictionGateActivity.class);
            intent.putExtra("WORD_COUNT", requiredWords);
            intent.putExtra("CONTEXT_TITLE", "Unlock Settings");
            frictionGateLauncher.launch(intent);
        } else {
            initViews();
        }
    }

    private void initViews() {
        findViewById(R.id.main).setVisibility(View.VISIBLE);
        
        tvFrictionGateSubtitle = findViewById(R.id.tv_friction_gate_subtitle);
        tvPauseDurationSubtitle = findViewById(R.id.tv_pause_duration_subtitle);
        
        updateSubtitles();

        findViewById(R.id.btn_custom_rules).setOnClickListener(v -> {
            startActivity(new Intent(SettingsActivity.this, CustomRulesActivity.class));
        });

        findViewById(R.id.btn_friction_gate).setOnClickListener(v -> {
            showNumberPickerDialog("Friction Gate Words", "Choose number of words (0-15)", 0, 15, config.getFrictionWordCount(), newValue -> {
                config.setFrictionWordCount(newValue);
                updateSubtitles();
            });
        });

        findViewById(R.id.btn_pause_duration).setOnClickListener(v -> {
            showNumberPickerDialog("Pause Duration", "Choose default pause in minutes (1-120)", 1, 120, config.getPauseDurationMins(), newValue -> {
                config.setPauseDurationMins(newValue);
                updateSubtitles();
            });
        });
    }

    private void updateSubtitles() {
        if (tvFrictionGateSubtitle != null) {
            tvFrictionGateSubtitle.setText(getString(R.string.friction_gate_words, config.getFrictionWordCount()));
        }
        if (tvPauseDurationSubtitle != null) {
            tvPauseDurationSubtitle.setText(getString(R.string.pause_duration_minutes, config.getPauseDurationMins()));
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
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                callback.onNumberPicked(numberPicker.getValue());
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
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
