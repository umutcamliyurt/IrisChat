package com.umut.irischat;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String KEY_SAVED_SERVERS    = "saved_servers";
    private static final String KEY_SAVED_DMS        = "saved_dms";
    private static final String KEY_UNREAD_COUNTS    = "unread_counts";
    static final String KEY_DM_ADVERTISEMENT = "dm_advertisement_enabled";

    private static final int MAX_MSG_LENGTH = 400;
    private static final long CONNECTION_FAILED_TOAST_DURATION_MS = 10_000L;
    private static final long CONNECTION_FAILED_TOAST_REFRESH_MS  = 3_000L;

    private android.widget.Toast connectionErrorToast;
    private final android.os.Handler connectionErrorToastHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable connectionErrorToastRunnable;

    private final Map<String, List<Long>> chatRowIds = new LinkedHashMap<>();

    private MessageDatabase msgDb;

    private static final java.util.regex.Pattern IMAGE_URL_PATTERN =
            java.util.regex.Pattern.compile(
                    "https?://\\S+\\.(?:jpg|jpeg|png|gif|webp)(\\?\\S*)?",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    public static String extractImageUrl(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = IMAGE_URL_PATTERN.matcher(text);
        if (!m.find()) return null;
        String url = m.group();
        if (!url.toLowerCase(java.util.Locale.ROOT).startsWith("http://")
                && !url.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
            return null;
        }
        return url;
    }

    private static final java.util.regex.Pattern QUOTE_REPLY_PATTERN =
            java.util.regex.Pattern.compile("^> <([^>]+)>\\s?(.*)$");

    private static String[] parseQuoteReply(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = QUOTE_REPLY_PATTERN.matcher(text);
        if (!m.matches()) return null;
        return new String[]{m.group(1), m.group(2)};
    }

    private static boolean isValidServerName(String name) {
        if (name == null || name.isEmpty() || name.length() > 100) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c < 0x20 || c == 0x7F) return false;
        }
        return true;
    }

    private static final java.util.regex.Pattern VALID_NICK =
            java.util.regex.Pattern.compile(
                    "^[A-Za-z_\\[\\]\\\\^{}|][A-Za-z0-9_\\-\\[\\]\\\\^{}|]{0,49}$");

    static boolean isValidNick(String nick) {
        return nick != null && VALID_NICK.matcher(nick).matches();
    }

    private TextView    statusLabel;
    private ImageButton addServerButton;
    ImageButton sendButton;
    private ImageButton membersButton;
    private ImageButton discoverButton;
    private EditText    chatInput;
    TabLayout   tabLayout;
    private ViewPager2  viewPager;

    private String focusedServerName = null;
    private android.widget.LinearLayout serverLabelRow;
    private int defaultServerLabelColor;
    private View        replyPreviewBar;
    TextView    replyPreviewNick, replyPreviewText;
    private View        replyCancelBtn;

    final List<String>                   tabKeys     = new ArrayList<>();
    final Map<String, List<ChatMessage>> chatLogs    = new LinkedHashMap<>();
    final Map<String, Server>            knownServers = new LinkedHashMap<>();
    final Object stateLock = new Object();

    final Map<String, Integer> unreadCounts = new LinkedHashMap<>();
    private volatile String  visibleTabKey     = null;
    private volatile boolean isAppInForeground = false;

    private final android.os.Handler saveHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long SAVE_DEBOUNCE_MS = 500;
    private final Runnable saveRunnable = this::saveHistoryNow;

    private ChatMessage pendingReply        = null;
    private String      pendingReplyTabKey  = null;
    ChannelPagerAdapter pagerAdapter;

    private CryptoStore crypto;
    private SignalStore  signalStore;

    private final java.util.Set<String> dmGreetingSent =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private final Map<String, List<String>> channelMembers =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>());

    private final Map<String, String[]> pendingIrcQuoteReplies =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>());

    private IrcService ircService;
    private boolean    serviceBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            ircService   = ((IrcService.LocalBinder) binder).getService();
            serviceBound = true;

            ircService.setListener(new IrcService.Listener() {
                @Override public void onConnected(String serverName) {
                    runOnUiThread(() -> {
                        refreshStatusBar();
                        for (String key : new ArrayList<>(tabKeys)) {
                            if (!isDmTab(key)) continue;
                            int sl = key.indexOf('/');
                            if (sl < 0) continue;
                            String srv  = key.substring(0, sl);
                            String nick = key.substring(sl + 1);
                            if (!srv.equals(serverName)) continue;

                            assert key.equals(IrcService.dbTabKey(srv, nick));

                            ircService.requestHistory(serverName, nick);
                            if (signalStore != null && signalStore.hasIdentity()) {
                                announceKeyTo(serverName, nick);
                            }
                        }
                    });
                }
                @Override public void onDisconnected(String serverName) {
                    runOnUiThread(() -> refreshStatusBar());
                }
                @Override public void onConnectionFailed(String serverName) {
                    runOnUiThread(() -> showConnectionFailedToast(serverName, null));
                }
                @Override public void onConnectionFailed(String serverName, String errorDetail) {
                    runOnUiThread(() -> showConnectionFailedToast(serverName, errorDetail));

                    if (errorDetail != null) {
                        String key = currentTabKeyForServer(serverName);
                        if (key != null) {
                            appendToTab(key, new ChatMessage(null,
                                    "Connection failed: " + errorDetail,
                                    ChatMessage.Type.SYSTEM));
                        }
                    }
                }
                @Override public void onMessage(String serverName, String channel,
                                                String nick, String text, String imageUrl,
                                                String msgId, long timestampMs) {
                    String key = tabKey(serverName, channel);

                    String pendingMapKey = key + "\u0000" + nick;
                    String[] quote = parseQuoteReply(text);
                    if (quote != null) {
                        pendingIrcQuoteReplies.put(pendingMapKey, quote);
                        return;
                    }

                    String replyNick = null;
                    String replyText = null;
                    String[] pending = pendingIrcQuoteReplies.remove(pendingMapKey);
                    if (pending != null) {
                        replyNick = pending[0];
                        replyText = pending[1];
                    }

                    if (isDmTab(key) && signalStore.hasIdentity()
                            && SignalStore.isSignalMessage(text)) {
                        new Thread(() -> {
                            try {
                                SignalStore.DecryptResult result = signalStore.receiveMsgChunk(nick, text);
                                if (result == null) return;
                                appendToTab(key, new ChatMessage(nick, result.plaintext,
                                        ChatMessage.Type.RECEIVED, null, null, null, true,
                                        result.rawWire, timestampMs, msgId));
                                runOnUiThread(() -> refreshTabLabel(key));
                            } catch (Exception e) {
                                android.util.Log.w("MainActivity",
                                        "Decrypt error from " + nick + ": " + e.getMessage());
                                appendToTab(key, new ChatMessage(nick,
                                        getString(R.string.encrypted_message_failed),
                                        ChatMessage.Type.RECEIVED, null, null, null, true,
                                        text, timestampMs, msgId));
                                runOnUiThread(() -> refreshTabLabel(key));
                            }
                        }, "signal-recv").start();
                        return;
                    }
                    appendToTab(key, new ChatMessage(nick, text,
                            ChatMessage.Type.RECEIVED, replyNick, replyText, imageUrl,
                            false, null, timestampMs, msgId));
                    if (isDmTab(key)) runOnUiThread(() -> refreshTabLabel(key));
                }
                @Override public void onNotice(String serverName, String fromNick, String text) {
                    if (!SignalStore.isKeyAnnouncement(text)) {
                        String key = currentTabKeyForServer(serverName);
                        String display = "-" + fromNick + "- " + text;
                        if (key != null) {
                            appendToTab(key, new ChatMessage(null, display, ChatMessage.Type.SYSTEM));
                        } else {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, display, Toast.LENGTH_LONG).show());
                        }
                        return;
                    }
                    if (!isValidNick(fromNick)) {
                        android.util.Log.w("MainActivity",
                                "onNotice: ignoring key announcement from invalid nick");
                        return;
                    }

                    new Thread(() -> {
                        try {
                            byte[] bundle = signalStore.receiveChunk(fromNick, text);
                            if (bundle == null) return;
                            if (bundle.length == 0) return;

                            String dmKey = tabKey(serverName, fromNick);
                            synchronized (stateLock) {
                                if (!chatLogs.containsKey(dmKey)) {
                                    chatLogs.put(dmKey, new ArrayList<>());
                                    if (!tabKeys.contains(dmKey)) tabKeys.add(dmKey);
                                    runOnUiThread(() -> pagerAdapter.notifyDataSetChanged());
                                }
                            }

                            SignalStore.BundleClassification c =
                                    signalStore.classifyIncomingBundle(fromNick, bundle);

                            switch (c.status) {
                                case INVALID:
                                case UNCHANGED:
                                    return;

                                case NEW: {
                                    announceKeyTo(serverName, fromNick);
                                    String combinedFp = signalStore.combinedFingerprint(fromNick);
                                    String infoText = getString(R.string.e2e_enabled, fromNick)
                                            + (combinedFp != null
                                            ? getString(R.string.e2e_fingerprint_suffix, combinedFp)
                                            : "");
                                    appendToTab(dmKey, new ChatMessage(
                                            null, infoText, ChatMessage.Type.SYSTEM));
                                    runOnUiThread(() -> refreshTabLabel(dmKey));
                                    break;
                                }

                                case CHANGED: {
                                    String warn = getString(
                                            R.string.e2e_key_changed_warning, fromNick)
                                            + getString(R.string.e2e_key_changed_fingerprints,
                                            c.oldFingerprint, c.newFingerprint);
                                    appendToTab(dmKey, new ChatMessage(
                                            null, warn, ChatMessage.Type.SYSTEM));
                                    runOnUiThread(() -> {
                                        refreshTabLabel(dmKey);
                                        Toast.makeText(MainActivity.this,
                                                getString(R.string.e2e_key_changed_toast, fromNick),
                                                Toast.LENGTH_LONG).show();
                                    });
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            android.util.Log.w("MainActivity",
                                    "Failed to process key chunk from " + fromNick, e);
                        }
                    }, "signal-key-recv").start();
                }

                @Override public void onMembersChanged(String serverName, String channel,
                                                       List<String> sortedNicks) {
                    String key = tabKey(serverName, channel);
                    channelMembers.put(key, sortedNicks);
                    MembersSheet sheet = (MembersSheet) getSupportFragmentManager()
                            .findFragmentByTag(MembersSheet.TAG);
                    if (sheet != null && key.equals(sheet.getTabKey())) {
                        sheet.updateMembers(sortedNicks);
                    }
                }

                @Override public void onChannelListStarted(String serverName) {
                    ChannelDiscoverySheet sheet = currentDiscoverySheet(serverName);
                    if (sheet != null) sheet.onListStarted();
                }
                @Override public void onChannelListEntry(String serverName, String channel,
                                                         int userCount, String topic) {
                    ChannelDiscoverySheet sheet = currentDiscoverySheet(serverName);
                    if (sheet != null) sheet.onEntry(channel, userCount, topic);
                }
                @Override public void onChannelListComplete(String serverName) {
                    ChannelDiscoverySheet sheet = currentDiscoverySheet(serverName);
                    if (sheet != null) sheet.onListComplete();
                }

                @Override public void onServerText(String serverName, String text) {
                    runOnUiThread(() -> {
                        String key = currentTabKeyForServer(serverName);
                        if (key != null) {
                            appendToTab(key, new ChatMessage(null, text, ChatMessage.Type.SYSTEM));
                        } else {
                            Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });

            for (Server s : knownServers.values()) ircService.connect(s);
            ircService.setAppVisible(getLifecycle().getCurrentState()
                    .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED));
            refreshStatusBar();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            ircService   = null;
            refreshStatusBar();
        }
    };

    private final ActivityResultLauncher<Intent> serverPickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                        Intent data = result.getData();

                        if (data.getBooleanExtra(ServerSwitcherActivity.EXTRA_IS_DELETE, false)) {
                            String deleteName = data.getStringExtra(
                                    ServerSwitcherActivity.EXTRA_DELETE_NAME);
                            if (deleteName != null) removeServer(deleteName);
                            return;
                        }

                        String json = data.getStringExtra(ServerSwitcherActivity.EXTRA_SERVER_JSON);
                        if (json != null) {
                            try {
                                addOrReplaceServer(Server.fromJson(new JSONObject(json)));
                            } catch (JSONException e) {
                                Toast.makeText(this, getString(R.string.failed_to_load_server),
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    });

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
        signalStore = new SignalStore(crypto);
        if (!signalStore.hasIdentity()) {
            generateSignalIdentityInBackground();
        }

        msgDb = MessageDatabase.get(this);
        msgDb.init(crypto.getMasterKey());

        setContentView(R.layout.activity_main);

        serverLabelRow  = findViewById(R.id.serverLabelRow);
        statusLabel     = findViewById(R.id.statusLabel);
        defaultServerLabelColor = androidx.core.content.ContextCompat.getColor(this, R.color.text_primary);
        addServerButton = findViewById(R.id.switchServerButton);
        sendButton      = findViewById(R.id.sendButton);
        membersButton   = findViewById(R.id.membersButton);
        discoverButton  = findViewById(R.id.discoverButton);
        chatInput       = findViewById(R.id.chatInput);
        tabLayout       = findViewById(R.id.tabLayout);
        viewPager       = findViewById(R.id.viewPager);
        replyPreviewBar  = findViewById(R.id.replyPreviewBar);
        replyPreviewNick = findViewById(R.id.replyPreviewNick);
        replyPreviewText = findViewById(R.id.replyPreviewText);
        replyCancelBtn   = findViewById(R.id.replyCancelBtn);
        replyCancelBtn.setOnClickListener(v -> clearReply());

        View statusBarSpacer     = findViewById(R.id.statusBarSpacer);
        View navigationBarSpacer = findViewById(R.id.navigationBarSpacer);

        View rootContent = getWindow().getDecorView().findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(rootContent, (v, insets) -> {
            androidx.core.graphics.Insets sysBars =
                    insets.getInsets(WindowInsetsCompat.Type.systemBars());
            androidx.core.graphics.Insets ime =
                    insets.getInsets(WindowInsetsCompat.Type.ime());

            v.setPadding(sysBars.left, 0, sysBars.right, 0);

            if (statusBarSpacer != null) {
                android.view.ViewGroup.LayoutParams lp = statusBarSpacer.getLayoutParams();
                lp.height = sysBars.top;
                statusBarSpacer.setLayoutParams(lp);
            }

            if (navigationBarSpacer != null) {
                android.view.ViewGroup.LayoutParams lp = navigationBarSpacer.getLayoutParams();
                lp.height = Math.max(sysBars.bottom, ime.bottom);
                navigationBarSpacer.setLayoutParams(lp);
            }

            return WindowInsetsCompat.CONSUMED;
        });

        addServerButton.setOnClickListener(v ->
                serverPickerLauncher.launch(new Intent(this, ServerSwitcherActivity.class)));

        sendButton.setOnClickListener(v -> sendMessage());

        membersButton.setOnClickListener(v -> showMembersSheet());

        discoverButton.setOnClickListener(v -> showChannelDiscoverySheet());

        tabLayout.setTabMode(TabLayout.MODE_SCROLLABLE);

        pagerAdapter = new ChannelPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);
        new TabLayoutMediator(tabLayout, viewPager,
                (tab, pos) -> applyTabLabel(tab, visibleTabKeys().get(pos))
        ).attach();

        tabLayout.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                updateMembersButtonVisibility();
                updateDiscoverButtonVisibility();
            }
            @Override public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
            @Override public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
        });
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                updateMembersButtonVisibility();
                updateDiscoverButtonVisibility();
                visibleTabKey = currentTabKey();
                if (isAppInForeground) markTabRead(visibleTabKey);
            }
        });

        for (int i = 0; i < tabLayout.getTabCount(); i++) {
            View tabView = tabLayout.getTabAt(i) != null
                    ? tabLayout.getTabAt(i).view : null;
            if (tabView != null) attachTabLongPress(tabView, i);
        }
        tabLayout.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            for (int i = 0; i < tabLayout.getTabCount(); i++) {
                com.google.android.material.tabs.TabLayout.Tab tab = tabLayout.getTabAt(i);
                if (tab != null && tab.view != null) attachTabLongPress(tab.view, i);
            }
        });

        restoreHistory();

        ThemeHelper.apply(this, crypto);

        Intent serviceIntent = new Intent(this, IrcService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE);

        handleDmIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDmIntent(intent);
    }

    private void handleDmIntent(Intent intent) {
        if (intent == null) return;
        String server = intent.getStringExtra(IrcService.EXTRA_DM_SERVER);
        String nick   = intent.getStringExtra(IrcService.EXTRA_DM_NICK);
        if (server == null || nick == null) return;
        intent.removeExtra(IrcService.EXTRA_DM_SERVER);
        intent.removeExtra(IrcService.EXTRA_DM_NICK);
        if (!isValidNick(nick)) return;
        openDmTab(server, nick);
    }

    @Override
    protected void onStart() {
        super.onStart();
        isAppInForeground = true;
        if (serviceBound && ircService != null) ircService.setAppVisible(true);
        visibleTabKey = currentTabKey();
        markCurrentTabRead();
    }

    @Override
    protected void onStop() {
        super.onStop();
        isAppInForeground = false;
        if (serviceBound && ircService != null) ircService.setAppVisible(false);
        saveHandler.removeCallbacks(saveRunnable);
        saveHistoryNow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isAppInForeground = true;
        if (crypto != null) {
            ThemeHelper.apply(this, crypto);
            if (focusedServerName != null && serverLabelRow != null) refreshServerLabel();
        }
        markCurrentTabRead();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            ircService.setListener(null);
            unbindService(serviceConnection);
            serviceBound = false;
        }
        if (msgDb != null) msgDb.lock();
        cancelConnectionFailedToast();
    }

    private void showConnectionFailedToast(String serverName, String errorDetail) {
        cancelConnectionFailedToast();

        String text = getString(R.string.server_not_connected, serverName);
        if (errorDetail != null && !errorDetail.isEmpty()) {
            text = text + " (" + errorDetail + ")";
        }
        connectionErrorToast = android.widget.Toast.makeText(
                this, text, android.widget.Toast.LENGTH_LONG);
        connectionErrorToast.show();

        long endAtMs = android.os.SystemClock.uptimeMillis() + CONNECTION_FAILED_TOAST_DURATION_MS;
        connectionErrorToastRunnable = new Runnable() {
            @Override public void run() {
                if (connectionErrorToast == null
                        || android.os.SystemClock.uptimeMillis() >= endAtMs) {
                    cancelConnectionFailedToast();
                    return;
                }
                connectionErrorToast.show();
                connectionErrorToastHandler.postDelayed(this, CONNECTION_FAILED_TOAST_REFRESH_MS);
            }
        };
        connectionErrorToastHandler.postDelayed(
                connectionErrorToastRunnable, CONNECTION_FAILED_TOAST_REFRESH_MS);
    }

    private void cancelConnectionFailedToast() {
        if (connectionErrorToastRunnable != null) {
            connectionErrorToastHandler.removeCallbacks(connectionErrorToastRunnable);
            connectionErrorToastRunnable = null;
        }
        if (connectionErrorToast != null) {
            connectionErrorToast.cancel();
            connectionErrorToast = null;
        }
    }

    static String tabKey(String serverName, String channel) {
        String safeServer  = serverName != null ? serverName.replace("/", "\u2215") : "";
        String safeChannel = channel    != null ? channel.replace("/", "\u2215")    : "";
        return safeServer + "/" + safeChannel;
    }

    static boolean isDmTab(String key) {
        int slash = key.indexOf('/');
        if (slash < 0) return false;
        String target = key.substring(slash + 1);
        return !target.startsWith("#") && !target.startsWith("&");
    }

    private String currentTabKey() {
        List<String> visible = visibleTabKeys();
        int cur = viewPager.getCurrentItem();
        return (cur >= 0 && cur < visible.size()) ? visible.get(cur) : null;
    }

    private void updateMembersButtonVisibility() {
        String key = currentTabKey();
        boolean show = key != null && !isDmTab(key);
        membersButton.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void updateDiscoverButtonVisibility() {
        String serverName = currentServerName();
        boolean show = serverName != null && serviceBound && ircService != null
                && ircService.isConnected(serverName);
        discoverButton.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showMembersSheet() {
        String key = currentTabKey();
        if (key == null || isDmTab(key)) return;

        int slash = key.indexOf('/');
        String serverName = key.substring(0, slash);
        String channel    = key.substring(slash + 1);

        List<String> members;
        if (serviceBound && ircService != null) {
            members = ircService.getChannelMembers(serverName, channel);
            channelMembers.put(key, members);
        } else {
            members = channelMembers.getOrDefault(key, new ArrayList<>());
        }

        MembersSheet sheet = MembersSheet.newInstance(key, channel, members);
        sheet.setOnDmListener(nick -> openDmTab(serverName, nick));
        sheet.show(getSupportFragmentManager(), MembersSheet.TAG);
    }

    private ChannelDiscoverySheet currentDiscoverySheet(String serverName) {
        ChannelDiscoverySheet sheet = (ChannelDiscoverySheet) getSupportFragmentManager()
                .findFragmentByTag(ChannelDiscoverySheet.TAG);
        if (sheet == null || !serverName.equals(sheet.getServerName())) return null;
        return sheet;
    }

    private void showChannelDiscoverySheet() {
        String serverName = currentServerName();
        if (serverName == null) {
            Toast.makeText(this, getString(R.string.no_connected_server), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!serviceBound || ircService == null || !ircService.isConnected(serverName)) {
            Toast.makeText(this, getString(R.string.server_not_connected, serverName), Toast.LENGTH_SHORT).show();
            return;
        }

        ChannelDiscoverySheet sheet = ChannelDiscoverySheet.newInstance(serverName);
        sheet.setOnJoinListener(channel -> joinDiscoveredChannel(serverName, channel));
        sheet.show(getSupportFragmentManager(), ChannelDiscoverySheet.TAG);

        ircService.requestChannelList(serverName);
    }

    void joinDiscoveredChannel(String serverName, String channel) {
        joinDiscoveredChannel(serverName, channel, null);
    }

    void joinDiscoveredChannel(String serverName, String channel, String channelKey) {
        if (!serviceBound || ircService == null || !ircService.isConnected(serverName)) {
            Toast.makeText(this, getString(R.string.server_not_connected, serverName), Toast.LENGTH_SHORT).show();
            return;
        }
        if (channel == null || channel.isEmpty()
                || (!channel.startsWith("#") && !channel.startsWith("&"))) {
            return;
        }

        String rawLine = (channelKey != null && !channelKey.isEmpty())
                ? "JOIN " + channel + " " + channelKey : "JOIN " + channel;
        boolean sent = ircService.sendRaw(serverName, rawLine);
        if (!sent) {
            Toast.makeText(this, getString(R.string.send_failed), Toast.LENGTH_SHORT).show();
            return;
        }

        String key = tabKey(serverName, channel);
        boolean isNewTab;
        synchronized (stateLock) {
            isNewTab = !chatLogs.containsKey(key);
            if (isNewTab) {
                chatLogs.put(key, new ArrayList<>());
                tabKeys.add(key);
            }
        }
        if (isNewTab) {
            pagerAdapter.notifyDataSetChanged();
            ircService.requestHistory(serverName, channel);
        }

        Server srv = knownServers.get(serverName);
        if (srv != null && !srv.getChannels().contains(channel)) {
            List<String> updated = new ArrayList<>(srv.getChannels());
            updated.add(channel);
            srv.setChannels(updated);
            scheduleSave();
        }

        int idx = visibleTabKeys().indexOf(key);
        if (idx >= 0) viewPager.setCurrentItem(idx, true);
    }

    public static class ChannelDiscoverySheet extends BottomSheetDialogFragment {

        public static final String TAG = "channel_discovery_sheet";

        private static final String ARG_SERVER_NAME = "server_name";

        interface OnJoinListener { void onJoin(String channel); }
        private OnJoinListener joinListener;
        private ChannelDiscoveryAdapter discoveryAdapter;

        private TextView countView;
        private android.widget.ProgressBar progressView;
        private TextView emptyView;

        public static ChannelDiscoverySheet newInstance(String serverName) {
            ChannelDiscoverySheet f = new ChannelDiscoverySheet();
            Bundle args = new Bundle();
            args.putString(ARG_SERVER_NAME, serverName);
            f.setArguments(args);
            return f;
        }

        public String getServerName() {
            return getArguments() != null ? getArguments().getString(ARG_SERVER_NAME, "") : "";
        }

        public void setOnJoinListener(OnJoinListener l) { this.joinListener = l; }

        public void onListStarted() {
            if (discoveryAdapter != null) discoveryAdapter.clear();
            if (progressView != null) progressView.setVisibility(View.VISIBLE);
            if (emptyView != null) emptyView.setVisibility(View.GONE);
            if (countView != null) {
                countView.setText(getString(R.string.channels_loading));
            }
        }

        public void onEntry(String channel, int userCount, String topic) {
            if (discoveryAdapter != null) discoveryAdapter.addEntry(channel, userCount, topic);
        }

        public void onListComplete() {
            if (progressView != null) progressView.setVisibility(View.GONE);
            if (discoveryAdapter != null) {
                boolean empty = discoveryAdapter.getItemCount() == 0;
                if (emptyView != null) emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
                if (countView != null) {
                    countView.setText(getString(R.string.channels_found, discoveryAdapter.totalCount()));
                }
            }
        }

        @NonNull
        @Override
        public android.app.Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
            dialog.setOnShowListener(d -> {
                View sheet = ((BottomSheetDialog) d)
                        .findViewById(com.google.android.material.R.id.design_bottom_sheet);
                if (sheet != null) {
                    sheet.post(() -> {
                        int height = (int) (sheet.getRootView().getHeight() * 0.75);
                        sheet.getLayoutParams().height = height;
                        sheet.requestLayout();
                        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
                        behavior.setPeekHeight(height);
                        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                    });
                }
            });
            return dialog;
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater,
                                 @Nullable ViewGroup container,
                                 @Nullable Bundle savedInstanceState) {
            return inflater.inflate(R.layout.bottom_sheet_channel_discovery, container, false);
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);

            countView    = view.findViewById(R.id.discoverySheetCount);
            progressView = view.findViewById(R.id.discoveryProgress);
            emptyView    = view.findViewById(R.id.discoveryEmptyText);
            EditText searchBox = view.findViewById(R.id.discoverySearchInput);
            RecyclerView rv    = view.findViewById(R.id.discoveryRecycler);

            rv.setLayoutManager(new LinearLayoutManager(requireContext()));

            discoveryAdapter = new ChannelDiscoveryAdapter(channel -> {
                if (joinListener != null) joinListener.onJoin(channel);
                dismiss();
            });
            rv.setAdapter(discoveryAdapter);

            progressView.setVisibility(View.VISIBLE);

            searchBox.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    discoveryAdapter.filter(s.toString());
                }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });
        }
    }

    static class ChannelDiscoveryAdapter
            extends RecyclerView.Adapter<ChannelDiscoveryAdapter.VH> {

        static class Entry {
            final String channel;
            final int    userCount;
            final String topic;
            Entry(String channel, int userCount, String topic) {
                this.channel = channel; this.userCount = userCount; this.topic = topic;
            }
        }

        interface OnJoinClickListener { void onClick(String channel); }

        private final List<Entry> allEntries = new ArrayList<>();
        private final List<Entry> shown      = new ArrayList<>();
        private String filterQuery = "";
        private final OnJoinClickListener joinClickListener;

        ChannelDiscoveryAdapter(OnJoinClickListener l) { joinClickListener = l; }

        void clear() {
            allEntries.clear();
            shown.clear();
            notifyDataSetChanged();
        }

        void addEntry(String channel, int userCount, String topic) {
            allEntries.add(new Entry(channel, userCount, topic));
            if (matchesFilter(channel, topic)) {
                shown.add(new Entry(channel, userCount, topic));
                notifyItemInserted(shown.size() - 1);
            }
        }

        int totalCount() { return allEntries.size(); }

        private boolean matchesFilter(String channel, String topic) {
            if (filterQuery.isEmpty()) return true;
            String c = channel != null ? channel.toLowerCase(java.util.Locale.ROOT) : "";
            String t = topic != null ? topic.toLowerCase(java.util.Locale.ROOT) : "";
            return c.contains(filterQuery) || t.contains(filterQuery);
        }

        void filter(String query) {
            filterQuery = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
            shown.clear();
            for (Entry e : allEntries) {
                if (matchesFilter(e.channel, e.topic)) shown.add(e);
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View row = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_channel_discovery, parent, false);
            return new VH(row);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Entry entry = shown.get(position);
            holder.name.setText(entry.channel);
            holder.topic.setText(entry.topic == null || entry.topic.isEmpty()
                    ? "" : entry.topic);
            holder.topic.setVisibility(entry.topic == null || entry.topic.isEmpty()
                    ? View.GONE : View.VISIBLE);
            holder.users.setText(holder.itemView.getContext()
                    .getResources().getQuantityString(
                            R.plurals.channel_discovery_users, entry.userCount, entry.userCount));

            holder.joinBtn.setTextColor(ThemeHelper.currentAccent);
            android.graphics.drawable.Drawable joinBg = holder.joinBtn.getBackground();
            if (joinBg != null) {
                joinBg = joinBg.mutate();
                androidx.core.graphics.drawable.DrawableCompat.setTint(joinBg, ThemeHelper.currentAccent);
                holder.joinBtn.setBackground(joinBg);
            }

            holder.joinBtn.setOnClickListener(v -> {
                if (joinClickListener != null) joinClickListener.onClick(entry.channel);
            });
            holder.itemView.setOnClickListener(v -> {
                if (joinClickListener != null) joinClickListener.onClick(entry.channel);
            });
        }

        @Override
        public int getItemCount() { return shown.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final TextView name, topic, users, joinBtn;
            VH(View v) {
                super(v);
                name    = v.findViewById(R.id.channelDiscoveryName);
                topic   = v.findViewById(R.id.channelDiscoveryTopic);
                users   = v.findViewById(R.id.channelDiscoveryUsers);
                joinBtn = v.findViewById(R.id.channelDiscoveryJoinButton);
            }
        }
    }

    public static class MembersSheet extends BottomSheetDialogFragment {

        public static final String TAG = "members_sheet";

        private static final String ARG_TAB_KEY  = "tab_key";
        private static final String ARG_CHANNEL  = "channel";
        private static final String ARG_MEMBERS  = "members";

        interface OnDmListener { void onDm(String nick); }
        private OnDmListener dmListener;
        private MembersAdapter membersAdapter;

        public static MembersSheet newInstance(String tabKey, String channel,
                                               List<String> members) {
            MembersSheet f = new MembersSheet();
            Bundle args = new Bundle();
            args.putString(ARG_TAB_KEY, tabKey);
            args.putString(ARG_CHANNEL, channel);
            args.putStringArrayList(ARG_MEMBERS, new ArrayList<>(members));
            f.setArguments(args);
            return f;
        }

        public String getTabKey() {
            return getArguments() != null ? getArguments().getString(ARG_TAB_KEY, "") : "";
        }

        public void setOnDmListener(OnDmListener l) { this.dmListener = l; }

        public void updateMembers(List<String> members) {
            if (membersAdapter != null) membersAdapter.setNicks(members);
        }

        @NonNull
        @Override
        public android.app.Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
            dialog.setOnShowListener(d -> {
                View sheet = ((BottomSheetDialog) d)
                        .findViewById(com.google.android.material.R.id.design_bottom_sheet);
                if (sheet != null) {
                    sheet.post(() -> {
                        int height = (int) (sheet.getRootView().getHeight() * 0.60);
                        sheet.getLayoutParams().height = height;
                        sheet.requestLayout();
                        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
                        behavior.setPeekHeight(height);
                        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                    });
                }
            });
            return dialog;
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater,
                                 @Nullable ViewGroup container,
                                 @Nullable Bundle savedInstanceState) {
            return inflater.inflate(R.layout.bottom_sheet_members, container, false);
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);

            String channel = getArguments() != null
                    ? getArguments().getString(ARG_CHANNEL, getString(R.string.members)) : getString(R.string.members);
            ArrayList<String> initial = getArguments() != null
                    ? getArguments().getStringArrayList(ARG_MEMBERS) : new ArrayList<>();
            if (initial == null) initial = new ArrayList<>();

            TextView titleView = view.findViewById(R.id.membersSheetTitle);
            TextView countView = view.findViewById(R.id.membersSheetCount);
            EditText searchBox = view.findViewById(R.id.membersSearchInput);
            RecyclerView rv    = view.findViewById(R.id.membersRecycler);

            titleView.setText(channel);
            countView.setText(view.getContext().getString(R.string.members_online, initial.size()));

            rv.setLayoutManager(new LinearLayoutManager(requireContext()));

            membersAdapter = new MembersAdapter(initial, nick -> {
                if (dmListener != null) dmListener.onDm(nick);
                dismiss();
            });
            membersAdapter.setCountView(countView);
            rv.setAdapter(membersAdapter);

            searchBox.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    membersAdapter.filter(s.toString());
                }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });
        }
    }

    static class MembersAdapter extends RecyclerView.Adapter<MembersAdapter.VH> {

        interface OnClickListener { void onClick(String nick); }

        private static final int[] AVATAR_COLORS = {
                0xFF3D5AFE, 0xFF00BCD4, 0xFF4CAF50, 0xFFE91E63,
                0xFFFF5722, 0xFF9C27B0, 0xFF009688, 0xFFFF9800
        };

        private final List<String> allNicks  = new ArrayList<>();
        private final List<String> shown     = new ArrayList<>();
        private String filterQuery = "";
        private final OnClickListener clickListener;
        private TextView countView;

        MembersAdapter(List<String> nicks, OnClickListener l) {
            allNicks.addAll(nicks);
            shown.addAll(nicks);
            clickListener = l;
        }

        void setCountView(TextView cv) { this.countView = cv; }

        void setNicks(List<String> nicks) {
            allNicks.clear();
            allNicks.addAll(nicks);
            filter(filterQuery);
        }

        void filter(String query) {
            filterQuery = query == null ? "" : query.trim().toLowerCase();
            shown.clear();
            for (String n : allNicks) {
                if (filterQuery.isEmpty() || n.toLowerCase().contains(filterQuery))
                    shown.add(n);
            }
            notifyDataSetChanged();
            if (countView != null) {
                countView.setText(filterQuery.isEmpty()
                        ? countView.getContext().getString(R.string.members_online, allNicks.size())
                        : countView.getContext().getString(R.string.members_filter_count, shown.size(), allNicks.size()));
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View row = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_member, parent, false);
            return new VH(row);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            String nick = shown.get(position);

            String initial = nick.isEmpty() ? "?" : String.valueOf(nick.charAt(0)).toUpperCase();
            holder.initial.setText(initial);
            int colorIdx = nick.isEmpty() ? 0
                    : (Character.toLowerCase(nick.charAt(0)) - 'a' + 26) % AVATAR_COLORS.length;
            android.graphics.drawable.Drawable bg = holder.initial.getBackground().mutate();
            androidx.core.graphics.drawable.DrawableCompat.setTint(bg, AVATAR_COLORS[colorIdx]);

            holder.nick.setText(nick);

            holder.dmBtn.setTextColor(ThemeHelper.currentAccent);
            android.graphics.drawable.Drawable dmBg = holder.dmBtn.getBackground();
            if (dmBg != null) {
                dmBg = dmBg.mutate();
                androidx.core.graphics.drawable.DrawableCompat.setTint(dmBg, ThemeHelper.currentAccent);
                holder.dmBtn.setBackground(dmBg);
            }

            holder.dmBtn.setOnClickListener(v -> {
                if (clickListener != null) clickListener.onClick(nick);
            });
            holder.itemView.setOnClickListener(v -> {
                if (clickListener != null) clickListener.onClick(nick);
            });
        }

        @Override
        public int getItemCount() { return shown.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final TextView initial, nick, dmBtn;
            VH(@NonNull View root) {
                super(root);
                initial = root.findViewById(R.id.memberInitial);
                nick    = root.findViewById(R.id.memberNick);
                dmBtn   = root.findViewById(R.id.memberDmButton);
            }
        }
    }

    private void applyTabLabel(TabLayout.Tab tab, String key) {
        int slash = key.indexOf('/');
        String serverName = slash >= 0 ? key.substring(0, slash) : "";
        String channel    = slash >= 0 ? key.substring(slash + 1) : key;

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setGravity(android.view.Gravity.CENTER);

        boolean multiServer = knownServers.size() > 1;

        if (multiServer) {
            TextView serverView = new TextView(this);
            serverView.setText(serverName);
            serverView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
            serverView.setAlpha(0.65f);
            serverView.setGravity(android.view.Gravity.CENTER);
            layout.addView(serverView);
        }

        int unread;
        synchronized (stateLock) {
            Integer c = unreadCounts.get(key);
            unread = c != null ? c : 0;
        }

        android.widget.LinearLayout channelRow = new android.widget.LinearLayout(this);
        channelRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        channelRow.setGravity(android.view.Gravity.CENTER);

        TextView channelView = new TextView(this);
        channelView.setText(channel);
        channelView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, multiServer ? 12 : 14);
        channelView.setGravity(android.view.Gravity.CENTER);
        channelRow.addView(channelView);

        if (unread > 0) {
            TextView badge = new TextView(this);
            badge.setText(unread > 99 ? "99+" : String.valueOf(unread));
            badge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
            badge.setTextColor(android.graphics.Color.WHITE);
            badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            badge.setGravity(android.view.Gravity.CENTER);
            badge.setMinWidth(dp(this, 16));
            badge.setMinHeight(dp(this, 16));
            badge.setPadding(dp(this, 4), 0, dp(this, 4), 0);

            android.graphics.drawable.GradientDrawable badgeBg =
                    new android.graphics.drawable.GradientDrawable();
            badgeBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            badgeBg.setColor(ThemeHelper.currentAccent);
            badge.setBackground(badgeBg);

            android.widget.LinearLayout.LayoutParams badgeParams =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            badgeParams.setMargins(dp(this, 4), 0, 0, 0);
            channelRow.addView(badge, badgeParams);
        }

        layout.addView(channelRow);

        tab.setCustomView(layout);
    }

    private void closeDmTab(String key) {
        synchronized (stateLock) {
            tabKeys.remove(key);
            chatLogs.remove(key);
            chatRowIds.remove(key);
            dmGreetingSent.remove(key);
            unreadCounts.remove(key);
        }
        if (msgDb != null) {
            new Thread(() -> msgDb.deleteTab(key), "db-close-tab").start();
        }
        pagerAdapter.notifyDataSetChanged();
        scheduleSave();
    }

    private void addOrReplaceServer(Server server) {
        if (!isValidServerName(server.getName())) {
            Toast.makeText(this, getString(R.string.invalid_server_name), Toast.LENGTH_SHORT).show();
            return;
        }

        String name = server.getName();
        boolean isEdit = knownServers.containsKey(name);

        if (isEdit) {
            if (serviceBound && ircService != null) ircService.disconnectServer(name);

            Server old = knownServers.get(name);
            if (old != null) {
                List<String> keysToRemove = new ArrayList<>();
                synchronized (stateLock) {
                    for (String ch : old.getChannels()) {
                        String key = tabKey(name, ch);
                        if (!server.getChannels().contains(ch)) {
                            keysToRemove.add(key);
                        }
                    }
                    for (String key : keysToRemove) {
                        tabKeys.remove(key);
                        chatLogs.remove(key);
                        chatRowIds.remove(key);
                        unreadCounts.remove(key);
                    }
                }
                if (!keysToRemove.isEmpty()) pagerAdapter.notifyDataSetChanged();
            }
        }

        knownServers.put(name, server);

        boolean tabsAdded = false;
        for (String ch : server.getChannels()) {
            String key = tabKey(name, ch);
            synchronized (stateLock) {
                if (!chatLogs.containsKey(key)) {
                    chatLogs.put(key, loadChannelHistory(name, ch));
                    tabKeys.add(key);
                    tabsAdded = true;
                }
            }
        }
        if (tabsAdded) pagerAdapter.notifyDataSetChanged();

        refreshServerLabel();
        refreshStatusBar();
        scheduleSave();

        if (serviceBound) ircService.connect(server);
    }

    private void removeServer(String name) {
        if (!knownServers.containsKey(name)) return;

        if (name.equals(focusedServerName)) {
            focusedServerName = null;
        }

        if (serviceBound && ircService != null) ircService.disconnectServer(name);

        List<String> keysToRemove = new ArrayList<>();
        synchronized (stateLock) {
            for (String key : tabKeys) {
                int slash = key.indexOf('/');
                if (slash >= 0 && key.substring(0, slash).equals(name)) {
                    keysToRemove.add(key);
                }
            }
            for (String key : keysToRemove) {
                tabKeys.remove(key);
                chatLogs.remove(key);
                chatRowIds.remove(key);
                dmGreetingSent.remove(key);
                channelMembers.remove(key);
                unreadCounts.remove(key);
            }
            knownServers.remove(name);
        }

        if (msgDb != null) {
            for (String key : keysToRemove) {
                final String k = key;
                new Thread(() -> msgDb.deleteTab(k), "db-remove-server").start();
            }
        }

        pagerAdapter.notifyDataSetChanged();
        refreshServerLabel();
        refreshStatusBar();
        scheduleSave();
    }


    private void refreshServerLabel() {
        serverLabelRow.removeAllViews();

        if (knownServers.isEmpty()) {
            TextView chip = makeServerLabelChip(getString(R.string.app_name), false);
            serverLabelRow.addView(chip);
            return;
        }

        boolean first = true;
        for (String name : knownServers.keySet()) {
            TextView chip = makeServerLabelChip(name, true);
            boolean focused = name.equals(focusedServerName);
            chip.setTextColor(focused ? ThemeHelper.currentAccent : defaultServerLabelColor);

            GestureDetector detector = new GestureDetector(this,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDoubleTap(@NonNull MotionEvent e) {
                            toggleServerFocus(name);
                            return true;
                        }
                    });
            chip.setOnTouchListener((v, event) -> {
                detector.onTouchEvent(event);
                return true;
            });

            android.widget.LinearLayout.LayoutParams lp =
                    (android.widget.LinearLayout.LayoutParams) chip.getLayoutParams();
            if (!first) {
                lp.leftMargin = (int) (8 * getResources().getDisplayMetrics().density);
            }
            first = false;

            serverLabelRow.addView(chip);
        }
    }

    private TextView makeServerLabelChip(String text, boolean interactive) {
        TextView chip = new TextView(this);
        chip.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        chip.setText(text);
        chip.setTextColor(defaultServerLabelColor);
        chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
        chip.setMaxLines(1);
        if (!interactive) chip.setAlpha(0.85f);
        return chip;
    }

    private static String serverNameOf(String tabKey) {
        if (tabKey == null) return "";
        int slash = tabKey.indexOf('/');
        return slash >= 0 ? tabKey.substring(0, slash) : tabKey;
    }

    private List<String> visibleTabKeys() {
        if (focusedServerName == null) return tabKeys;
        List<String> out = new ArrayList<>();
        for (String key : tabKeys) {
            if (focusedServerName.equals(serverNameOf(key))) out.add(key);
        }
        return out;
    }

    private void toggleServerFocus(String serverName) {
        if (serverName == null || !knownServers.containsKey(serverName)) return;

        List<String> oldVisible = visibleTabKeys();
        int cur = viewPager.getCurrentItem();
        String currentKey = (cur >= 0 && cur < oldVisible.size()) ? oldVisible.get(cur) : null;

        if (serverName.equals(focusedServerName)) {
            focusedServerName = null;
            pagerAdapter.notifyDataSetChanged();
            restoreViewPagerPosition(currentKey, null);
            refreshServerLabel();
            Toast.makeText(this,
                    getString(R.string.server_focus_cleared),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        focusedServerName = serverName;
        pagerAdapter.notifyDataSetChanged();
        restoreViewPagerPosition(currentKey, serverName);
        refreshServerLabel();
        Toast.makeText(this,
                getString(R.string.server_focus_enabled, serverName),
                Toast.LENGTH_SHORT).show();
    }

    private void restoreViewPagerPosition(String previousKey, String preferredServer) {
        List<String> visible = visibleTabKeys();
        if (visible.isEmpty()) return;

        int idx = previousKey != null ? visible.indexOf(previousKey) : -1;
        if (idx < 0 && preferredServer != null) {
            for (int i = 0; i < visible.size(); i++) {
                if (preferredServer.equals(serverNameOf(visible.get(i)))) { idx = i; break; }
            }
        }
        if (idx < 0) idx = 0;
        viewPager.setCurrentItem(idx, false);
    }

    private void refreshStatusBar() {
        if (!serviceBound || ircService == null) {
            statusLabel.setText(getString(R.string.status_disconnected));
            sendButton.setEnabled(false);
            sendButton.setAlpha(0.4f);
            updateDiscoverButtonVisibility();
            return;
        }
        List<Server> connected = ircService.getConnectedServers();
        int total = knownServers.size();
        if (connected.isEmpty()) {
            statusLabel.setText(total > 0 ? getString(R.string.status_connecting) : getString(R.string.status_no_servers));
            sendButton.setEnabled(false);
            sendButton.setAlpha(0.4f);
        } else {
            statusLabel.setText(getString(R.string.status_connected, connected.size(), total));
            sendButton.setEnabled(true);
            sendButton.setAlpha(1f);
        }
        updateMembersButtonVisibility();
        updateDiscoverButtonVisibility();
    }

    private void sendMessage() {
        if (!serviceBound || !ircService.isAnyConnected()) {
            Toast.makeText(this, getString(R.string.not_connected_wait),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        String message = chatInput.getText().toString().trim();
        if (message.isEmpty()) return;

        if (message.startsWith("//")) {
            message = message.substring(1);
        } else if (message.startsWith("/")) {
            handleSlashCommand(message.substring(1).trim());
            chatInput.setText("");
            return;
        }

        if (message.length() > MAX_MSG_LENGTH) {
            Toast.makeText(this,
                    getString(R.string.message_too_long, message.length(), MAX_MSG_LENGTH),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        String key = currentTabKey();
        if (key == null) return;

        int slash = key.indexOf('/');
        if (slash < 0) return;
        String serverName = key.substring(0, slash);
        String channel    = key.substring(slash + 1);

        if (!ircService.isConnected(serverName)) {
            Toast.makeText(this, getString(R.string.server_not_connected, serverName), Toast.LENGTH_SHORT).show();
            return;
        }

        final String replyNick;
        final String replyText;
        if (pendingReply != null && key.equals(pendingReplyTabKey)) {
            Server srv = knownServers.get(serverName);
            replyNick = pendingReply.isSent()
                    ? (srv != null ? srv.getNickname() : "me")
                    : pendingReply.getNick();
            replyText = pendingReply.getText();
        } else {
            replyNick = null;
            replyText = null;
        }

        String outImageUrl    = extractImageUrl(message);
        String outDisplayText = outImageUrl != null
                ? message.replace(outImageUrl, "").trim() : message;

        boolean e2eEncrypted = false;
        List<String> wireLines = null;
        if (isDmTab(key) && outImageUrl == null && signalStore.hasContactBundle(channel)) {
            if (!signalStore.hasIdentity()) {
                Toast.makeText(this,
                        getString(R.string.no_signal_identity),
                        Toast.LENGTH_LONG).show();
            } else {
                if (signalStore.hasPendingIdentity(channel)) {
                    Toast.makeText(this,
                            getString(R.string.e2e_pending_send_warning, channel),
                            Toast.LENGTH_LONG).show();
                }
                try {
                    wireLines    = signalStore.encryptForWire(channel, message);
                    e2eEncrypted = true;
                } catch (Exception e) {
                    String errMsg = e.getMessage() != null
                            ? e.getMessage() : e.getClass().getSimpleName();
                    Toast.makeText(this,
                            getString(R.string.encryption_failed, errMsg), Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }

        String localDisplayText = outDisplayText;
        String rawWire = e2eEncrypted && wireLines != null ? String.join("\n", wireLines) : null;
        ChatMessage msg = new ChatMessage(null, localDisplayText, ChatMessage.Type.SENT,
                replyNick, replyText, outImageUrl, e2eEncrypted, rawWire);
        appendToTab(key, msg);
        chatInput.setText("");
        clearReply();

        if (e2eEncrypted) {
            final List<String> chunks = wireLines;
            new Thread(() -> {
                for (String chunk : chunks) {
                    ircService.sendMessage(serverName, channel, chunk);
                    try { Thread.sleep(50); } catch (InterruptedException e) { return; }
                }
            }, "signal-send").start();
        } else {
            String wire = message;
            if (replyNick != null && replyText != null)
                wire = "> <" + replyNick + "> " + replyText + "\n" + message;
            boolean sent = ircService.sendMessage(serverName, channel, wire);
            if (!sent) {
                Toast.makeText(this, getString(R.string.send_failed), Toast.LENGTH_SHORT).show();
            }
        }
    }
    private void handleSlashMsg(String args) {
        if (args.isEmpty()) {
            showNewDmDialog();
            return;
        }
        int space = args.indexOf(' ');
        String nick = space >= 0 ? args.substring(0, space) : args;
        String text = space >= 0 ? args.substring(space + 1).trim() : "";

        if (!isValidNick(nick)) {
            Toast.makeText(this, getString(R.string.invalid_nickname), Toast.LENGTH_SHORT).show();
            return;
        }

        String serverName = currentServerName();
        if (serverName == null) {
            Toast.makeText(this, getString(R.string.no_connected_server), Toast.LENGTH_SHORT).show();
            return;
        }

        openDmTab(serverName, nick);

        if (!text.isEmpty()) {
            chatInput.setText(text);
            sendMessage();
        }
    }

    private void handleSlashRaw(String rawCommand) {
        if (rawCommand.isEmpty()) return;

        String serverName = currentServerName();
        if (serverName == null) {
            Toast.makeText(this, getString(R.string.no_connected_server), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!serviceBound || ircService == null || !ircService.isConnected(serverName)) {
            Toast.makeText(this, getString(R.string.server_not_connected, serverName), Toast.LENGTH_SHORT).show();
            return;
        }

        boolean sent = ircService.sendRaw(serverName, rawCommand);
        if (!sent) {
            Toast.makeText(this, getString(R.string.send_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void handleSlashCommand(String body) {
        if (body.isEmpty()) return;

        int space = body.indexOf(' ');
        String cmd  = (space >= 0 ? body.substring(0, space) : body).toLowerCase(java.util.Locale.ROOT);
        String args = space >= 0 ? body.substring(space + 1).trim() : "";

        switch (cmd) {
            case "msg":
            case "query":
                handleSlashMsg(args);
                return;
            case "raw":
            case "quote":
                handleSlashRaw(args);
                return;
            case "join":
            case "j":
                handleSlashJoin(args);
                return;
            case "part":
            case "leave":
                handleSlashPart(args);
                return;
            case "nick":
                handleSlashNick(args);
                return;
            case "me":
            case "action":
                handleSlashMe(args);
                return;
            case "topic":
                handleSlashTopic(args);
                return;
            case "notice":
                handleSlashNotice(args);
                return;
            case "whois":
                handleSlashWhois(args);
                return;
            case "kick":
                handleSlashKick(args);
                return;
            case "invite":
                handleSlashInvite(args);
                return;
            case "away":
                handleSlashAway(args);
                return;
            case "quit":
            case "disconnect":
                handleSlashQuit(args);
                return;
            case "help":
            case "commands":
                showSlashCommandHelp();
                return;
            default:
                handleSlashRaw(cmd.toUpperCase(java.util.Locale.ROOT) + (args.isEmpty() ? "" : " " + args));
        }
    }

    private String[] currentTabTarget() {
        String key = currentTabKey();
        if (key == null) return null;
        int slash = key.indexOf('/');
        if (slash < 0) return null;
        return new String[]{ key.substring(0, slash), key.substring(slash + 1), key };
    }

    private static boolean isChannelName(String target) {
        return target != null && (target.startsWith("#") || target.startsWith("&"));
    }

    private void handleSlashJoin(String args) {
        if (args.isEmpty()) {
            Toast.makeText(this, "Usage: /join #channel [key]", Toast.LENGTH_SHORT).show();
            return;
        }
        String serverName = currentServerName();
        if (serverName == null) {
            Toast.makeText(this, getString(R.string.no_connected_server), Toast.LENGTH_SHORT).show();
            return;
        }
        String[] parts = args.split("\\s+", 2);
        String channel = parts[0];
        if (!channel.startsWith("#") && !channel.startsWith("&")) channel = "#" + channel;
        String key = parts.length > 1 ? parts[1] : null;

        joinDiscoveredChannel(serverName, channel, key);
    }

    private void handleSlashPart(String args) {
        String serverName;
        String channel;
        String reason;

        String trimmed = args.trim();
        if (isChannelName(trimmed.split("\\s+", 2)[0])) {
            String[] parts = trimmed.split("\\s+", 2);
            serverName = currentServerName();
            channel    = parts[0];
            reason     = parts.length > 1 ? parts[1] : null;
        } else {
            String[] cur = currentTabTarget();
            if (cur == null || !isChannelName(cur[1])) {
                Toast.makeText(this, "Usage: /part [#channel] [reason]", Toast.LENGTH_SHORT).show();
                return;
            }
            serverName = cur[0];
            channel    = cur[1];
            reason     = trimmed.isEmpty() ? null : trimmed;
        }

        if (serverName == null) {
            Toast.makeText(this, getString(R.string.no_connected_server), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!serviceBound || ircService == null || !ircService.isConnected(serverName)) {
            Toast.makeText(this, getString(R.string.server_not_connected, serverName), Toast.LENGTH_SHORT).show();
            return;
        }

        String rawLine = reason != null ? "PART " + channel + " :" + reason : "PART " + channel;
        boolean sent = ircService.sendRaw(serverName, rawLine);
        if (!sent) {
            Toast.makeText(this, getString(R.string.send_failed), Toast.LENGTH_SHORT).show();
            return;
        }

        Server srv = knownServers.get(serverName);
        if (srv != null && srv.getChannels().contains(channel)) {
            List<String> updated = new ArrayList<>(srv.getChannels());
            updated.remove(channel);
            srv.setChannels(updated);
        }
        scheduleSave();

        closeDmTab(tabKey(serverName, channel));
    }

    private void handleSlashNick(String args) {
        String newNick = args.trim().split("\\s+")[0];
        if (newNick.isEmpty() || !isValidNick(newNick)) {
            Toast.makeText(this, getString(R.string.invalid_nickname), Toast.LENGTH_SHORT).show();
            return;
        }
        String serverName = currentServerName();
        if (serverName == null) {
            Toast.makeText(this, getString(R.string.no_connected_server), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!serviceBound || ircService == null || !ircService.isConnected(serverName)) {
            Toast.makeText(this, getString(R.string.server_not_connected, serverName), Toast.LENGTH_SHORT).show();
            return;
        }

        boolean sent = ircService.sendRaw(serverName, "NICK " + newNick);
        if (!sent) {
            Toast.makeText(this, getString(R.string.send_failed), Toast.LENGTH_SHORT).show();
            return;
        }

        Server srv = knownServers.get(serverName);
        if (srv != null) {
            srv.setNickname(newNick);
            scheduleSave();
        }
    }

    private void handleSlashMe(String args) {
        if (args.isEmpty()) return;
        String[] cur = currentTabTarget();
        if (cur == null) return;
        String serverName = cur[0];
        String target      = cur[1];
        String key          = cur[2];

        if (!serviceBound || ircService == null || !ircService.isConnected(serverName)) {
            Toast.makeText(this, getString(R.string.server_not_connected, serverName), Toast.LENGTH_SHORT).show();
            return;
        }
        if (args.length() > MAX_MSG_LENGTH) {
            Toast.makeText(this,
                    getString(R.string.message_too_long, args.length(), MAX_MSG_LENGTH),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        boolean sent = ircService.sendAction(serverName, target, args);
        if (!sent) {
            Toast.makeText(this, getString(R.string.send_failed), Toast.LENGTH_SHORT).show();
            return;
        }

        Server srv = knownServers.get(serverName);
        String myNick = srv != null ? srv.getNickname() : "me";
        ChatMessage msg = new ChatMessage(null, "* " + myNick + " " + args, ChatMessage.Type.SENT);
        appendToTab(key, msg);
    }

    private void handleSlashTopic(String args) {
        String[] cur = currentTabTarget();
        if (cur == null || !isChannelName(cur[1])) {
            Toast.makeText(this, "Usage: /topic [new topic]", Toast.LENGTH_SHORT).show();
            return;
        }
        String serverName = cur[0];
        String channel     = cur[1];

        if (!serviceBound || ircService == null || !ircService.isConnected(serverName)) {
            Toast.makeText(this, getString(R.string.server_not_connected, serverName), Toast.LENGTH_SHORT).show();
            return;
        }

        String rawLine = args.isEmpty() ? "TOPIC " + channel : "TOPIC " + channel + " :" + args;
        boolean sent = ircService.sendRaw(serverName, rawLine);
        if (!sent) {
            Toast.makeText(this, getString(R.string.send_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void handleSlashNotice(String args) {
        int space = args.indexOf(' ');
        if (space < 0 || space == args.length() - 1) {
            Toast.makeText(this, "Usage: /notice <nick> <message>", Toast.LENGTH_SHORT).show();
            return;
        }
        String target = args.substring(0, space);
        String text   = args.substring(space + 1).trim();
        if (target.isEmpty() || text.isEmpty()) return;

        String serverName = currentServerName();
        if (serverName == null) {
            Toast.makeText(this, getString(R.string.no_connected_server), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!serviceBound || ircService == null || !ircService.isConnected(serverName)) {
            Toast.makeText(this, getString(R.string.server_not_connected, serverName), Toast.LENGTH_SHORT).show();
            return;
        }

        boolean sent = ircService.sendNotice(serverName, target, text);
        if (!sent) {
            Toast.makeText(this, getString(R.string.send_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void handleSlashWhois(String args) {
        String nick = args.trim().split("\\s+")[0];
        if (nick.isEmpty()) {
            Toast.makeText(this, "Usage: /whois <nick>", Toast.LENGTH_SHORT).show();
            return;
        }
        String serverName = currentServerName();
        if (serverName == null) {
            Toast.makeText(this, getString(R.string.no_connected_server), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!serviceBound || ircService == null || !ircService.isConnected(serverName)) {
            Toast.makeText(this, getString(R.string.server_not_connected, serverName), Toast.LENGTH_SHORT).show();
            return;
        }

        boolean sent = ircService.sendRaw(serverName, "WHOIS " + nick);
        if (!sent) {
            Toast.makeText(this, getString(R.string.send_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void handleSlashKick(String args) {
        String[] cur = currentTabTarget();
        String trimmed = args.trim();
        if (cur == null || !isChannelName(cur[1]) || trimmed.isEmpty()) {
            Toast.makeText(this, "Usage: /kick <nick> [reason]", Toast.LENGTH_SHORT).show();
            return;
        }
        String serverName = cur[0];
        String channel     = cur[1];
        String[] parts = trimmed.split("\\s+", 2);
        String nick    = parts[0];
        String reason  = parts.length > 1 ? parts[1] : null;

        if (!serviceBound || ircService == null || !ircService.isConnected(serverName)) {
            Toast.makeText(this, getString(R.string.server_not_connected, serverName), Toast.LENGTH_SHORT).show();
            return;
        }

        String rawLine = reason != null
                ? "KICK " + channel + " " + nick + " :" + reason
                : "KICK " + channel + " " + nick;
        boolean sent = ircService.sendRaw(serverName, rawLine);
        if (!sent) {
            Toast.makeText(this, getString(R.string.send_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void handleSlashInvite(String args) {
        String trimmed = args.trim();
        String[] parts = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
        if (parts.length == 0) {
            Toast.makeText(this, "Usage: /invite <nick> [#channel]", Toast.LENGTH_SHORT).show();
            return;
        }
        String nick = parts[0];
        String[] cur = currentTabTarget();
        String channel = parts.length > 1 ? parts[1]
                : (cur != null && isChannelName(cur[1]) ? cur[1] : null);
        if (channel == null) {
            Toast.makeText(this, "Usage: /invite <nick> <#channel>", Toast.LENGTH_SHORT).show();
            return;
        }

        String serverName = currentServerName();
        if (serverName == null) {
            Toast.makeText(this, getString(R.string.no_connected_server), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!serviceBound || ircService == null || !ircService.isConnected(serverName)) {
            Toast.makeText(this, getString(R.string.server_not_connected, serverName), Toast.LENGTH_SHORT).show();
            return;
        }

        boolean sent = ircService.sendRaw(serverName, "INVITE " + nick + " " + channel);
        if (!sent) {
            Toast.makeText(this, getString(R.string.send_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void handleSlashAway(String args) {
        String serverName = currentServerName();
        if (serverName == null) {
            Toast.makeText(this, getString(R.string.no_connected_server), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!serviceBound || ircService == null || !ircService.isConnected(serverName)) {
            Toast.makeText(this, getString(R.string.server_not_connected, serverName), Toast.LENGTH_SHORT).show();
            return;
        }

        String rawLine = args.isEmpty() ? "AWAY" : "AWAY :" + args;
        boolean sent = ircService.sendRaw(serverName, rawLine);
        if (!sent) {
            Toast.makeText(this, getString(R.string.send_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void handleSlashQuit(String args) {
        String serverName = currentServerName();
        if (serverName == null) {
            Toast.makeText(this, getString(R.string.no_connected_server), Toast.LENGTH_SHORT).show();
            return;
        }
        if (serviceBound && ircService != null && ircService.isConnected(serverName)) {
            String rawLine = args.isEmpty() ? "QUIT" : "QUIT :" + args;
            ircService.sendRaw(serverName, rawLine);
            ircService.disconnectServer(serverName);
            refreshStatusBar();
        }
    }

    private void showSlashCommandHelp() {
        String help =
                "/join #channel [key]\n" +
                        "/part [#channel] [reason]\n" +
                        "/msg <nick> [message]  (alias: /query)\n" +
                        "/me <action>\n" +
                        "/nick <newnick>\n" +
                        "/topic [text]\n" +
                        "/notice <nick> <message>\n" +
                        "/whois <nick>\n" +
                        "/kick <nick> [reason]\n" +
                        "/invite <nick> [#channel]\n" +
                        "/away [message]\n" +
                        "/quit [message]  (alias: /disconnect)\n" +
                        "/raw <IRC command>  (alias: /quote)\n\n" +
                        "Any other /command is sent to the server as-is.\n" +
                        "Start a message with // to send a literal message beginning with a slash.";
        new androidx.appcompat.app.AlertDialog.Builder(this, R.style.IrisDialog)
                .setTitle("Available commands")
                .setMessage(help)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String currentServerName() {
        String key = currentTabKey();
        if (key != null) {
            int slash = key.indexOf('/');
            if (slash >= 0) return key.substring(0, slash);
        }
        List<Server> connected = ircService != null ? ircService.getConnectedServers()
                : new ArrayList<>();
        return connected.isEmpty() ? null : connected.get(0).getName();
    }

    private String currentTabKeyForServer(String serverName) {
        if (serverName == null) return null;
        String cur = currentTabKey();
        synchronized (stateLock) {
            if (cur != null) {
                int slash = cur.indexOf('/');
                if (slash >= 0 && cur.substring(0, slash).equals(serverName)) {
                    return cur;
                }
            }
            for (String key : tabKeys) {
                int slash = key.indexOf('/');
                if (slash >= 0 && key.substring(0, slash).equals(serverName)) {
                    return key;
                }
            }
        }
        return null;
    }

    void openDmTab(String serverName, String nick) {
        if (!isValidNick(nick)) {
            Toast.makeText(this, getString(R.string.invalid_nickname_colon, nick), Toast.LENGTH_SHORT).show();
            return;
        }

        String key = tabKey(serverName, nick);
        boolean isNewTab;
        synchronized (stateLock) {
            isNewTab = !chatLogs.containsKey(key);
            if (isNewTab) {
                chatLogs.put(key, new ArrayList<>());
                tabKeys.add(key);
            }
        }
        if (isNewTab) {
            pagerAdapter.notifyDataSetChanged();
            scheduleSave();
        }
        int idx = visibleTabKeys().indexOf(key);
        if (idx >= 0) viewPager.setCurrentItem(idx, true);

        if (isNewTab && serviceBound && ircService.isConnected(serverName)) {
            ircService.requestHistory(serverName, nick);

            Server srv = knownServers.get(serverName);
            boolean isSelf = srv != null && srv.getNickname().equalsIgnoreCase(nick);
            boolean dmAdEnabled = "true".equals(crypto.getString(KEY_DM_ADVERTISEMENT, null));
            if (!isSelf && dmAdEnabled && !dmGreetingSent.contains(key)) {
                dmGreetingSent.add(key);
                String greeting = "I use IrisChat - https://github.com/umutcamliyurt/IrisChat";
                boolean sent = ircService.sendMessage(serverName, nick, greeting);
                if (sent) {
                    ChatMessage greetMsg = new ChatMessage(null, greeting, ChatMessage.Type.SENT);
                    appendToTab(key, greetMsg);
                }
            }
            if (!isSelf) announceKeyTo(serverName, nick);
        }
    }

    private void announceKeyTo(String serverName, String nick) {
        if (!signalStore.hasIdentity()) return;
        if (!serviceBound || !ircService.isConnected(serverName)) return;
        new Thread(() -> {
            List<String> chunks = signalStore.buildKeyAnnouncement(nick);
            for (String chunk : chunks) {
                ircService.sendNotice(serverName, nick, chunk);
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        }, "signal-announce").start();
    }

    private void showNewDmDialog() {
        if (!serviceBound || !ircService.isAnyConnected()) {
            Toast.makeText(this, getString(R.string.not_connected_short), Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(R.string.hint_nickname_plain);
        input.setSingleLine(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);

        new androidx.appcompat.app.AlertDialog.Builder(this, R.style.IrisDialog)
                .setTitle(R.string.new_dm_title)
                .setView(input)
                .setPositiveButton(R.string.open, (d, w) -> {
                    String nick = input.getText().toString().trim();
                    if (nick.isEmpty()) return;
                    if (!isValidNick(nick)) {
                        Toast.makeText(this, getString(R.string.invalid_nickname), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String serverName = currentServerName();
                    if (serverName == null) {
                        Toast.makeText(this, getString(R.string.no_connected_server), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    openDmTab(serverName, nick);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void generateSignalIdentityInBackground() {
        new Thread(() -> {
            try {
                signalStore.generateIdentity();
                runOnUiThread(() -> {
                    for (String key : new ArrayList<>(tabKeys)) {
                        if (!isDmTab(key)) continue;
                        int sl = key.indexOf('/');
                        if (sl < 0) continue;
                        String serverName = key.substring(0, sl);
                        String nick       = key.substring(sl + 1);
                        announceKeyTo(serverName, nick);
                    }
                });
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Signal identity generation failed", e);
            }
        }, "signal-keygen").start();
    }

    private void attachTabLongPress(View tabView, int index) {
        tabView.setOnLongClickListener(v -> {
            List<String> visible = visibleTabKeys();
            if (index < visible.size() && isDmTab(visible.get(index))) {
                showDmSignalMenu(visible.get(index));
                return true;
            }
            return false;
        });
    }

    private void refreshTabLabel(String key) {
        int idx = visibleTabKeys().indexOf(key);
        if (idx < 0) return;
        TabLayout.Tab tab = tabLayout.getTabAt(idx);
        if (tab != null) applyTabLabel(tab, key);
    }

    private void markTabUnread(String key) {
        if (key == null) return;
        synchronized (stateLock) {
            Integer current = unreadCounts.get(key);
            unreadCounts.put(key, current == null ? 1 : current + 1);
        }
        scheduleSave();
        runOnUiThread(() -> refreshTabLabel(key));
    }

    private void markTabRead(String key) {
        if (key == null) return;
        boolean changed;
        synchronized (stateLock) {
            changed = unreadCounts.remove(key) != null;
        }
        if (changed) {
            scheduleSave();
            refreshTabLabel(key);
        }
    }

    private void markCurrentTabRead() {
        if (viewPager == null || pagerAdapter == null) return;
        markTabRead(currentTabKey());
    }

    private static int dp(android.content.Context ctx, int value) {
        return Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, value, ctx.getResources().getDisplayMetrics()));
    }

    private static final int[] FP_COLORS = {
            0xFF64B5F6,
            0xFFFFFFFF,
    };

    private static android.text.SpannableString coloredFingerprint(
            String label, String fingerprint) {
        String full = label + fingerprint;
        android.text.SpannableString ss = new android.text.SpannableString(full);
        int offset = label.length();
        String[] tokens = fingerprint.split(" ");
        int pos = offset;
        for (int i = 0; i < tokens.length; i++) {
            int end = pos + tokens[i].length();
            ss.setSpan(
                    new android.text.style.ForegroundColorSpan(FP_COLORS[i % FP_COLORS.length]),
                    pos, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            pos = end + 1;
        }
        return ss;
    }

    private void showDmSignalMenu(String tabKey) {
        int slash = tabKey.indexOf('/');
        String nick = slash >= 0 ? tabKey.substring(slash + 1) : tabKey;

        boolean hasContact = signalStore.hasContactBundle(nick);
        String combinedFp  = hasContact ? signalStore.combinedFingerprint(nick) : null;
        boolean hasPending = signalStore.hasPendingIdentity(nick);
        String pendingFp   = hasPending ? signalStore.pendingFingerprint(nick) : null;

        java.util.List<String> keys = new java.util.ArrayList<>();
        java.util.List<CharSequence> labels = new java.util.ArrayList<>();

        if (hasPending) {
            if (pendingFp != null) {
                keys.add("pending_fp");
                labels.add(coloredFingerprint(
                        getString(R.string.pending_fingerprint_label), pendingFp));
            }
            keys.add("accept_pending");
            labels.add(getString(R.string.accept_changed_key));
            keys.add("reject_pending");
            labels.add(getString(R.string.reject_changed_key));
        }

        if (hasContact && combinedFp != null) {
            keys.add("combined_fp");
            labels.add(coloredFingerprint(getString(R.string.session_fingerprint_label), combinedFp));
        }
        keys.add("announce");
        labels.add(hasContact ? getString(R.string.reannounce_key_to, nick) : getString(R.string.announce_key_to, nick));
        if (hasContact) {
            keys.add("remove");
            labels.add(getString(R.string.remove_session_with, nick));
        }
        keys.add("close");
        labels.add(getString(R.string.close_tab));

        android.widget.ArrayAdapter<CharSequence> adapter =
                new android.widget.ArrayAdapter<CharSequence>(
                        this,
                        android.R.layout.simple_list_item_1,
                        labels) {
                    @Override
                    public android.view.View getView(int position, android.view.View convertView,
                                                     android.view.ViewGroup parent) {
                        android.view.View v = super.getView(position, convertView, parent);
                        android.widget.TextView tv = v.findViewById(android.R.id.text1);
                        tv.setText(labels.get(position));
                        tv.setPadding(
                                (int)(20 * getResources().getDisplayMetrics().density), tv.getPaddingTop(),
                                tv.getPaddingRight(), tv.getPaddingBottom());
                        return v;
                    }
                };

        new androidx.appcompat.app.AlertDialog.Builder(this, R.style.IrisDialog)
                .setTitle(getString(R.string.signal_menu_title, nick))
                .setAdapter(adapter, (d, which) -> {
                    String key = keys.get(which);
                    switch (key) {
                        case "pending_fp": {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                            if (cm != null && pendingFp != null) cm.setPrimaryClip(
                                    android.content.ClipData.newPlainText("fingerprint", pendingFp));
                            Toast.makeText(this, getString(R.string.fingerprint_copied), Toast.LENGTH_SHORT).show();
                            break;
                        }
                        case "accept_pending": {
                            String serverName = tabKey.contains("/")
                                    ? tabKey.substring(0, tabKey.indexOf('/')) : null;
                            String newCombined = signalStore.acceptPendingIdentity(nick);
                            refreshTabLabel(tabKey);
                            if (serverName != null) announceKeyTo(serverName, nick);
                            String info = getString(R.string.e2e_key_accepted, nick)
                                    + (newCombined != null
                                    ? getString(R.string.e2e_fingerprint_suffix, newCombined) : "");
                            appendToTab(tabKey, new ChatMessage(null, info, ChatMessage.Type.SYSTEM));
                            Toast.makeText(this,
                                    getString(R.string.e2e_key_accepted_toast, nick),
                                    Toast.LENGTH_SHORT).show();
                            break;
                        }
                        case "reject_pending": {
                            signalStore.rejectPendingIdentity(nick);
                            appendToTab(tabKey, new ChatMessage(
                                    null, getString(R.string.e2e_key_rejected, nick),
                                    ChatMessage.Type.SYSTEM));
                            Toast.makeText(this,
                                    getString(R.string.e2e_key_rejected_toast, nick),
                                    Toast.LENGTH_SHORT).show();
                            break;
                        }
                        case "combined_fp": {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                            if (cm != null) cm.setPrimaryClip(
                                    android.content.ClipData.newPlainText("fingerprint", combinedFp));
                            Toast.makeText(this, getString(R.string.fingerprint_copied), Toast.LENGTH_SHORT).show();
                            break;
                        }
                        case "announce": {
                            String serverName = tabKey.contains("/")
                                    ? tabKey.substring(0, tabKey.indexOf('/')) : null;
                            if (serverName != null) announceKeyTo(serverName, nick);
                            Toast.makeText(this, getString(R.string.key_announced, nick), Toast.LENGTH_SHORT).show();
                            break;
                        }
                        case "remove": {
                            signalStore.removeContact(nick);
                            refreshTabLabel(tabKey);
                            Toast.makeText(this, getString(R.string.session_removed), Toast.LENGTH_SHORT).show();
                            break;
                        }
                        case "close": {
                            closeDmTab(tabKey);
                            break;
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
    private void appendToTab(String key, ChatMessage msg) {
        long rowId = -1;
        if (msgDb != null) {
            rowId = msgDb.insertIfNew(key, msg);
            if (rowId == MessageDatabase.DUPLICATE_MESSAGE) {
                return;
            }
        }
        final long finalRowId = rowId;
        synchronized (stateLock) {
            if (!chatLogs.containsKey(key)) {
                chatLogs.put(key, new ArrayList<>());
                chatRowIds.put(key, new ArrayList<>());
                if (!tabKeys.contains(key)) {
                    tabKeys.add(key);
                    runOnUiThread(() -> pagerAdapter.notifyDataSetChanged());
                }
            }
            List<ChatMessage> log = chatLogs.get(key);
            if (log != null) log.add(msg);
            List<Long> ids = chatRowIds.get(key);
            if (ids != null) ids.add(finalRowId);
        }
        scheduleSave();

        boolean isSeen = isAppInForeground && key.equals(visibleTabKey);
        if (!isSeen && !msg.isSent() && !msg.isSystem()) {
            markTabUnread(key);
        }

        runOnUiThread(() -> pagerAdapter.deliverMessage(key, msg));
    }

    public void startReply(String tabKey, ChatMessage msg) {
        pendingReply       = msg;
        pendingReplyTabKey = tabKey;
        int slash = tabKey.indexOf('/');
        String serverName = slash >= 0 ? tabKey.substring(0, slash) : "";
        Server srv = knownServers.get(serverName);
        String nick = msg.isSent() ? (srv != null ? srv.getNickname() : "You") : msg.getNick();
        replyPreviewNick.setText(getString(R.string.replying_to, nick));
        String previewText = msg.getText();
        String imgUrl = extractImageUrl(previewText);
        if (imgUrl != null) previewText = previewText.replace(imgUrl, "").trim();
        if (previewText.isEmpty()) previewText = getString(R.string.image_placeholder);
        replyPreviewText.setText(previewText);
        replyPreviewBar.setVisibility(View.VISIBLE);
        chatInput.requestFocus();
    }

    private void clearReply() {
        pendingReply       = null;
        pendingReplyTabKey = null;
        replyPreviewBar.setVisibility(View.GONE);
    }

    public void deleteMessage(String tabKey, int position) {
        long rowId = -1;
        synchronized (stateLock) {
            List<ChatMessage> log = chatLogs.get(tabKey);
            if (log == null || position < 0 || position >= log.size()) return;
            log.remove(position);
            List<Long> ids = chatRowIds.get(tabKey);
            if (ids != null && position < ids.size()) {
                rowId = ids.remove(position);
            }
        }
        if (msgDb != null && rowId > 0) msgDb.delete(rowId);
    }

    private void scheduleSave() {
        saveHandler.removeCallbacks(saveRunnable);
        saveHandler.postDelayed(saveRunnable, SAVE_DEBOUNCE_MS);
    }

    private void saveHistoryNow() {
        try {
            JSONArray serverArr = new JSONArray();
            synchronized (stateLock) {
                for (Server s : knownServers.values()) serverArr.put(s.toJson());
            }
            crypto.putString(KEY_SAVED_SERVERS, serverArr.toString());

            JSONArray dmArr = new JSONArray();
            synchronized (stateLock) {
                for (String key : tabKeys) {
                    if (isDmTab(key)) dmArr.put(key);
                }
            }
            crypto.putString(KEY_SAVED_DMS, dmArr.toString());

            JSONObject unreadObj = new JSONObject();
            synchronized (stateLock) {
                for (Map.Entry<String, Integer> e : unreadCounts.entrySet()) {
                    if (e.getValue() != null && e.getValue() > 0) {
                        unreadObj.put(e.getKey(), (int) e.getValue());
                    }
                }
            }
            crypto.putString(KEY_UNREAD_COUNTS, unreadObj.toString());

        } catch (JSONException e) {
            android.util.Log.e("MainActivity", "saveHistory failed", e);
        }
    }

    private List<ChatMessage> loadChannelHistory(String serverName, String channel) {
        String key = tabKey(serverName, channel);

        List<ChatMessage> msgs = new ArrayList<>();
        List<Long>        ids  = new ArrayList<>();
        if (msgDb != null) {
            List<MessageDatabase.StoredMessage> stored =
                    msgDb.loadRecent(key, MessageDatabase.DEFAULT_PAGE_SIZE);
            for (MessageDatabase.StoredMessage sm : stored) {
                msgs.add(sm.message);
                ids.add(sm.id);
            }
        }
        synchronized (stateLock) {
            chatRowIds.put(key, ids);
        }
        return msgs;
    }

    public void loadOlderMessages(String key, java.util.function.Consumer<Integer> callback) {
        if (msgDb == null) { if (callback != null) runOnUiThread(() -> callback.accept(0)); return; }
        new Thread(() -> {
            long oldestId;
            synchronized (stateLock) {
                List<Long> ids = chatRowIds.get(key);
                oldestId = (ids == null || ids.isEmpty()) ? Long.MAX_VALUE : ids.get(0);
            }
            List<MessageDatabase.StoredMessage> older =
                    msgDb.loadPage(key, oldestId, MessageDatabase.DEFAULT_PAGE_SIZE);
            if (older.isEmpty()) {
                if (callback != null) runOnUiThread(() -> callback.accept(0));
                return;
            }
            synchronized (stateLock) {
                List<ChatMessage> log = chatLogs.get(key);
                List<Long>        ids = chatRowIds.get(key);
                if (log == null || ids == null) return;
                for (int i = older.size() - 1; i >= 0; i--) {
                    log.add(0, older.get(i).message);
                    ids.add(0, older.get(i).id);
                }
            }
            int added = older.size();
            if (callback != null) runOnUiThread(() -> callback.accept(added));
        }, "db-load-older").start();
    }

    private void restoreHistory() {
        String savedServersJson = crypto.getString(KEY_SAVED_SERVERS, null);
        if (savedServersJson == null) return;

        try {
            JSONArray arr = new JSONArray(savedServersJson);
            for (int i = 0; i < arr.length(); i++) {
                Server server = Server.fromJson(arr.getJSONObject(i));
                if (!isValidServerName(server.getName())) continue;
                knownServers.put(server.getName(), server);
                for (String ch : server.getChannels()) {
                    String key = tabKey(server.getName(), ch);
                    if (!chatLogs.containsKey(key)) {
                        chatLogs.put(key, loadChannelHistory(server.getName(), ch));
                        tabKeys.add(key);
                    }
                }
            }

            String savedDmsJson = crypto.getString(KEY_SAVED_DMS, null);
            if (savedDmsJson != null) {
                JSONArray dmArr = new JSONArray(savedDmsJson);
                for (int i = 0; i < dmArr.length(); i++) {
                    String key = dmArr.getString(i);
                    int slashPos = key.indexOf('/');
                    if (slashPos <= 0 || slashPos == key.length() - 1) continue;
                    String nick = key.substring(slashPos + 1);
                    if (nick.startsWith("#") || nick.startsWith("&")) continue;
                    if (!isValidNick(nick)) continue;
                    if (!chatLogs.containsKey(key)) {
                        String serverPart  = key.substring(0, slashPos);
                        List<ChatMessage> msgs = loadChannelHistory(serverPart, nick);
                        chatLogs.put(key, msgs);
                        tabKeys.add(key);
                    }
                }
            }

            String savedUnreadJson = crypto.getString(KEY_UNREAD_COUNTS, null);
            if (savedUnreadJson != null) {
                JSONObject unreadObj = new JSONObject(savedUnreadJson);
                synchronized (stateLock) {
                    java.util.Iterator<String> keysIt = unreadObj.keys();
                    while (keysIt.hasNext()) {
                        String key = keysIt.next();
                        if (!tabKeys.contains(key)) continue;
                        int count = unreadObj.optInt(key, 0);
                        if (count > 0) unreadCounts.put(key, count);
                    }
                }
            }

            if (!knownServers.isEmpty()) {
                pagerAdapter.notifyDataSetChanged();
                refreshServerLabel();
                statusLabel.setText(getString(R.string.status_offline_reconnect));
            }
        } catch (JSONException e) { android.util.Log.e("MainActivity", "JSON error", e); }
    }

    class ChannelPagerAdapter extends FragmentStateAdapter {

        private final Map<String, ChannelFragment> liveFragments = new HashMap<>();

        ChannelPagerAdapter(AppCompatActivity a) { super(a); }

        @Override public int getItemCount() { return visibleTabKeys().size(); }

        @Override
        public long getItemId(int position) {
            String key = visibleTabKeys().get(position);
            long h = 0xcbf29ce484222325L;
            for (int i = 0; i < key.length(); i++) {
                h ^= key.charAt(i);
                h *= 0x100000001b3L;
            }
            return h;
        }

        @Override
        public boolean containsItem(long itemId) {
            for (String key : visibleTabKeys()) {
                long h = 0xcbf29ce484222325L;
                for (int i = 0; i < key.length(); i++) {
                    h ^= key.charAt(i);
                    h *= 0x100000001b3L;
                }
                if (h == itemId) return true;
            }
            return false;
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            String key = visibleTabKeys().get(position);
            ChannelFragment f = new ChannelFragment();
            Bundle args = new Bundle();
            args.putString("tab_key", key);
            f.setArguments(args);
            liveFragments.put(key, f);
            return f;
        }

        void deliverMessage(String key, ChatMessage msg) {
            ChannelFragment f = liveFragments.get(key);
            if (f != null) f.addMessage(msg);
        }

        void notifyAccentChanged() {
            for (ChannelFragment f : liveFragments.values()) {
                if (f != null) f.notifyAccentChanged();
            }
        }

    }

    public static class ChannelFragment extends Fragment {

        private MainActivity      host;
        private String            tabKey;
        private List<ChatMessage> messages;
        private ChatAdapter       adapter;
        private RecyclerView      recyclerView;

        @Override
        public void onAttach(@NonNull android.content.Context context) {
            super.onAttach(context);
            host = (MainActivity) requireActivity();
        }

        @Override
        public void onDetach() {
            super.onDetach();
            host = null;
        }

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater,
                                 ViewGroup container, Bundle savedInstanceState) {
            View root = inflater.inflate(R.layout.fragment_channel, container, false);
            recyclerView = root.findViewById(R.id.channelRecycler);

            tabKey = getArguments() != null
                    ? getArguments().getString("tab_key", "") : "";

            List<ChatMessage> live = host != null ? host.chatLogs.get(tabKey) : null;
            messages = live != null ? new ArrayList<>(live) : new ArrayList<>();

            adapter = new ChatAdapter(messages);

            adapter.setOnDeleteListener(pos -> {
                if (host != null) host.deleteMessage(tabKey, pos);
                adapter.deleteAt(pos);
            });

            adapter.setOnReplyListener(msg -> {
                if (host != null) host.startReply(tabKey, msg);
            });

            adapter.setOnDmListener(nick -> {
                if (host != null) {
                    String serverName = tabKey.contains("/")
                            ? tabKey.substring(0, tabKey.indexOf('/')) : null;
                    if (serverName != null) host.openDmTab(serverName, nick);
                }
            });

            LinearLayoutManager llm = new LinearLayoutManager(getContext());
            llm.setStackFromEnd(true);
            recyclerView.setLayoutManager(llm);
            recyclerView.setAdapter(adapter);

            recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                private boolean loading = false;

                @Override
                public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    if (loading || dy >= 0) return;
                    if (llm.findFirstVisibleItemPosition() > 2) return;
                    if (host == null) return;
                    loading = true;
                    host.loadOlderMessages(tabKey, added -> {
                        loading = false;
                        if (added != null && added > 0) {
                            List<ChatMessage> live = host.chatLogs.get(tabKey);
                            if (live != null) {
                                messages.clear();
                                messages.addAll(live);
                                adapter.notifyDataSetChanged();
                                recyclerView.scrollToPosition(added);
                            }
                        }
                    });
                }
            });
            return root;
        }

        public void addMessage(ChatMessage msg) {
            if (adapter == null) return;
            messages.add(msg);
            adapter.notifyItemInserted(messages.size() - 1);
            recyclerView.scrollToPosition(messages.size() - 1);
        }

        public void notifyAccentChanged() {
            if (adapter != null) adapter.notifyDataSetChanged();
        }
    }
}