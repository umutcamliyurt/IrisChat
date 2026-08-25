package com.umut.irischat;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.tabs.TabLayout;

public class ThemeHelper {

    static final String KEY_ACCENT_COLOR = "accent_color";

    private static final String PLAIN_PREFS = "theme_prefs";

    static final String[] ACCENT_LABELS = {
            "Blue", "Indigo", "Purple", "Violet", "Pink", "Magenta", "Red",
            "Crimson", "Orange", "Amber", "Yellow", "Lime", "Green", "Emerald",
            "Teal", "Cyan", "Sky", "Slate", "Brown", "Graphite"
    };
    static final int[] ACCENT_COLORS = {
            0xFF3A76F0, // Blue
            0xFF5C6BC0, // Indigo
            0xFF8A56E8, // Purple
            0xFF9C27B0, // Violet
            0xFFE040FB, // Pink
            0xFFD81B60, // Magenta
            0xFFE53935, // Red
            0xFFB71C1C, // Crimson
            0xFFF57C00, // Orange
            0xFFFFA000, // Amber
            0xFFF9A825, // Yellow
            0xFF9E9D24, // Lime
            0xFF2E7D32, // Green
            0xFF00A86B, // Emerald
            0xFF00897B, // Teal
            0xFF00ACC1, // Cyan
            0xFF0288D1, // Sky
            0xFF546E7A, // Slate
            0xFF6D4C41, // Brown
            0xFF455A64, // Graphite
    };

    static volatile int currentAccent = ACCENT_COLORS[0];

    static int getAccent(CryptoStore crypto) {
        String stored = crypto.getString(KEY_ACCENT_COLOR, null);
        if (stored != null) {
            try { return Color.parseColor(stored); }
            catch (IllegalArgumentException ignored) {}
        }
        return ACCENT_COLORS[0];
    }

    static void saveAccent(Context ctx, CryptoStore crypto, int color) {
        String hex = String.format(java.util.Locale.ROOT, "#%06X", 0xFFFFFF & color);
        crypto.putString(KEY_ACCENT_COLOR, hex);
        ctx.getApplicationContext()
                .getSharedPreferences(PLAIN_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ACCENT_COLOR, hex).apply();
    }

    static int getAccentPreUnlock(Context ctx) {
        String stored = ctx.getApplicationContext()
                .getSharedPreferences(PLAIN_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ACCENT_COLOR, null);
        if (stored != null) {
            try { return Color.parseColor(stored); }
            catch (IllegalArgumentException ignored) {}
        }
        return ACCENT_COLORS[0];
    }

    static void applyToUnlock(Activity activity) {
        int accent = getAccentPreUnlock(activity);
        currentAccent = accent;
        ColorStateList csl = ColorStateList.valueOf(accent);

        ImageView lockIcon = activity.findViewById(R.id.unlockLockIcon);
        if (lockIcon != null) lockIcon.setImageTintList(csl);

        Button unlockButton = activity.findViewById(R.id.unlockButton);
        if (unlockButton != null) unlockButton.setBackgroundTintList(csl);
    }

    static void apply(MainActivity activity, CryptoStore crypto) {
        currentAccent = getAccent(crypto);

        activity.getApplicationContext()
                .getSharedPreferences(PLAIN_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ACCENT_COLOR, String.format(java.util.Locale.ROOT, "#%06X", 0xFFFFFF & currentAccent))
                .apply();

        ColorStateList csl = ColorStateList.valueOf(currentAccent);

        activity.tabLayout.setSelectedTabIndicatorColor(currentAccent);

        activity.sendButton.setBackgroundTintList(csl);

        activity.replyPreviewNick.setTextColor(currentAccent);

        activity.pagerAdapter.notifyAccentChanged();
    }


    static void tintSentBubble(View bubbleFrame) {
        bubbleFrame.setBackgroundTintList(ColorStateList.valueOf(currentAccent));
    }

    static void tintReceivedBubble(TextView nickView,
                                   LinearLayout replyBanner,
                                   TextView replyNickView) {
        nickView.setTextColor(currentAccent);
        if (replyNickView != null) replyNickView.setTextColor(currentAccent);
        if (replyBanner != null && replyBanner.getChildCount() > 0) {
            replyBanner.getChildAt(0).setBackgroundColor(currentAccent);
        }
    }
}