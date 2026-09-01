package com.umut.irischat;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.ref.WeakReference;

public class LogViewerActivity extends AppCompatActivity {

    private static final String[] FILTER_SPEC = {
            "IrcService:V",
            "MainActivity:V",
            "UnlockActivity:V",
            "ServerSwitcherActivity:V",
            "CryptoStore:V",
            "SignalStore:V",
            "AndroidRuntime:E",
            "*:S"
    };

    private TextView   logText;
    private ScrollView logScroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_viewer);

        logText   = findViewById(R.id.logsTextView);
        logScroll = findViewById(R.id.logsScrollView);

        ImageButton refreshBtn = findViewById(R.id.logsRefreshButton);
        ImageButton copyBtn    = findViewById(R.id.logsCopyButton);
        ImageButton shareBtn   = findViewById(R.id.logsShareButton);
        ImageButton clearBtn   = findViewById(R.id.logsClearButton);

        refreshBtn.setOnClickListener(v -> loadLogs());
        copyBtn.setOnClickListener(v -> copyToClipboard());
        shareBtn.setOnClickListener(v -> shareLogs());
        clearBtn.setOnClickListener(v -> clearLogs());

        loadLogs();
    }

    private void loadLogs() {
        logText.setText(R.string.logs_loading);
        new LoadLogsTask(this).execute();
    }

    private void clearLogs() {
        new ClearLogsTask(this).execute();
    }

    private static class LoadLogsTask extends AsyncTask<Void, Void, String> {
        private final WeakReference<LogViewerActivity> activityRef;

        LoadLogsTask(LogViewerActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }

        @Override
        protected String doInBackground(Void... voids) {
            StringBuilder sb = new StringBuilder();
            LogViewerActivity activity = activityRef.get();
            try {
                String[] full = new String[FILTER_SPEC.length + 4];
                full[0] = "logcat";
                full[1] = "-d";
                full[2] = "-v";
                full[3] = "time";
                System.arraycopy(FILTER_SPEC, 0, full, 4, FILTER_SPEC.length);

                Process process = Runtime.getRuntime().exec(full);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                process.waitFor();
            } catch (Exception e) {
                sb.append("Failed to read logs: ").append(e).append('\n');
            }
            if (sb.length() == 0) {
                if (activity != null) {
                    sb.append(activity.getString(R.string.logs_empty_placeholder));
                } else {
                    sb.append("No logs.");
                }
            }
            return sb.toString();
        }

        @Override
        protected void onPostExecute(String result) {
            LogViewerActivity activity = activityRef.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            activity.logText.setText(result);
            activity.logScroll.post(() -> activity.logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }

    private static class ClearLogsTask extends AsyncTask<Void, Void, Void> {
        private final WeakReference<LogViewerActivity> activityRef;

        ClearLogsTask(LogViewerActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }

        @Override
        protected Void doInBackground(Void... voids) {
            try {
                Runtime.getRuntime().exec(new String[]{"logcat", "-c"}).waitFor();
            } catch (Exception ignored) { }
            return null;
        }

        @Override
        protected void onPostExecute(Void unused) {
            LogViewerActivity activity = activityRef.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            Toast.makeText(activity, R.string.logs_cleared, Toast.LENGTH_SHORT).show();
            activity.loadLogs();
        }
    }

    private void copyToClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("IrisChat debug log",
                logText.getText().toString()));
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show();
    }

    private void shareLogs() {
        try {
            File dir = new File(getCacheDir(), "logs");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "irischat_debug_log.txt");
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(file))) {
                w.write(logText.getText().toString());
            }
            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share)));
        } catch (Exception e) {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, logText.getText().toString());
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share)));
        }
    }
}