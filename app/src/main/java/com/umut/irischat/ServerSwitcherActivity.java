package com.umut.irischat;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.app.Dialog;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ServerSwitcherActivity extends AppCompatActivity {

    public static final String EXTRA_SERVER_JSON    = "extra_server_json";
    public static final String EXTRA_DELETE_NAME    = "extra_delete_name";
    public static final String EXTRA_IS_DELETE      = "extra_is_delete";

    static final String CRYPTO_KEY_SERVERS = "saved_servers";
    private static final String KEY_LANGUAGE = "app_language";

    private ListView          serverListView;
    private Button            addServerButton;
    private List<Server>      servers;
    private ServerListAdapter adapter;

    private CryptoStore crypto;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        super.onCreate(savedInstanceState);

        crypto = UnlockActivity.cryptoStore;
        if (crypto == null || !crypto.isUnlocked()) {
            startActivity(new Intent(this, UnlockActivity.class));
            finish();
            return;
        }

        applyLocale(false);
        setContentView(R.layout.activity_server_switcher);

        serverListView  = findViewById(R.id.serverListView);
        addServerButton = findViewById(R.id.addServerButton);

        View root = getWindow().getDecorView().findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars =
                    insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        findViewById(R.id.settingsButton).setOnClickListener(v -> showSettingsDialog());

        servers = loadServers();
        adapter = new ServerListAdapter(this, servers);
        serverListView.setAdapter(adapter);

        serverListView.setOnItemClickListener((p, v, pos, id) ->
                returnServer(servers.get(pos)));

        serverListView.setOnItemLongClickListener((p, v, pos, id) -> {
            showEditDeleteDialog(pos);
            return true;
        });

        addServerButton.setOnClickListener(v -> showAddEditSheet(null, -1));

        applyAccent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyAccent();
    }

    private void applyAccent() {
        int accent = ThemeHelper.getAccent(crypto);
        ThemeHelper.currentAccent = accent;
        addServerButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(accent));
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void returnServer(Server server) {
        try {
            Intent result = new Intent();
            result.putExtra(EXTRA_SERVER_JSON, server.toJson().toString());
            setResult(RESULT_OK, result);
            finish();
        } catch (JSONException e) {
            Toast.makeText(this, R.string.error_selecting_server, Toast.LENGTH_SHORT).show();
        }
    }

    private void returnDelete(String serverName) {
        Intent result = new Intent();
        result.putExtra(EXTRA_DELETE_NAME, serverName);
        result.putExtra(EXTRA_IS_DELETE, true);
        setResult(RESULT_OK, result);
        finish();
    }

    private void showAddEditSheet(Server existing, int editIndex) {
        ServerEditSheet sheet = ServerEditSheet.newInstance(existing, editIndex);
        sheet.setListener((server, idx) -> {
            if (idx >= 0) {
                servers.set(idx, server);
            } else {
                servers.add(server);
            }
            saveServers();
            adapter.notifyDataSetChanged();
        });
        sheet.show(getSupportFragmentManager(), "server_edit");
    }

    private void showEditDeleteDialog(int index) {
        new AlertDialog.Builder(this, R.style.IrisDialog)
                .setTitle(servers.get(index).getName())
                .setItems(new String[]{
                        getString(R.string.edit),
                        getString(R.string.delete)
                }, (dialog, which) -> {
                    if (which == 0) {
                        showAddEditSheet(servers.get(index), index);
                    } else {
                        String name = servers.get(index).getName();
                        new AlertDialog.Builder(this, R.style.IrisDialog)
                                .setTitle(R.string.delete)
                                .setMessage(getString(R.string.confirm_delete_server, name))
                                .setPositiveButton(R.string.delete, (d2, w2) -> {
                                    servers.remove(index);
                                    saveServers();
                                    adapter.notifyDataSetChanged();
                                    returnDelete(name);
                                })
                                .setNegativeButton(R.string.cancel, null)
                                .show();
                    }
                })
                .show();
    }

    private void showSettingsDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);

        int currentAccent = ThemeHelper.getAccent(crypto);

        androidx.appcompat.widget.SwitchCompat switchDmAd =
                view.findViewById(R.id.switchDmAdvertisement);
        switchDmAd.setChecked("true".equals(
                crypto.getString(MainActivity.KEY_DM_ADVERTISEMENT, null)));
        switchDmAd.setOnCheckedChangeListener((btn, checked) ->
                crypto.putString(MainActivity.KEY_DM_ADVERTISEMENT, checked ? "true" : "false"));
        tintSwitch(this, switchDmAd, currentAccent);

        android.widget.LinearLayout swatchRow = view.findViewById(R.id.colorSwatchRow);
        int swatchSizePx   = (int) (36 * getResources().getDisplayMetrics().density);
        int swatchMarginPx = (int) (8  * getResources().getDisplayMetrics().density);
        int strokePx       = (int) (3  * getResources().getDisplayMetrics().density);

        for (int i = 0; i < ThemeHelper.ACCENT_COLORS.length; i++) {
            final int color = ThemeHelper.ACCENT_COLORS[i];
            final String label = ThemeHelper.ACCENT_LABELS[i];

            android.widget.FrameLayout swatch = new android.widget.FrameLayout(this);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(swatchSizePx, swatchSizePx);
            lp.setMargins(0, 0, swatchMarginPx, 0);
            swatch.setLayoutParams(lp);

            android.graphics.drawable.GradientDrawable circle =
                    new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(color);
            if (color == currentAccent) {
                circle.setStroke(strokePx, 0xFFFFFFFF);
            }
            swatch.setBackground(circle);
            swatch.setContentDescription(label);

            swatch.setOnClickListener(v -> {
                ThemeHelper.saveAccent(this, crypto, color);
                for (int j = 0; j < swatchRow.getChildCount(); j++) {
                    android.widget.FrameLayout s =
                            (android.widget.FrameLayout) swatchRow.getChildAt(j);
                    android.graphics.drawable.GradientDrawable d =
                            (android.graphics.drawable.GradientDrawable) s.getBackground();
                    d.setStroke(ThemeHelper.ACCENT_COLORS[j] == color ? strokePx : 0,
                            0xFFFFFFFF);
                }
            });

            swatchRow.addView(swatch);
        }

        Button btnEn = view.findViewById(R.id.btnLangEnglish);
        Button btnTr = view.findViewById(R.id.btnLangTurkish);
        Button btnRu = view.findViewById(R.id.btnLangRussian);

        String currentLang = crypto.getString(KEY_LANGUAGE, "en");
        if (currentLang == null || currentLang.isEmpty()) currentLang = "en";
        updateLangButtons(btnEn, btnTr, btnRu, currentLang);

        final String[] selectedLang = {currentLang};
        btnEn.setOnClickListener(v -> {
            selectedLang[0] = "en";
            crypto.putString(KEY_LANGUAGE, "en");
            updateLangButtons(btnEn, btnTr, btnRu, "en");
        });
        btnTr.setOnClickListener(v -> {
            selectedLang[0] = "tr";
            crypto.putString(KEY_LANGUAGE, "tr");
            updateLangButtons(btnEn, btnTr, btnRu, "tr");
        });
        btnRu.setOnClickListener(v -> {
            selectedLang[0] = "ru";
            crypto.putString(KEY_LANGUAGE, "ru");
            updateLangButtons(btnEn, btnTr, btnRu, "ru");
        });

        Button btnChangePassword = view.findViewById(R.id.btnChangePassword);
        btnChangePassword.setTextColor(currentAccent);
        if (btnChangePassword instanceof MaterialButton) {
            ((MaterialButton) btnChangePassword).setStrokeColor(ColorStateList.valueOf(currentAccent));
        }
        btnChangePassword.setOnClickListener(v -> showChangePasswordDialog());

        TextView versionText    = view.findViewById(R.id.settingsVersionText);
        versionText.setText(getString(R.string.app_version_label, getAppVersionName()));

        AlertDialog settingsDialog = new AlertDialog.Builder(this, R.style.IrisDialog)
                .setTitle(R.string.settings)
                .setView(view)
                .setPositiveButton(R.string.settings_done, (d, w) -> {
                    String activeLang = getResources().getConfiguration()
                            .getLocales().get(0).getLanguage();
                    if (!selectedLang[0].equals(activeLang)) {
                        applyLocale(true);
                    }
                })
                .create();
        settingsDialog.setOnShowListener(d -> {
            settingsDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(currentAccent);
            Window window = settingsDialog.getWindow();
            if (window != null) {
                window.setLayout(addServerButton.getWidth(), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
        settingsDialog.show();
    }

    private String getAppVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName != null ? info.versionName : "";
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private void showChangePasswordDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_change_password, null);

        EditText currentInput = view.findViewById(R.id.inputCurrentPassword);
        EditText newInput     = view.findViewById(R.id.inputNewPassword);
        EditText confirmInput = view.findViewById(R.id.inputConfirmPassword);

        setupPasswordToggle(view.findViewById(R.id.toggleCurrentPasswordVisibility), currentInput);
        setupPasswordToggle(view.findViewById(R.id.toggleNewPasswordVisibility), newInput);
        setupPasswordToggle(view.findViewById(R.id.toggleConfirmPasswordVisibility), confirmInput);

        int accent = ThemeHelper.getAccent(crypto);

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.IrisDialog)
                .setTitle(R.string.change_password_title)
                .setView(view)
                .setPositiveButton(R.string.change_password, null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> {
            Button changeButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);

            changeButton.setTextColor(enabledStateTextColor(accent));
            cancelButton.setTextColor(accent);

            changeButton.setOnClickListener(v -> {
                String current = currentInput.getText().toString();
                String next    = newInput.getText().toString();
                String confirm = confirmInput.getText().toString();

                if (current.isEmpty() || next.isEmpty()) {
                    Toast.makeText(this, R.string.password_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (next.length() < 4) {
                    Toast.makeText(this, R.string.password_too_short, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!next.equals(confirm)) {
                    Toast.makeText(this, R.string.passwords_do_not_match, Toast.LENGTH_SHORT).show();
                    confirmInput.requestFocus();
                    return;
                }

                changeButton.setEnabled(false);
                cancelButton.setEnabled(false);
                changeButton.setText(R.string.changing_password);

                new Thread(() -> {
                    boolean ok = crypto.changePassword(current, next);
                    runOnUiThread(() -> {
                        changeButton.setEnabled(true);
                        cancelButton.setEnabled(true);
                        changeButton.setText(R.string.change_password);
                        if (ok) {
                            Toast.makeText(this, R.string.password_changed, Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                        } else {
                            Toast.makeText(this, R.string.incorrect_current_password,
                                    Toast.LENGTH_SHORT).show();
                            currentInput.selectAll();
                            currentInput.requestFocus();
                        }
                    });
                }).start();
            });
        });

        dialog.show();
    }

    private static void tintSwitch(Context ctx, SwitchCompat sw, int accent) {
        int uncheckedThumb = ContextCompat.getColor(ctx, R.color.text_secondary);
        int uncheckedTrack = ContextCompat.getColor(ctx, R.color.divider);
        int checkedTrack   = Color.argb(140,
                Color.red(accent), Color.green(accent), Color.blue(accent));

        ColorStateList thumbStates = new ColorStateList(
                new int[][] {
                        new int[] { android.R.attr.state_checked },
                        new int[] {}
                },
                new int[] { accent, uncheckedThumb }
        );
        ColorStateList trackStates = new ColorStateList(
                new int[][] {
                        new int[] { android.R.attr.state_checked },
                        new int[] {}
                },
                new int[] { checkedTrack, uncheckedTrack }
        );

        sw.setThumbTintList(thumbStates);
        sw.setTrackTintList(trackStates);
    }

    private ColorStateList enabledStateTextColor(int accent) {
        int disabled = Color.argb(
                Math.round(Color.alpha(accent) * 0.4f),
                Color.red(accent), Color.green(accent), Color.blue(accent));
        return new ColorStateList(
                new int[][] {
                        new int[] { -android.R.attr.state_enabled },
                        new int[] {}
                },
                new int[] { disabled, accent }
        );
    }

    private void setupPasswordToggle(ImageView toggle, EditText input) {
        final boolean[] visible = {false};
        toggle.setOnClickListener(v -> {
            visible[0] = !visible[0];
            input.setInputType(visible[0]
                    ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            toggle.setImageResource(visible[0]
                    ? android.R.drawable.ic_menu_close_clear_cancel
                    : android.R.drawable.ic_menu_view);
            input.setSelection(input.getText().length());
        });
    }

    private void applyLocale(boolean restart) {
        String lang = crypto != null ? crypto.getString(KEY_LANGUAGE, "en") : "en";
        if (lang == null || lang.isEmpty()) lang = "en";
        Locale locale = new Locale(lang);
        Locale.setDefault(locale);
        Configuration config = new Configuration(getResources().getConfiguration());
        config.setLocale(locale);
        getResources().updateConfiguration(config, getResources().getDisplayMetrics());
        if (restart) {
            Intent intent = getIntent();
            finish();
            startActivity(intent);
        }
    }

    private void updateLangButtons(Button btnEn, Button btnTr, Button btnRu, String lang) {
        int accent = ThemeHelper.getAccent(crypto);
        android.content.res.ColorStateList accentList =
                android.content.res.ColorStateList.valueOf(accent);
        android.content.res.ColorStateList transparent =
                android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT);

        for (Button btn : new Button[]{btnEn, btnTr, btnRu}) {
            btn.setBackgroundTintList(transparent);
            btn.setTextColor(accent);
        }
        Button active = "tr".equals(lang) ? btnTr : "ru".equals(lang) ? btnRu : btnEn;
        active.setBackgroundTintList(accentList);
        active.setTextColor(0xFFFFFFFF);
    }

    private List<Server> loadServers() {
        List<Server> list = new ArrayList<>();
        String raw = crypto.getString(CRYPTO_KEY_SERVERS, null);
        if (raw == null) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                try {
                    list.add(Server.fromJson(arr.getJSONObject(i)));
                } catch (JSONException ignored) {}
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return list;
    }

    private void saveServers() {
        JSONArray arr = new JSONArray();
        for (Server s : servers) {
            try { arr.put(s.toJson()); } catch (JSONException ignored) {}
        }
        crypto.putString(CRYPTO_KEY_SERVERS, arr.toString());
    }

    private static class ServerListAdapter extends ArrayAdapter<Server> {
        ServerListAdapter(Context ctx, List<Server> items) {
            super(ctx, R.layout.item_server, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null)
                convertView = LayoutInflater.from(getContext())
                        .inflate(R.layout.item_server, parent, false);

            Server s = getItem(position);
            String initial = s.getName().isEmpty() ? "#"
                    : String.valueOf(s.getName().charAt(0)).toUpperCase();
            TextView initialView = convertView.findViewById(R.id.serverInitial);
            initialView.setText(initial);
            initialView.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(ThemeHelper.currentAccent));
            ((TextView) convertView.findViewById(R.id.serverName)).setText(s.getName());
            convertView.findViewById(R.id.serverDetail).setVisibility(View.GONE);
            convertView.findViewById(R.id.lockIcon).setVisibility(View.GONE);
            return convertView;
        }
    }

    public interface OnServerSavedListener {
        void onSaved(Server server, int editIndex);
    }

    public static class ServerEditSheet extends BottomSheetDialogFragment {

        private static final String ARG_SERVER_JSON = "server_json";
        private static final String ARG_EDIT_INDEX  = "edit_index";

        private OnServerSavedListener listener;

        public static ServerEditSheet newInstance(Server existing, int editIndex) {
            ServerEditSheet f = new ServerEditSheet();
            Bundle args = new Bundle();
            args.putInt(ARG_EDIT_INDEX, editIndex);
            if (existing != null) {
                try { args.putString(ARG_SERVER_JSON, existing.toJson().toString()); }
                catch (JSONException ignored) {}
            }
            f.setArguments(args);
            return f;
        }

        public void setListener(OnServerSavedListener l) { this.listener = l; }

        @NonNull
        @Override
        public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
            dialog.setOnShowListener(d -> {
                BottomSheetDialog bsd = (BottomSheetDialog) d;
                View sheet = bsd.findViewById(com.google.android.material.R.id.design_bottom_sheet);
                if (sheet != null) {
                    sheet.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
                    BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
                    behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                    behavior.setSkipCollapsed(true);
                }
            });
            return dialog;
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater,
                                 @Nullable ViewGroup container,
                                 @Nullable Bundle savedInstanceState) {
            return inflater.inflate(R.layout.dialog_server_edit, container, false);
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);

            int editIndex = getArguments() != null ? getArguments().getInt(ARG_EDIT_INDEX, -1) : -1;
            String existingJson = getArguments() != null
                    ? getArguments().getString(ARG_SERVER_JSON, null) : null;
            Server existing = null;
            if (existingJson != null) {
                try { existing = Server.fromJson(new JSONObject(existingJson)); }
                catch (JSONException ignored) {}
            }

            EditText     nameInput          = view.findViewById(R.id.inputServerName);
            EditText     hostInput          = view.findViewById(R.id.inputHost);
            EditText     portInput          = view.findViewById(R.id.inputPort);
            EditText     nicknameInput      = view.findViewById(R.id.inputNickname);
            EditText     channelInput       = view.findViewById(R.id.inputChannel);
            EditText     passwordInput      = view.findViewById(R.id.inputPassword);
            ImageView    togglePassword     = view.findViewById(R.id.togglePasswordVisibility);
            SwitchCompat tlsSwitch          = view.findViewById(R.id.switchTls);
            SwitchCompat saslSwitch         = view.findViewById(R.id.switchSasl);
            View         saslFields         = view.findViewById(R.id.saslFields);
            EditText     saslLoginInput     = view.findViewById(R.id.inputSaslLogin);
            EditText     saslPasswordInput  = view.findViewById(R.id.inputSaslPassword);
            ImageView    toggleSaslPassword = view.findViewById(R.id.toggleSaslPasswordVisibility);
            Button       saveButton         = view.findViewById(R.id.btnSave);
            Button       cancelButton       = view.findViewById(R.id.btnCancel);
            TextView     titleView          = view.findViewById(R.id.sheetTitle);

            titleView.setText(existing == null ? R.string.add_server_title : R.string.edit_server_title);

            tintSwitch(requireContext(), tlsSwitch, ThemeHelper.currentAccent);
            tintSwitch(requireContext(), saslSwitch, ThemeHelper.currentAccent);

            tlsSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
                String p = portInput.getText().toString().trim();
                if (p.equals("6667") || p.equals("6697") || p.isEmpty())
                    portInput.setText(isChecked ? "6697" : "6667");
            });

            final boolean[] pwVisible = {false};
            togglePassword.setOnClickListener(v -> {
                pwVisible[0] = !pwVisible[0];
                passwordInput.setInputType(pwVisible[0]
                        ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                togglePassword.setImageResource(pwVisible[0]
                        ? android.R.drawable.ic_menu_close_clear_cancel
                        : android.R.drawable.ic_menu_view);
                passwordInput.setSelection(passwordInput.getText().length());
            });

            saslSwitch.setOnCheckedChangeListener((btn, checked) ->
                    saslFields.setVisibility(checked ? View.VISIBLE : View.GONE));

            final boolean[] saslPwVisible = {false};
            toggleSaslPassword.setOnClickListener(v -> {
                saslPwVisible[0] = !saslPwVisible[0];
                saslPasswordInput.setInputType(saslPwVisible[0]
                        ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                toggleSaslPassword.setImageResource(saslPwVisible[0]
                        ? android.R.drawable.ic_menu_close_clear_cancel
                        : android.R.drawable.ic_menu_view);
                saslPasswordInput.setSelection(saslPasswordInput.getText().length());
            });

            if (existing != null) {
                nameInput.setText(existing.getName());
                hostInput.setText(existing.getHost());
                portInput.setText(String.valueOf(existing.getPort()));
                nicknameInput.setText(existing.getNickname());
                channelInput.setText(existing.getChannelsAsString());
                passwordInput.setText(existing.getPassword());
                tlsSwitch.setChecked(existing.isTls());
                boolean hasSasl = existing.hasSasl();
                saslSwitch.setChecked(hasSasl);
                saslFields.setVisibility(hasSasl ? View.VISIBLE : View.GONE);
                saslLoginInput.setText(existing.getSaslLogin());
                saslPasswordInput.setText(existing.getSaslPassword());
            } else {
                portInput.setText("6697");
                tlsSwitch.setChecked(true);
            }

            cancelButton.setOnClickListener(v -> dismiss());

            saveButton.setOnClickListener(v -> {
                String name     = nameInput.getText().toString().trim();
                String host     = hostInput.getText().toString().trim();
                String portStr  = portInput.getText().toString().trim();
                String nickname = nicknameInput.getText().toString().trim();
                String chanRaw  = channelInput.getText().toString().trim();
                String password = passwordInput.getText().toString();
                boolean tls     = tlsSwitch.isChecked();
                boolean sasl    = saslSwitch.isChecked();
                String saslLogin    = saslLoginInput.getText().toString().trim();
                String saslPassword = saslPasswordInput.getText().toString();

                if (name.isEmpty() || host.isEmpty() || portStr.isEmpty()
                        || nickname.isEmpty() || chanRaw.isEmpty()) {
                    Toast.makeText(getContext(), R.string.all_fields_required,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (sasl && (saslLogin.isEmpty() || saslPassword.isEmpty())) {
                    Toast.makeText(getContext(), R.string.sasl_fields_required,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                int port;
                try {
                    port = Integer.parseInt(portStr);
                    if (port < 1 || port > 65535) throw new NumberFormatException("out of range");
                } catch (NumberFormatException e) {
                    Toast.makeText(getContext(), R.string.invalid_port, Toast.LENGTH_SHORT).show();
                    return;
                }
                List<String> channels = Server.parseChannels(chanRaw);
                if (channels.isEmpty()) {
                    Toast.makeText(getContext(), R.string.enter_at_least_one_channel,
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                Server s = new Server(name, host, port, nickname, channels, password, tls,
                        sasl ? saslLogin : "", sasl ? saslPassword : "");
                if (listener != null) listener.onSaved(s, editIndex);
                dismiss();
            });
        }
    }
}