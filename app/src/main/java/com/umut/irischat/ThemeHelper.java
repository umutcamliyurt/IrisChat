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
            "Blue", "Purple", "Pink", "Red", "Orange", "Green", "Teal"
    };
    static final int[] ACCENT_COLORS = {
            0xFF3A76F0,
            0xFF8A56E8,
            0xFFE040FB,
            0xFFE53935,
            0xFFF57C00,
            0xFF2E7D32,
            0xFF00897B,
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