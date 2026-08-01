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
import android.view.LayoutInflater;
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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import net.kollnig.distractionlib.FilterRule;
import net.kollnig.distractionlib.FilterRuleParser;
import net.kollnig.distractionlib.FrictionGateActivity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CustomRulesActivity extends AppCompatActivity implements FrictionGateHost {
    private static final String PREFS_NAME = "picker_prefs";
    private static final String KEY_PICKER_INTRO_SHOWN = "picker_intro_shown";

    private EditText rulesEditor;
    private View listContainer;
    private View editorContainer;
    private View emptyView;
    private RecyclerView rulesList;
    private ServiceConfig config;
    private CustomRulesAdapter adapter;
    private boolean expertMode;
    private boolean editorHasInvalidText;
    private Runnable pendingFrictionAction;

    private final ActivityResultLauncher<Intent> frictionGateLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                Runnable action = pendingFrictionAction;
                pendingFrictionAction = null;
                if (result.getResultCode() == RESULT_OK && action != null) {
                    action.run();
                } else {
                    reloadRuleList();
                }
            });

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
        NavigationBarHelper.setup(this);
        setupInsets();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.custom_rules_list_title);
        }

        config = new ServiceConfig(this);
        listContainer = findViewById(R.id.custom_rules_list_container);
        editorContainer = findViewById(R.id.custom_rules_editor_container);
        emptyView = findViewById(R.id.custom_rules_empty);
        rulesList = findViewById(R.id.custom_rules_list);
        rulesEditor = findViewById(R.id.rules_editor);

        rulesList.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CustomRulesAdapter(this, config, new CustomRulesAdapter.Listener() {
            @Override
            public void onToggle(List<FilterRule> row, boolean enabled) {
                setRowEnabled(row, enabled);
            }

            @Override
            public void onEdit(List<FilterRule> row) {
                showEditDialog(row);
            }
        });
        rulesList.setAdapter(adapter);

        TextView readmeLink = findViewById(R.id.readme_link);
        if (readmeLink != null) {
            setupReadmeLink(readmeLink);
        }

        View addButton = findViewById(R.id.custom_rules_button);
        if (getResources().getBoolean(R.bool.show_custom_rules_fab)) {
            addButton.setOnClickListener(v -> onAddClicked());
        } else {
            addButton.setVisibility(View.GONE);
        }
        findViewById(R.id.edit_rules_as_text).setOnClickListener(v -> showExpertEditor());
        findViewById(R.id.back_to_rule_list).setOnClickListener(v -> leaveExpertEditor());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (expertMode) {
            if (!editorHasInvalidText) {
                loadEditor();
            }
        } else {
            reloadRuleList();
        }
    }

    @Override
    protected void onPause() {
        if (expertMode) {
            editorHasInvalidText = !saveRules();
        }
        super.onPause();
    }

    private void reloadRuleList() {
        if (config == null || adapter == null) return;
        List<FilterRule> customRules = new ArrayList<>();
        for (FilterRule rule : config.getRules()) {
            if (rule.isCustom) customRules.add(rule);
        }
        for (FilterRule rule : config.getNavigationRules()) {
            if (rule.isCustom) customRules.add(rule);
        }
        customRules.sort(Comparator
                .comparing((FilterRule rule) ->
                        AppCatalog.getDisplayName(this, rule.packageName),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(rule -> rule.description == null ? "" : rule.description,
                        String.CASE_INSENSITIVE_ORDER));

        Map<String, List<FilterRule>> byPackage = new LinkedHashMap<>();
        for (FilterRule rule : customRules) {
            byPackage.computeIfAbsent(rule.packageName, ignored -> new ArrayList<>()).add(rule);
        }
        Map<String, List<List<FilterRule>>> rows = new LinkedHashMap<>();
        for (Map.Entry<String, List<FilterRule>> entry : byPackage.entrySet()) {
            rows.put(entry.getKey(), RuleRows.mergeRules(entry.getValue()));
        }
        adapter.setRules(rows);
        boolean empty = customRules.isEmpty();
        rulesList.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void setRowEnabled(List<FilterRule> row, boolean enabled) {
        if (row.isEmpty()) return;
        if (!enabled && !RuleRows.isNavigationRow(row)) {
            String name = displayName(row);
            runWithFrictionGate(getString(R.string.custom_rule_disable_gate, name),
                    () -> showPauseOrDisable(row));
        } else {
            applyRowEnabled(row, enabled);
        }
    }

    private void showPauseOrDisable(List<FilterRule> row) {
        PauseOrDisableDialog.show(this, config,
                () -> {
                    PauseManager.applyRulePauses(this, row);
                    reloadRuleList();
                }, () -> applyRowEnabled(row, false), this::reloadRuleList);
    }

    private void applyRowEnabled(List<FilterRule> row, boolean enabled) {
        for (FilterRule rule : row) {
            if (rule.isNavigation) {
                config.setNavigationRuleEnabled(rule, enabled);
            } else {
                config.setRuleEnabled(rule, enabled);
                config.setRulePausedUntil(rule, 0);
            }
        }
        notifyService();
        reloadRuleList();
    }

    private void showEditDialog(List<FilterRule> row) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_custom_rule_edit, null);
        EditText nameInput = content.findViewById(R.id.custom_rule_name_input);
        TextView rawText = content.findViewById(R.id.custom_rule_raw_text);
        nameInput.setText(displayName(row));
        List<String> raw = new ArrayList<>();
        for (FilterRule rule : row) raw.add(rule.ruleString);
        rawText.setText(String.join("\n", raw));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.custom_rule_edit_title)
                .setView(content)
                .setPositiveButton(R.string.custom_rule_rename, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String newName = nameInput.getText().toString().trim();
                if (newName.isEmpty()) {
                    nameInput.setError(getString(R.string.custom_rule_name_required));
                    return;
                }
                if (newName.contains("##")) {
                    nameInput.setError(getString(R.string.custom_rule_name_invalid));
                    return;
                }
                renameRow(row, newName);
                dialog.dismiss();
            });
            content.findViewById(R.id.custom_rule_delete_button).setOnClickListener(v -> {
                dialog.dismiss();
                confirmDelete(row);
            });
        });
        dialog.show();
    }

    private void renameRow(List<FilterRule> row, String newName) {
        List<String> blocking = new ArrayList<>();
        List<String> navigation = new ArrayList<>();
        for (FilterRule rule : row) {
            (rule.isNavigation ? navigation : blocking).add(rule.ruleString);
        }
        if (!blocking.isEmpty()) {
            config.renameCustomRules(blocking.toArray(new String[0]), newName);
        }
        if (!navigation.isEmpty()) {
            config.renameCustomNavigationRules(navigation.toArray(new String[0]), newName);
        }
        notifyService();
        reloadRuleList();
        Toast.makeText(this, R.string.custom_rule_renamed, Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete(List<FilterRule> row) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_rule_title)
                .setMessage(R.string.delete_rule_message)
                .setPositiveButton(R.string.delete_rule_confirm, (dialog, which) ->
                        runWithFrictionGate(
                                getString(R.string.custom_rule_delete_gate, displayName(row)),
                                () -> deleteRow(row)))
                .setNegativeButton(R.string.delete_rule_cancel, null)
                .show();
    }

    private void deleteRow(List<FilterRule> row) {
        for (FilterRule rule : row) {
            if (rule.isNavigation) {
                config.removeCustomNavigationRule(rule.ruleString);
            } else {
                config.removeCustomRule(rule.ruleString);
            }
        }
        notifyService();
        reloadRuleList();
        Toast.makeText(this, R.string.rule_deleted, Toast.LENGTH_SHORT).show();
    }

    private String displayName(List<FilterRule> row) {
        FilterRule primary = row.get(0);
        if (primary.description == null || primary.description.trim().isEmpty()) {
            return getString(R.string.rule_custom_fallback);
        }
        return primary.description.trim();
    }

    private void showExpertEditor() {
        expertMode = true;
        editorHasInvalidText = false;
        listContainer.setVisibility(View.GONE);
        editorContainer.setVisibility(View.VISIBLE);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.custom_rules_title);
        }
        loadEditor();
    }

    private void leaveExpertEditor() {
        if (!saveRules()) {
            editorHasInvalidText = true;
            return;
        }
        editorHasInvalidText = false;
        expertMode = false;
        editorContainer.setVisibility(View.GONE);
        listContainer.setVisibility(View.VISIBLE);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.custom_rules_list_title);
        }
        reloadRuleList();
    }

    private void loadEditor() {
        String[] customRules = config.getCustomRules();
        rulesEditor.setText(customRules == null ? "" : String.join("\n", customRules));
    }

    private boolean saveRules() {
        String[] rawLines = rulesEditor.getText().toString().split("\n");
        List<String> filtered = new ArrayList<>();
        int expectedRules = 0;
        for (String line : rawLines) {
            if (!line.trim().isEmpty()) {
                filtered.add(line);
                if (!line.trim().startsWith("//")) expectedRules++;
            }
        }
        String[] rules = filtered.toArray(new String[0]);
        if (new FilterRuleParser().parseRules(rules).size() != expectedRules) {
            Toast.makeText(this, R.string.invalid_rules, Toast.LENGTH_LONG).show();
            return false;
        }
        config.saveCustomRules(rules);
        notifyService();
        return true;
    }

    @Override
    public void runWithFrictionGate(String contextTitle, Runnable action) {
        int wordCount = config.getFrictionWordCount();
        if (wordCount <= 0) {
            action.run();
            return;
        }
        pendingFrictionAction = action;
        Intent intent = new Intent(this, FrictionGateActivity.class);
        intent.putExtra(FrictionGateActivity.EXTRA_WORD_COUNT, wordCount);
        intent.putExtra(FrictionGateActivity.EXTRA_CONTEXT_TITLE, contextTitle);
        frictionGateLauncher.launch(intent);
    }

    private void onAddClicked() {
        boolean introShown = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_PICKER_INTRO_SHOWN, false);
        if (!introShown) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.picker_intro_title)
                    .setMessage(R.string.picker_intro_message)
                    .setPositiveButton(R.string.picker_intro_enable,
                            (dialog, which) -> requestNotificationPermissionAndShow())
                    .setNegativeButton(R.string.picker_intro_cancel, null)
                    .show();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermissionAndShow();
        } else {
            showPickerNotification();
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
        new ElementPickerNotification(this).showNotification();
        Toast.makeText(this, R.string.picker_notification_shown, Toast.LENGTH_SHORT).show();
    }

    private void notifyService() {
        DistractionControlService service = DistractionControlService.getInstance();
        if (service != null) service.updateRules();
    }

    private void setupReadmeLink(TextView textView) {
        String fullText = getString(R.string.custom_rules_readme_link);
        String linkText = "Custom Rules README";
        SpannableString text = new SpannableString(fullText);
        int start = fullText.indexOf(linkText);
        if (start >= 0) {
            text.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/kasnder/GreaseMilkyway/blob/main/docs/CUSTOM_RULES.md")));
                }
            }, start, start + linkText.length(), SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        textView.setText(text);
        textView.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private void setupInsets() {
        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets system = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.navigationBars());
            view.setPadding(view.getPaddingLeft(), system.top,
                    view.getPaddingRight(), system.bottom);
            return insets;
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            if (expertMode) {
                leaveExpertEditor();
            } else {
                onBackPressed();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
