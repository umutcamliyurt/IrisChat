package com.umut.irischat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public class UnlockActivity extends AppCompatActivity {

    public static CryptoStore cryptoStore;

    private EditText     passwordInput;
    private EditText     confirmInput;
    private TextView     titleView;
    private TextView     subtitleView;
    private LinearLayout confirmLayout;
    private Button       unlockButton;
    private ImageView    toggleVisibility;

    private boolean isFirstRun;
    private boolean pwVisible = false;

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {  }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_unlock);

        cryptoStore = new CryptoStore(this);

        isFirstRun = !cryptoStore.isInitialized();

        titleView        = findViewById(R.id.unlockTitle);
        subtitleView     = findViewById(R.id.unlockSubtitle);
        passwordInput    = findViewById(R.id.unlockPasswordInput);
        confirmInput     = findViewById(R.id.unlockConfirmInput);
        confirmLayout    = findViewById(R.id.unlockConfirmLayout);
        unlockButton     = findViewById(R.id.unlockButton);
        toggleVisibility = findViewById(R.id.toggleUnlockVisibility);

        View rootView = getWindow().getDecorView().findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            androidx.core.graphics.Insets sysBars =
                    insets.getInsets(WindowInsetsCompat.Type.systemBars());
            androidx.core.graphics.Insets ime =
                    insets.getInsets(WindowInsetsCompat.Type.ime());
            int bottomPadding = Math.max(sysBars.bottom, ime.bottom);
            v.setPadding(sysBars.left, sysBars.top, sysBars.right, bottomPadding);
            return WindowInsetsCompat.CONSUMED;
        });

        ThemeHelper.applyToUnlock(this);

        if (isFirstRun) {
            titleView.setText(R.string.create_password_title);
            subtitleView.setText(R.string.create_password_subtitle);
            confirmLayout.setVisibility(View.VISIBLE);
            unlockButton.setText(R.string.set_password);
        } else {
            titleView.setText(R.string.app_name);
            subtitleView.setText(R.string.enter_password_to_unlock);
            confirmLayout.setVisibility(View.GONE);
            unlockButton.setText(R.string.unlock);
        }

        toggleVisibility.setOnClickListener(v -> {
            pwVisible = !pwVisible;
            int inputType = pwVisible
                    ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
            passwordInput.setInputType(inputType);
            confirmInput.setInputType(inputType);
            toggleVisibility.setImageResource(pwVisible
                    ? android.R.drawable.ic_menu_close_clear_cancel
                    : android.R.drawable.ic_menu_view);
            passwordInput.setSelection(passwordInput.getText().length());
        });

        unlockButton.setOnClickListener(v -> attemptUnlock());

        requestNotificationPermission();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void attemptUnlock() {
        String password = passwordInput.getText().toString();

        if (password.isEmpty()) {
            Toast.makeText(this, R.string.password_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        if (isFirstRun) {
            if (password.length() < 4) {
                Toast.makeText(this, R.string.password_too_short, Toast.LENGTH_SHORT).show();
                return;
            }
            String confirm = confirmInput.getText().toString();
            if (!password.equals(confirm)) {
                Toast.makeText(this, R.string.passwords_do_not_match, Toast.LENGTH_SHORT).show();
                confirmInput.requestFocus();
                return;
            }
            try {
                cryptoStore.initPassword(password);
            } catch (Exception e) {
                Toast.makeText(this,
                        getString(R.string.encryption_init_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show();
                return;
            }
            proceed();

        } else {
            unlockButton.setEnabled(false);
            unlockButton.setText(R.string.unlocking);

            new Thread(() -> {
                boolean ok = cryptoStore.unlock(password);
                runOnUiThread(() -> {
                    unlockButton.setEnabled(true);
                    unlockButton.setText(R.string.unlock);
                    if (ok) {
                        proceed();
                    } else {
                        Toast.makeText(this, R.string.incorrect_password, Toast.LENGTH_SHORT).show();
                        passwordInput.selectAll();
                        passwordInput.requestFocus();
                    }
                });
            }).start();
        }
    }

    private void proceed() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}