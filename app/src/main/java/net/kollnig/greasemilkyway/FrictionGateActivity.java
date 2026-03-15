package net.kollnig.greasemilkyway;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class FrictionGateActivity extends AppCompatActivity {

    private static final String[] DICTIONARY = {
        "apple", "forest", "jungle", "ocean", "mountain", "river", "desert", "garden", "bridge", "island",
        "planet", "star", "galaxy", "nebula", "comet", "meteor", "lunar", "solar", "earth", "world",
        "active", "bright", "calm", "dark", "energy", "flow", "growth", "hidden", "inner", "joyful",
        "kind", "light", "magic", "nature", "open", "peace", "quiet", "rare", "soft", "truth",
        "unique", "vibrant", "wisdom", "young", "zeal", "brave", "clear", "dream", "early", "fresh"
    };

    private List<String> words;
    private int currentWordIndex = 0;
    private int wordCount;

    private TextView tvGateTitle;
    private TextView tvProgress;
    private LinearProgressIndicator progressBar;
    private TextView tvFullPhrase;
    private TextView tvCurrentWord;
    private TextInputLayout tilUserInput;
    private TextInputEditText etUserInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_friction_gate);

        wordCount = getIntent().getIntExtra("WORD_COUNT", 5);
        String contextTitle = getIntent().getStringExtra("CONTEXT_TITLE");
        if (contextTitle != null) {
            ((TextView) findViewById(R.id.tv_gate_title)).setText(contextTitle);
        }

        initializeGame();
        initializeViews();
    }

    private void initializeGame() {
        List<String> dictList = new ArrayList<>();
        Collections.addAll(dictList, DICTIONARY);
        Collections.shuffle(dictList);
        
        words = new ArrayList<>();
        for (int i = 0; i < Math.min(wordCount, dictList.size()); i++) {
            words.add(dictList.get(i));
        }
        
        // If wordCount > DICTIONARY size, repeat some words
        Random rand = new Random();
        while (words.size() < wordCount) {
            words.add(dictList.get(rand.nextInt(dictList.size())));
        }
    }

    private void initializeViews() {
        tvGateTitle = findViewById(R.id.tv_gate_title);
        tvProgress = findViewById(R.id.tv_progress);
        progressBar = findViewById(R.id.progress_bar);
        tvFullPhrase = findViewById(R.id.tv_full_phrase);
        tvCurrentWord = findViewById(R.id.tv_current_word);
        tilUserInput = findViewById(R.id.til_user_input);
        etUserInput = findViewById(R.id.et_user_input);

        StringBuilder phraseBuilder = new StringBuilder();
        for (String w : words) {
            phraseBuilder.append(w).append(" ");
        }
        tvFullPhrase.setText(phraseBuilder.toString().trim());

        updateUI();

        etUserInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                checkInput(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        findViewById(R.id.btn_cancel).setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    private void updateUI() {
        tvProgress.setText("Word " + (currentWordIndex + 1) + " of " + wordCount);
        progressBar.setProgress((int) (((float) currentWordIndex / wordCount) * 100));
        tvCurrentWord.setText(words.get(currentWordIndex));
        
        // Reset error state
        tilUserInput.setError(null);
    }

    private void checkInput(String input) {
        String target = words.get(currentWordIndex);
        if (input.equalsIgnoreCase(target)) {
            currentWordIndex++;
            if (currentWordIndex >= wordCount) {
                // Completed!
                setResult(RESULT_OK);
                finish();
            } else {
                etUserInput.setText("");
                updateUI();
            }
        }
    }
}
