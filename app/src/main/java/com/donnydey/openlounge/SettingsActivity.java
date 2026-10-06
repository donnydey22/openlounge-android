package com.donnydey.openlounge;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

public class SettingsActivity extends AppCompatActivity {
    public static final String EXTRA_RELOAD = "reload";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        TextInputEditText input = findViewById(R.id.url_input);
        MaterialButton save = findViewById(R.id.btn_save);

        input.setText(Prefs.getServerUrl(this));

        save.setOnClickListener(v -> {
            String raw = input.getText() != null ? input.getText().toString() : "";
            if (!Prefs.isValidUrl(raw)) {
                Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_LONG).show();
                return;
            }
            Prefs.setServerUrl(this, raw);
            Intent data = new Intent();
            data.putExtra(EXTRA_RELOAD, true);
            setResult(RESULT_OK, data);
            finish();
        });
    }
}
