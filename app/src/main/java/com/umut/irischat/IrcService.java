package com.umut.irischat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.User;
import org.pircbotx.cap.EnableCapHandler;
import org.pircbotx.exception.IrcException;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.ConnectEvent;
import org.pircbotx.hooks.events.DisconnectEvent;
import org.pircbotx.hooks.events.JoinEvent;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.events.NoticeEvent;
import org.pircbotx.hooks.events.PartEvent;
import org.pircbotx.hooks.events.PrivateMessageEvent;
import org.pircbotx.hooks.events.QuitEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

public class IrcService extends Service {

    private static final String TAG        = "IrcService";
    private static final String CHANNEL_ID  = "irischat_conn";
    private static final int    NOTIF_ID    = 1;

    private static final String DM_CHANNEL_ID     = "irischat_dms";
    private static final int    DM_NOTIF_ID_BASE  = 1000;
    private static final long   DM_ALERT_THROTTLE_MS = 2_000L;

    public static final String EXTRA_DM_SERVER = "com.umut.irischat.extra.DM_SERVER";
    public static final String EXTRA_DM_NICK   = "com.umut.irischat.extra.DM_NICK";

    private static final long RECONNECT_DELAY_BASE_MS = 5_000L;
    private static final long RECONNECT_DELAY_MAX_MS   = 300_000L;
    private static final int  MAX_QUEUED_MSGS_PER_TAB  = 200;
    private static final int  MAX_INBOUND_MSG_BYTES    = 8192;
    private static final int  MAX_OUTBOUND_MSG_BYTES   = 400;

    private static final int  HISTORY_BACKFILL_COUNT      = 100;
    private static final int  HISTORY_BACKFILL_MAX         = 500;
    private static final long HISTORY_BACKFILL_WINDOW_MS  = 8_000L;
    private static final long MIN_HISTORY_REQUEST_INTERVAL_MS = 1_000L;
    private static final long HISTORY_REQUEST_STAGGER_MS = 75L;
    private static final long HISTORY_REQUEST_STAGGER_CAP_MS = 1_500L;

    private static final int  MAX_BACKFILL_CONTINUATIONS   = 40;
    private static final int  MAX_CHATHISTORY_FAIL_RETRIES = 4;
    private static final long CHATHISTORY_FAIL_RETRY_BASE_MS = 3_000L;

    private static final int  MAX_LIST_ENTRIES_PER_REQUEST = 5_000;
    private static final String RPL_LIST      = "322";
    private static final String RPL_LISTEND   = "323";

    public class LocalBinder extends Binder {
        IrcService getService() { return IrcService.this; }
    }

    private final IBinder binder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    public interface Listener {
        void onConnected(String serverName);
        void onDisconnected(String serverName);
        void onMessage(String serverName, String channel, String nick,
                       String text, String imageUrl);
        void onNotice(String serverName, String fromNick, String text);
        default void onMembersChanged(String serverName, String channel,
                                      List<String> sortedNicks) {}

        default void onChannelListStarted(String serverName) {}
        default void onChannelListEntry(String serverName, String channel,
                                        int userCount, String topic) {}
        default void onChannelListComplete(String serverName) {}
    }

    private volatile Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean appVisible = false;

    private final Map<String, Integer> dmNotifIds =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Integer> dmUnreadCounts =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> dmLastAlertMs =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicInteger nextDmNotifId = new AtomicInteger(DM_NOTIF_ID_BASE);

    private final Map<String, Long> lastSeenMs =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final String HISTORY_PREFS_NAME = "irc_chat_history_watermarks";
    private static final long WATERMARK_PERSIST_MIN_INTERVAL_MS = 3_000L;

    private SharedPreferences historyPrefs;
    private final Map<String, Long> lastPersistedMs =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String encodeWatermarkPrefKey(String rawKey) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(rawKey.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeWatermarkPrefKey(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private void loadPersistedWatermarks() {
        if (historyPrefs == null) return;
        for (Map.Entry<String, ?> e : historyPrefs.getAll().entrySet()) {
            Object v = e.getValue();
            if (!(v instanceof Long)) continue;
            try {
                lastSeenMs.put(decodeWatermarkPrefKey(e.getKey()), (Long) v);
            } catch (Exception ex) {
                Log.w(TAG, "Dropping unreadable persisted watermark: " + e.getKey());
            }
        }
    }

    private void persistWatermark(String rawKey, long timestampMs) {
        persistWatermark(rawKey, timestampMs, false);
    }

    private void persistWatermark(String rawKey, long timestampMs, boolean force) {
        if (historyPrefs == null) return;
        Long lastPersisted = lastPersistedMs.get(rawKey);
        if (!force && lastPersisted != null
                && (timestampMs - lastPersisted) < WATERMARK_PERSIST_MIN_INTERVAL_MS) {
            return;
        }
        lastPersistedMs.put(rawKey, timestampMs);
        historyPrefs.edit().putLong(encodeWatermarkPrefKey(rawKey), timestampMs).apply();
    }

    private void flushAllWatermarks() {
        if (historyPrefs == null) return;
        SharedPreferences.Editor editor = historyPrefs.edit();
        for (Map.Entry<String, Long> e : lastSeenMs.entrySet()) {
            editor.putLong(encodeWatermarkPrefKey(e.getKey()), e.getValue());
            lastPersistedMs.put(e.getKey(), e.getValue());
        }
        editor.commit();
    }

    private void purgePersistedWatermarksFor(String serverName) {
        if (historyPrefs == null) return;
        String prefix = serverName + "\u0000";
        SharedPreferences.Editor editor = historyPrefs.edit();
        boolean changed = false;
        for (String encodedKey : historyPrefs.getAll().keySet()) {
            try {
                if (decodeWatermarkPrefKey(encodedKey).startsWith(prefix)) {
                    editor.remove(encodedKey);
                    changed = true;
                }
            } catch (Exception ignored) {}
        }
        if (changed) editor.apply();
    }

    public static String dbTabKey(String serverName, String target) {
        return serverName + "/" + target;
    }

    private static String watermarkKey(String serverName, String target) {
        return serverName + "\u0000" + target.toLowerCase(java.util.Locale.ROOT);
    }

    private void recordLastSeen(String serverName, String target, long timestampMs) {
        String key = watermarkKey(serverName, target);
        lastSeenMs.put(key, timestampMs);
        persistWatermark(key, timestampMs);
    }

    private class TimeTaggingInputParser extends org.pircbotx.InputParser {
        volatile long   lastLineServerTimeMs = 0L;
        volatile String lastLineMsgId        = null;

        private final String      serverName;
        private final ServerState state;

        TimeTaggingInputParser(PircBotX bot, String serverName, ServerState state) {
            super(bot);
            this.serverName = serverName;
            this.state      = state;
        }

        @Override
        public void handleLine(String rawLine) throws IOException, IrcException {
            lastLineServerTimeMs = parseTagValue(rawLine, "time=", true);
            lastLineMsgId        = parseTagString(rawLine, "msgid=");

            String batchRef = parseTagString(rawLine, "batch=");
            if (batchRef != null) {
                trackChathistoryBatchLine(state, batchRef);
            }

            handleBatchControlLine(serverName, state, rawLine);
            handleChathistoryFailLine(serverName, state, rawLine);
            handleChannelListLine(serverName, rawLine);

            super.handleLine(rawLine);
        }

        private long parseTagValue(String rawLine, String prefix, boolean isTimestamp) {
            String v = parseTagString(rawLine, prefix);
            if (v == null) return 0L;
            if (!isTimestamp) return 0L;
            try {
                return java.time.Instant.parse(v).toEpochMilli();
            } catch (Exception e) {
                return 0L;
            }
        }

        private String parseTagString(String rawLine, String prefix) {
            if (rawLine == null || rawLine.isEmpty() || rawLine.charAt(0) != '@') return null;
            int spaceIdx = rawLine.indexOf(' ');
            String tagBlock = spaceIdx >= 0 ? rawLine.substring(1, spaceIdx) : rawLine.substring(1);
            for (String tag : tagBlock.split(";")) {
                if (tag.startsWith(prefix)) {
                    String v = tag.substring(prefix.length());
                    return v.isEmpty() ? null : v;
                }
            }
            return null;
        }
    }

    private static long extractServerTimeMs(ServerState st) {
        TimeTaggingInputParser parser = st.inputParser;
        if (parser != null && parser.lastLineServerTimeMs > 0L) {
            return parser.lastLineServerTimeMs;
        }
        return System.currentTimeMillis();
    }

    private static String extractServerMsgId(ServerState st) {
        TimeTaggingInputParser parser = st.inputParser;
        return parser != null ? parser.lastLineMsgId : null;
    }

    private static String stripTags(String rawLine) {
        if (rawLine == null) return null;
        if (rawLine.startsWith("@")) {
            int sp = rawLine.indexOf(' ');
            return sp >= 0 ? rawLine.substring(sp + 1) : null;
        }
        return rawLine;
    }

    private void trackChathistoryBatchLine(ServerState st, String batchRef) {
        if (st.chathistoryBatchTargets.containsKey(batchRef)) {
            st.chathistoryBatchCounts.merge(batchRef, 1, Integer::sum);
        }
    }

    private void handleBatchControlLine(String serverName, ServerState st, String rawLine) {
        String line = stripTags(rawLine);
        if (line == null || line.isEmpty()) return;

        String[] parts = line.split(" ");
        int idx = 0;
        if (parts.length > 0 && parts[0].startsWith(":")) idx = 1;
        if (parts.length <= idx || !parts[idx].equalsIgnoreCase("BATCH")) return;
        if (parts.length <= idx + 1) return;

        String refToken = parts[idx + 1];
        if (refToken.length() < 2) return;

        if (refToken.charAt(0) == '+') {
            if (parts.length <= idx + 3) return;
            String type = parts[idx + 2];
            if (!type.equalsIgnoreCase("chathistory")) return;
            String ref    = refToken.substring(1);
            String target = parts[idx + 3];
            st.chathistoryBatchTargets.put(ref, target);
            st.chathistoryBatchCounts.put(ref, 0);
        } else if (refToken.charAt(0) == '-') {
            String ref = refToken.substring(1);
            String target = st.chathistoryBatchTargets.remove(ref);
            Integer count = st.chathistoryBatchCounts.remove(ref);
            if (target != null && count != null) {
                onChathistoryBatchComplete(serverName, st, target, count);
            }
        }
    }

    private void handleChathistoryFailLine(String serverName, ServerState st, String rawLine) {
        String line = stripTags(rawLine);
        if (line == null || line.isEmpty()) return;

        String[] parts = line.split(" ");
        int idx = (parts.length > 0 && parts[0].startsWith(":")) ? 1 : 0;
        if (parts.length <= idx + 1) return;
        if (!parts[idx].equalsIgnoreCase("FAIL")) return;
        if (!parts[idx + 1].equalsIgnoreCase("CHATHISTORY")) return;

        Log.w(TAG, "CHATHISTORY FAIL on " + serverName + ": " + rawLine);

        java.util.Set<String> targets = new java.util.HashSet<>(st.inFlightChathistoryTargets);
        if (targets.isEmpty()) return;
        for (String target : targets) {
            st.inFlightChathistoryTargets.remove(target);
            scheduleChathistoryFailRetry(serverName, st, target);
        }
    }

    private void handleChannelListLine(String serverName, String rawLine) {
        String line = stripTags(rawLine);
        if (line == null || line.isEmpty()) return;

        String[] parts = line.split(" ");
        int idx = (parts.length > 0 && parts[0].startsWith(":")) ? 1 : 0;
        if (parts.length <= idx) return;

        String code = parts[idx];
        if (code.equals(RPL_LIST)) {
            if (parts.length <= idx + 3) return;
            String channel = parts[idx + 2];
            if (channel == null || channel.isEmpty()) return;

            int userCount;
            try { userCount = Integer.parseInt(parts[idx + 3]); }
            catch (NumberFormatException e) { userCount = 0; }

            String topic = "";
            int chanPos = line.indexOf(channel);
            int topicIdx = chanPos >= 0 ? line.indexOf(" :", chanPos) : -1;
            if (topicIdx >= 0) topic = line.substring(topicIdx + 2);

            int seen = listEntryCount.merge(serverName, 1, Integer::sum);
            if (seen > MAX_LIST_ENTRIES_PER_REQUEST) return;

            final String ch    = channel;
            final int    uc    = userCount;
            final String tp    = topic;
            Listener l = listener;
            if (l != null) mainHandler.post(() -> l.onChannelListEntry(serverName, ch, uc, tp));

        } else if (code.equals(RPL_LISTEND)) {
            listEntryCount.remove(serverName);
            Listener l = listener;
            if (l != null) mainHandler.post(() -> l.onChannelListComplete(serverName));
        }
    }

    private void onChathistoryBatchComplete(String serverName, ServerState st, String target, int count) {
        st.inFlightChathistoryTargets.remove(target);
        String rateKey = watermarkKey(serverName, target);
        chathistoryFailRetryCount.remove(rateKey);

        if (count < HISTORY_BACKFILL_MAX) {
            backfillContinuationCount.remove(rateKey);
            return;
        }

        int continuations = backfillContinuationCount.merge(rateKey, 1, Integer::sum);
        if (continuations > MAX_BACKFILL_CONTINUATIONS) {
            Log.w(TAG, "CHATHISTORY backfill for " + rateKey
                    + " exceeded continuation limit (" + MAX_BACKFILL_CONTINUATIONS
                    + "); stopping to avoid a runaway loop.");
            return;
        }

        Log.i(TAG, "CHATHISTORY batch for " + rateKey + " hit the " + HISTORY_BACKFILL_MAX
                + "-message cap (continuation #" + continuations + ") — requesting next page");
        mainHandler.post(() -> requestHistory(serverName, target));
    }

    private void scheduleChathistoryFailRetry(String serverName, ServerState st, String target) {
        String rateKey = watermarkKey(serverName, target);
        int attempt = chathistoryFailRetryCount.merge(rateKey, 1, Integer::sum);
        if (attempt > MAX_CHATHISTORY_FAIL_RETRIES) {
            Log.w(TAG, "CHATHISTORY for " + rateKey + " failed " + attempt
                    + " times in a row; giving up until the next reconnect/tab-open.");
            return;
        }
        long delay = CHATHISTORY_FAIL_RETRY_BASE_MS * attempt;
        mainHandler.postDelayed(() -> {
            if (!st.shouldRun.get() || !st.connected.get()) return;
            lastHistoryRequestMs.remove(rateKey);
            requestHistory(serverName, target);
        }, delay);
    }

    private static boolean isHistServ(String nick) {
        return nick != null && nick.equalsIgnoreCase("HistServ");
    }

    private static final long DEDUPE_WINDOW_MS = 5 * 60_000L;
    private final Map<String, Long> recentMessageFingerprints =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicInteger dedupeInsertCount = new AtomicInteger(0);

    private boolean isDuplicateMessage(String serverName, String target, String nick, String text) {
        return isDuplicateMessage(serverName, target, nick, text, null, 0L);
    }

    private boolean isDuplicateMessage(String serverName, String target, String nick, String text,
                                       String msgId, long timestampMs) {
        String rateKey = watermarkKey(serverName, target);
        String variablePart = (msgId != null && !msgId.isEmpty())
                ? "msgid\u0000" + msgId
                : nick + "\u0000" + (text != null ? text : "");
        String key = rateKey + "\u0000" + fingerprintHash(variablePart);
        long now = System.currentTimeMillis();
        Long prevExpiry = recentMessageFingerprints.put(key, now + DEDUPE_WINDOW_MS);
        boolean duplicate = prevExpiry != null && now < prevExpiry;

        if (!duplicate && timestampMs > 0) {
            try {
                duplicate = MessageDatabase.get(this)
                        .existsAtTimestamp(dbTabKey(serverName, target), nick, text, timestampMs);
            } catch (Exception e) {
                Log.w(TAG, "isDuplicateMessage: persistent dedupe check failed for " + rateKey, e);
            }
        }

        if (dedupeInsertCount.incrementAndGet() % 200 == 0) {
            long cutoff = now;
            recentMessageFingerprints.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
        return duplicate;
    }

    private static String fingerprintHash(String raw) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            return raw;
        }
    }

    private static final java.time.format.DateTimeFormatter IRC_HISTORY_TS_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(java.time.ZoneOffset.UTC);

    private static String formatHistoryTimestamp(long epochMs) {
        return IRC_HISTORY_TS_FMT.format(java.time.Instant.ofEpochMilli(epochMs));
    }

    public void setAppVisible(boolean visible) {
        appVisible = visible;
        if (visible) cancelDmNotifications();
    }

    public void cancelDmNotifications() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            for (Integer id : dmNotifIds.values()) nm.cancel(id);
        }
        dmUnreadCounts.clear();
        dmLastAlertMs.clear();
    }

    public void setListener(Listener l) {
        this.listener = l;
        if (l != null) drainMessageQueue();
    }

    private static class QueuedMessage {
        final String serverName, channel, nick, text, imageUrl;
        QueuedMessage(String s, String c, String n, String t, String i) {
            serverName = s; channel = c; nick = n; text = t; imageUrl = i;
        }
    }

    private static class QueuedNotice {
        final String serverName, fromNick, text;
        QueuedNotice(String s, String n, String t) { serverName = s; fromNick = n; text = t; }
    }

    private final LinkedBlockingQueue<QueuedMessage> messageQueue =
            new LinkedBlockingQueue<>(MAX_QUEUED_MSGS_PER_TAB * 10);
    private final LinkedBlockingQueue<QueuedNotice> noticeQueue =
            new LinkedBlockingQueue<>(200);

    private void enqueueOrDeliver(String serverName, String channel,
                                  String nick, String text, String imageUrl) {
        Listener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onMessage(serverName, channel, nick, text, imageUrl));
        } else {
            if (!messageQueue.offer(new QueuedMessage(serverName, channel, nick, text, imageUrl))) {
                messageQueue.poll();
                messageQueue.offer(new QueuedMessage(serverName, channel, nick, text, imageUrl));
            }
        }
    }

    private void enqueueOrDeliverNotice(String serverName, String fromNick, String text) {
        Listener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onNotice(serverName, fromNick, text));
        } else {
            noticeQueue.offer(new QueuedNotice(serverName, fromNick, text));
        }
    }

    private void drainMessageQueue() {
        Listener l = listener;
        if (l == null) return;
        QueuedMessage qm;
        while ((qm = messageQueue.poll()) != null) {
            final QueuedMessage msg = qm;
            mainHandler.post(() -> l.onMessage(msg.serverName, msg.channel,
                    msg.nick, msg.text, msg.imageUrl));
        }
        QueuedNotice qn;
        while ((qn = noticeQueue.poll()) != null) {
            final QueuedNotice n = qn;
            mainHandler.post(() -> l.onNotice(n.serverName, n.fromNick, n.text));
        }
    }

    private static class ServerState {
        final Server server;
        volatile PircBotX bot;
        final AtomicBoolean connected = new AtomicBoolean(false);
        final AtomicBoolean shouldRun = new AtomicBoolean(true);
        final AtomicBoolean suppressNextDisconnectReconnect = new AtomicBoolean(false);
        final AtomicInteger retryCount = new AtomicInteger(0);
        volatile Future<?> botFuture;
        volatile Future<?> retryFuture;
        volatile long historyBackfillUntilMs = 0L;
        final AtomicInteger historyRequestSeq = new AtomicInteger(0);
        volatile TimeTaggingInputParser inputParser;

        final Map<String, String>  chathistoryBatchTargets = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, Integer> chathistoryBatchCounts  = new java.util.concurrent.ConcurrentHashMap<>();

        final java.util.Set<String> inFlightChathistoryTargets =
                java.util.concurrent.ConcurrentHashMap.newKeySet();

        ServerState(Server s) { this.server = s; }
    }

    private final Map<String, ServerState> states =
            Collections.synchronizedMap(new LinkedHashMap<>());

    private ExecutorService botExecutor;
    private ExecutorService workerExecutor;
    private PowerManager.WakeLock wakeLock;

    private void safeExecute(ExecutorService executor, String opName, Runnable task) {
        try {
            executor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Log.w(TAG, opName + " rejected — executor queue full or shut down");
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(60_000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private void registerNetworkCallback() {
        connectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                Log.d(TAG, "Network available – checking connections");
                synchronized (states) {
                    for (ServerState st : states.values()) {
                        if (!st.connected.get() && st.shouldRun.get()) {
                            st.retryCount.set(0);
                            scheduleReconnect(st, 1_000L);
                        }
                    }
                }
            }
        };

        NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        connectivityManager.registerNetworkCallback(req, networkCallback);
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); }
            catch (Exception ignored) {}
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        historyPrefs = getSharedPreferences(HISTORY_PREFS_NAME, MODE_PRIVATE);
        loadPersistedWatermarks();

        botExecutor = new java.util.concurrent.ThreadPoolExecutor(
                0, 16, 60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.SynchronousQueue<>(),
                r -> { Thread t = new Thread(r); t.setDaemon(true);
                    t.setName("irc-bot-" + t.getId()); return t; });

        workerExecutor = new java.util.concurrent.ThreadPoolExecutor(
                1, 8, 60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(256),
                r -> { Thread t = new Thread(r); t.setDaemon(true);
                    t.setName("irc-worker-" + t.getId()); return t; });

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IrisChat:reconnect");
            wakeLock.setReferenceCounted(false);
        }

        registerNetworkCallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID,
                    buildNotification(getString(R.string.notif_idle)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_idle)));
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterNetworkCallback();
        flushAllWatermarks();
        disconnectAll();

        botExecutor.shutdown();
        workerExecutor.shutdown();
        try {
            if (!workerExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                workerExecutor.shutdownNow();
            }
            if (!botExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                botExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            botExecutor.shutdownNow();
            workerExecutor.shutdownNow();
        }

        releaseWakeLock();
    }

    public boolean isConnected(String serverName) {
        ServerState st = states.get(serverName);
        return st != null && st.connected.get();
    }

    public boolean isAnyConnected() {
        synchronized (states) {
            for (ServerState st : states.values()) if (st.connected.get()) return true;
        }
        return false;
    }

    public List<Server> getConnectedServers() {
        List<Server> list = new ArrayList<>();
        synchronized (states) {
            for (ServerState st : states.values())
                if (st.connected.get()) list.add(st.server);
        }
        return list;
    }

    public List<Server> getAllServers() {
        List<Server> list = new ArrayList<>();
        synchronized (states) {
            for (ServerState st : states.values()) list.add(st.server);
        }
        return list;
    }

    public List<String> getChannelMembers(String serverName, String channel) {
        ServerState st = states.get(serverName);
        if (st == null || !st.connected.get() || st.bot == null) return Collections.emptyList();
        try {
            org.pircbotx.Channel ch = st.bot.getUserChannelDao().getChannel(channel);
            if (ch == null) return Collections.emptyList();
            TreeSet<String> sorted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            String myNick = st.server.getNickname();
            for (User u : ch.getUsers()) {
                String nick = u.getNick();
                if (!nick.equalsIgnoreCase(myNick)) sorted.add(nick);
            }
            return new ArrayList<>(sorted);
        } catch (Exception e) {
            Log.w(TAG, "getChannelMembers error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    public void connect(Server server) {
        String name = server.getName();
        ServerState existing = states.get(name);
        if (existing != null && existing.connected.get()) return;

        stopServerState(name);

        ServerState st = new ServerState(server);
        states.put(name, st);
        refreshNotification();

        launchBot(st);
    }

    public void disconnectServer(String serverName) {
        stopServerState(serverName);
        states.remove(serverName);
        purgeHistoryStateFor(serverName);
        refreshNotification();
    }

    private void purgeHistoryStateFor(String serverName) {
        String prefix = serverName + "\u0000";
        lastSeenMs.keySet().removeIf(k -> k.startsWith(prefix));
        lastHistoryRequestMs.keySet().removeIf(k -> k.startsWith(prefix));
        pendingHistoryRequest.keySet().removeIf(k -> k.startsWith(prefix));
        recentMessageFingerprints.keySet().removeIf(k -> k.startsWith(prefix));
        lastPersistedMs.keySet().removeIf(k -> k.startsWith(prefix));
        backfillContinuationCount.keySet().removeIf(k -> k.startsWith(prefix));
        chathistoryFailRetryCount.keySet().removeIf(k -> k.startsWith(prefix));
        listEntryCount.remove(serverName);
        purgePersistedWatermarksFor(serverName);
    }

    public void disconnectAll() {
        synchronized (states) {
            for (String name : new ArrayList<>(states.keySet())) stopServerState(name);
            states.clear();
        }
        lastHistoryRequestMs.clear();
        pendingHistoryRequest.clear();
        recentMessageFingerprints.clear();
        listEntryCount.clear();
        refreshNotification();
    }

    public boolean sendMessage(String serverName, String channel, String message) {
        ServerState st = states.get(serverName);
        if (st == null || !st.connected.get() || st.bot == null) return false;
        final String sanitized = stripIrcInjection(message);
        final String safe = truncateToByteLimit(sanitized, MAX_OUTBOUND_MSG_BYTES);
        safeExecute(workerExecutor, "sendMessage", () -> {
            try { st.bot.send().message(channel, safe); }
            catch (Exception e) { Log.e(TAG, "sendMessage error [" + serverName + "]", e); }
        });
        return true;
    }

    public boolean sendNotice(String serverName, String targetNick, String text) {
        ServerState st = states.get(serverName);
        if (st == null || !st.connected.get() || st.bot == null) return false;
        final String sanitized = stripIrcInjection(text);
        final String safe = truncateToByteLimit(sanitized, MAX_OUTBOUND_MSG_BYTES);
        safeExecute(workerExecutor, "sendNotice", () -> {
            try { st.bot.send().notice(targetNick, safe); }
            catch (Exception e) { Log.e(TAG, "sendNotice error [" + serverName + "]", e); }
        });
        return true;
    }

    public boolean sendRaw(String serverName, String rawLine) {
        ServerState st = states.get(serverName);
        if (st == null || !st.connected.get() || st.bot == null) return false;
        if (rawLine == null) return false;

        final String sanitized = stripIrcInjection(rawLine).trim();
        if (sanitized.isEmpty()) return false;

        final String safe = truncateToByteLimit(sanitized, MAX_OUTBOUND_MSG_BYTES);
        safeExecute(workerExecutor, "sendRaw", () -> {
            try { st.bot.sendRaw().rawLine(safe); }
            catch (Exception e) { Log.e(TAG, "sendRaw error [" + serverName + "]", e); }
        });
        return true;
    }

    private final Map<String, Long> lastHistoryRequestMs =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Boolean> pendingHistoryRequest =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final Map<String, Integer> backfillContinuationCount =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final Map<String, Integer> chathistoryFailRetryCount =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final Map<String, Integer> listEntryCount =
            new java.util.concurrent.ConcurrentHashMap<>();

    public boolean requestChannelList(String serverName) {
        ServerState st = states.get(serverName);
        if (st == null || !st.connected.get() || st.bot == null) return false;

        listEntryCount.remove(serverName);

        Listener l = listener;
        if (l != null) mainHandler.post(() -> l.onChannelListStarted(serverName));

        safeExecute(workerExecutor, "requestChannelList[" + serverName + "]", () -> {
            PircBotX bot = st.bot;
            if (bot == null || !st.connected.get()) return;
            try {
                bot.sendRaw().rawLine("LIST");
            } catch (Exception e) {
                Log.w(TAG, "requestChannelList error [" + serverName + "]", e);
            }
        });
        return true;
    }

    private void backfillKnownDmTargets(String serverName) {
        ServerState st = states.get(serverName);
        if (st == null) return;
        final java.util.Set<String> channelSet = new java.util.HashSet<>();
        for (String ch : st.server.getChannels()) channelSet.add(ch.toLowerCase(java.util.Locale.ROOT));

        safeExecute(workerExecutor, "backfillKnownDmTargets[" + serverName + "]", () -> {
            List<String> targets;
            try {
                targets = MessageDatabase.get(this).getKnownTargetsForServer(serverName);
            } catch (Exception e) {
                Log.w(TAG, "backfillKnownDmTargets: failed to list targets for " + serverName, e);
                return;
            }
            for (String target : targets) {
                if (target == null || target.isEmpty()) continue;
                String lower = target.toLowerCase(java.util.Locale.ROOT);
                if (channelSet.contains(lower) || lower.startsWith("#") || lower.startsWith("&")) {
                    continue;
                }
                mainHandler.post(() -> requestHistory(serverName, target));
            }
        });
    }

    public boolean requestHistory(String serverName, String target) {
        ServerState st = states.get(serverName);
        if (st == null || !st.connected.get() || st.bot == null) return false;
        if (target == null || target.isEmpty()) return false;

        final String safeTarget = stripIrcInjection(target).trim();
        if (safeTarget.isEmpty() || safeTarget.indexOf(' ') >= 0) return false;

        final String rateKey = watermarkKey(serverName, safeTarget);

        long delay = Math.min(
                st.historyRequestSeq.getAndIncrement() * HISTORY_REQUEST_STAGGER_MS,
                HISTORY_REQUEST_STAGGER_CAP_MS);

        mainHandler.postDelayed(
                () -> fireHistoryRequest(st, serverName, safeTarget, rateKey), delay);
        return true;
    }

    private void fireHistoryRequest(ServerState st, String serverName,
                                    String safeTarget, String rateKey) {
        if (!st.shouldRun.get()) return;

        PircBotX bot = st.bot;
        if (bot == null || !st.connected.get()) {
            return;
        }

        long now = System.currentTimeMillis();
        Long lastReq = lastHistoryRequestMs.get(rateKey);
        if (lastReq != null && (now - lastReq) < MIN_HISTORY_REQUEST_INTERVAL_MS) {
            long remaining = MIN_HISTORY_REQUEST_INTERVAL_MS - (now - lastReq);
            if (pendingHistoryRequest.putIfAbsent(rateKey, Boolean.TRUE) == null) {
                mainHandler.postDelayed(() -> {
                    pendingHistoryRequest.remove(rateKey);
                    fireHistoryRequest(st, serverName, safeTarget, rateKey);
                }, remaining + 50);
            }
            return;
        }
        lastHistoryRequestMs.put(rateKey, now);
        pendingHistoryRequest.remove(rateKey);

        st.historyBackfillUntilMs = now + HISTORY_BACKFILL_WINDOW_MS;

        Long since = lastSeenMs.get(rateKey);
        if (since == null) {
            try {
                long dbMax = MessageDatabase.get(this)
                        .getMaxTimestampForTab(dbTabKey(serverName, safeTarget));
                if (dbMax > 0) {
                    since = dbMax;
                    lastSeenMs.put(rateKey, since);
                    persistWatermark(rateKey, since, true);
                }
            } catch (Exception e) {
                Log.w(TAG, "requestHistory: failed to seed watermark from DB for "
                        + rateKey, e);
            }
        }

        final String query;
        if (since != null) {
            String ts = formatHistoryTimestamp(since - 1);
            query = "CHATHISTORY AFTER " + safeTarget + " timestamp=" + ts
                    + " " + HISTORY_BACKFILL_MAX;
        } else {
            query = "CHATHISTORY LATEST " + safeTarget + " * " + HISTORY_BACKFILL_COUNT;
        }

        safeExecute(workerExecutor, "requestHistory[" + serverName + "]", () -> {
            PircBotX bot2 = st.bot;
            if (bot2 == null || !st.connected.get()) return;
            try {
                st.inFlightChathistoryTargets.add(safeTarget);
                bot2.sendRaw().rawLine(query);
            } catch (Exception e) {
                st.inFlightChathistoryTargets.remove(safeTarget);
                Log.w(TAG, "requestHistory error [" + serverName + "]", e);
            }
        });
    }

    private static String stripIrcInjection(String s) {
        if (s == null) return null;
        return s.replace("\r", "").replace("\n", "");
    }

    private static final String TRUNCATION_MARKER = " […truncated]";

    private static String truncateOversizedInbound(String text, int maxBytes) {
        if (text == null) return null;
        String truncated = truncateToByteLimit(text, maxBytes);
        return truncated.length() < text.length() ? truncated + TRUNCATION_MARKER : truncated;
    }

    private static String truncateToByteLimit(String s, int maxBytes) {
        if (s == null) return null;
        byte[] encoded = s.getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= maxBytes) return s;
        int end = Math.min(maxBytes, encoded.length - 1);
        while (end > 0 && (encoded[end] & 0xC0) == 0x80) end--;
        return new String(encoded, 0, end, StandardCharsets.UTF_8);
    }

    private void stopServerState(String name) {
        ServerState st = states.get(name);
        if (st == null) return;
        st.shouldRun.set(false);
        if (st.retryFuture != null) st.retryFuture.cancel(true);
        PircBotX bot = st.bot;
        if (bot != null) {
            safeExecute(workerExecutor, "close[" + name + "]", () -> {
                try { bot.close(); } catch (Exception ignored) {}
            });
            st.bot = null;
        }
        st.connected.set(false);
        st.chathistoryBatchTargets.clear();
        st.chathistoryBatchCounts.clear();
        st.inFlightChathistoryTargets.clear();
    }

    private void notifyMembersChanged(String serverName, String channel, PircBotX bot,
                                      String myNick) {
        Listener l = listener;
        if (l == null) return;
        try {
            org.pircbotx.Channel ch = bot.getUserChannelDao().getChannel(channel);
            if (ch == null) return;
            TreeSet<String> sorted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (User u : ch.getUsers()) {
                String nick = u.getNick();
                if (!nick.equalsIgnoreCase(myNick)) sorted.add(nick);
            }
            List<String> list = new ArrayList<>(sorted);
            mainHandler.post(() -> l.onMembersChanged(serverName, channel, list));
        } catch (Exception e) {
            Log.w(TAG, "notifyMembersChanged error: " + e.getMessage());
        }
    }

    private void launchBot(ServerState st) {
        String name   = st.server.getName();
        String myNick = st.server.getNickname();

        try {
            Configuration.Builder builder = new Configuration.Builder()
                    .setName(myNick)
                    .addServer(st.server.getHost(), st.server.getPort())
                    .setCapEnabled(true)
                    .setSocketTimeout(120_000)
                    .setAutoReconnect(false)
                    .setBotFactory(new Configuration.BotFactory() {
                        @Override
                        public org.pircbotx.InputParser createInputParser(PircBotX bot) {
                            TimeTaggingInputParser parser =
                                    new TimeTaggingInputParser(bot, name, st);
                            st.inputParser = parser;
                            return parser;
                        }
                    })
                    .addListener(new ListenerAdapter() {

                        @Override
                        public void onConnect(ConnectEvent event) {
                            st.connected.set(true);
                            st.retryCount.set(0);
                            st.historyRequestSeq.set(0);
                            st.chathistoryBatchTargets.clear();
                            st.chathistoryBatchCounts.clear();
                            releaseWakeLock();
                            refreshNotification();
                            Listener l = listener;
                            if (l != null) mainHandler.post(() -> l.onConnected(name));
                            Log.i(TAG, "Connected to " + name);

                            backfillKnownDmTargets(name);
                        }

                        @Override
                        public void onDisconnect(DisconnectEvent event) {
                            st.connected.set(false);
                            refreshNotification();
                            Listener l = listener;
                            if (l != null) mainHandler.post(() -> l.onDisconnected(name));
                            Log.i(TAG, "Disconnected from " + name);

                            if (st.shouldRun.get()) {
                                if (st.suppressNextDisconnectReconnect.getAndSet(false)) {
                                    Log.d(TAG, "Skipping reconnect for intentional close: " + name);
                                } else {
                                    long delay = nextDelay(st.retryCount.getAndIncrement());
                                    Log.i(TAG, "Reconnect " + name + " in " + delay + " ms");
                                    scheduleReconnect(st, delay);
                                }
                            }
                        }

                        @Override
                        public void onMessage(MessageEvent event) {
                            String channel = event.getChannel().getName();
                            String nick = event.getUser().getNick();
                            String text = event.getMessage();

                            if (isHistServ(nick)) return;

                            long serverTimeMs = extractServerTimeMs(st);
                            recordLastSeen(name, channel, serverTimeMs);

                            if (text != null && text.getBytes(StandardCharsets.UTF_8).length
                                    > MAX_INBOUND_MSG_BYTES) {
                                Log.w(TAG, "onMessage: oversized message from " + nick + " truncated");
                                text = truncateOversizedInbound(text, MAX_INBOUND_MSG_BYTES);
                            }

                            if (nick.equalsIgnoreCase(myNick)) return;

                            if (isDuplicateMessage(name, channel, nick, text,
                                    extractServerMsgId(st), serverTimeMs)) return;

                            String imgUrl = MainActivity.extractImageUrl(text);
                            String display = imgUrl != null
                                    ? text.replace(imgUrl, "").trim() : text;
                            enqueueOrDeliver(name, channel, nick, display, imgUrl);
                        }

                        @Override
                        public void onPrivateMessage(PrivateMessageEvent event) {
                            String nick = event.getUser().getNick();
                            String text = event.getMessage();

                            if (isHistServ(nick)) return;

                            long serverTimeMs = extractServerTimeMs(st);
                            recordLastSeen(name, nick, serverTimeMs);

                            if (text != null && text.getBytes(StandardCharsets.UTF_8).length
                                    > MAX_INBOUND_MSG_BYTES) {
                                Log.w(TAG, "onPrivateMessage: oversized message from " + nick + " truncated");
                                text = truncateOversizedInbound(text, MAX_INBOUND_MSG_BYTES);
                            }

                            if (nick.equalsIgnoreCase(myNick)) return;

                            if (isDuplicateMessage(name, nick, nick, text,
                                    extractServerMsgId(st), serverTimeMs)) return;

                            if (SignalStore.isSignalMessage(text)) {
                                enqueueOrDeliver(name, nick, nick, text, null);
                                maybeNotifyDm(name, nick, null, null, true);
                                return;
                            }

                            String imgUrl = MainActivity.extractImageUrl(text);
                            String display = imgUrl != null
                                    ? text.replace(imgUrl, "").trim() : text;
                            enqueueOrDeliver(name, nick, nick, display, imgUrl);
                            maybeNotifyDm(name, nick, display, imgUrl, false);
                        }

                        @Override
                        public void onNotice(NoticeEvent event) {
                            if (event.getUser() == null) return;
                            String nick = event.getUser().getNick();
                            if (nick == null || nick.isEmpty()) return;
                            if (isHistServ(nick)) return;
                            if (nick.equalsIgnoreCase(myNick)) return;
                            String notice = event.getNotice();

                            if (notice != null && notice.getBytes(StandardCharsets.UTF_8).length
                                    > MAX_INBOUND_MSG_BYTES) {
                                Log.w(TAG, "onNotice: oversized notice from " + nick + " truncated");
                                notice = truncateOversizedInbound(notice, MAX_INBOUND_MSG_BYTES);
                            }

                            enqueueOrDeliverNotice(name, nick, notice);
                        }

                        @Override
                        public void onJoin(JoinEvent event) {
                            String joinedNick =
                                    event.getUser() != null ? event.getUser().getNick() : null;
                            if (joinedNick != null && joinedNick.equalsIgnoreCase(myNick)) {
                                requestHistory(name, event.getChannel().getName());
                            }
                            mainHandler.postDelayed(() -> {
                                if (st.bot != null)
                                    notifyMembersChanged(name,
                                            event.getChannel().getName(), st.bot, myNick);
                            }, 300);
                        }

                        @Override
                        public void onPart(PartEvent event) {
                            mainHandler.postDelayed(() -> {
                                if (st.bot != null)
                                    notifyMembersChanged(name,
                                            event.getChannel().getName(), st.bot, myNick);
                            }, 300);
                        }

                        @Override
                        public void onQuit(QuitEvent event) {
                            mainHandler.postDelayed(() -> {
                                if (st.bot == null) return;
                                for (String ch : st.server.getChannels()) {
                                    notifyMembersChanged(name, ch, st.bot, myNick);
                                }
                            }, 300);
                        }
                    });

            if (st.server.isTls()) {
                SSLSocketFactory sslSF = HttpsURLConnection.getDefaultSSLSocketFactory();
                builder.setSocketFactory(sslSF);
            }

            for (String ch : st.server.getChannels()) builder.addAutoJoinChannel(ch);
            if (st.server.hasPassword()) builder.setServerPassword(st.server.getPassword());

            builder.addCapHandler(new EnableCapHandler("server-time"));
            builder.addCapHandler(new EnableCapHandler("message-tags"));
            builder.addCapHandler(new EnableCapHandler("draft/chathistory"));
            builder.addCapHandler(new EnableCapHandler("batch"));

            if (st.server.hasSasl()) {
                final String saslLogin = st.server.getSaslLogin();
                final String saslPass  = st.server.getSaslPassword();

                builder.addCapHandler(new org.pircbotx.cap.CapHandler() {
                    private volatile boolean done = false;

                    @Override
                    public boolean handleLS(org.pircbotx.PircBotX bot,
                                            com.google.common.collect.ImmutableList<String> capabilities)
                            throws org.pircbotx.exception.CAPException {
                        boolean hasSasl = capabilities.stream()
                                .anyMatch(c -> c.equals("sasl") || c.startsWith("sasl="));
                        if (hasSasl) {
                            bot.sendRaw().rawLine("CAP REQ :sasl");
                        } else {
                            Log.w(TAG, "Server " + name + " does not advertise SASL");
                            done = true;
                            return true;
                        }
                        return false;
                    }

                    @Override
                    public boolean handleACK(org.pircbotx.PircBotX bot,
                                             com.google.common.collect.ImmutableList<String> capabilities)
                            throws org.pircbotx.exception.CAPException {
                        boolean ackSasl = capabilities.stream()
                                .anyMatch(c -> c.equals("sasl") || c.startsWith("sasl"));
                        if (ackSasl) {
                            bot.sendRaw().rawLine("AUTHENTICATE PLAIN");
                        }
                        return false;
                    }

                    @Override
                    public boolean handleNAK(org.pircbotx.PircBotX bot,
                                             com.google.common.collect.ImmutableList<String> capabilities)
                            throws org.pircbotx.exception.CAPException {
                        Log.w(TAG, "Server " + name + " NAK'd SASL cap");
                        done = true;
                        return true;
                    }

                    @Override
                    public boolean handleUnknown(org.pircbotx.PircBotX bot, String rawLine)
                            throws org.pircbotx.exception.CAPException {
                        if (rawLine == null || done) return done;

                        String[] parts = rawLine.split(" ");
                        if (parts.length < 2) return false;

                        boolean isAuthPlus =
                                (parts[0].equals("AUTHENTICATE") && parts.length >= 2 && parts[1].equals("+")) ||
                                        (parts.length >= 3 && parts[1].equals("AUTHENTICATE") && parts[2].equals("+"));
                        if (isAuthPlus) {
                            String creds = "\0" + saslLogin + "\0" + saslPass;
                            String encoded = Base64.getEncoder().encodeToString(
                                    creds.getBytes(StandardCharsets.UTF_8));
                            bot.sendRaw().rawLine("AUTHENTICATE " + encoded);
                            return false;
                        }

                        String numeric = parts[1];
                        if (numeric.equals("903")) {
                            bot.sendRaw().rawLine("CAP END");
                            Log.i(TAG, "SASL PLAIN authenticated on " + name);
                            done = true;
                            return true;
                        }
                        if (numeric.equals("904") || numeric.equals("905")) {
                            Log.w(TAG, "SASL auth failed on " + name + ": " + rawLine);
                            done = true;
                            return true;
                        }
                        return false;
                    }
                });
            }

            PircBotX bot = new PircBotX(builder.buildConfiguration());
            st.bot = bot;

            st.botFuture = botExecutor.submit(() -> {
                try {
                    bot.startBot();
                } catch (IOException | IrcException e) {
                    Log.e(TAG, "Bot error [" + name + "]", e);
                    st.connected.set(false);
                    refreshNotification();
                    Listener l = listener;
                    if (l != null) mainHandler.post(() -> l.onDisconnected(name));
                    if (st.shouldRun.get()) {
                        long delay = nextDelay(st.retryCount.getAndIncrement());
                        scheduleReconnect(st, delay);
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "launchBot() error [" + name + "]", e);
            st.connected.set(false);
            refreshNotification();
            Listener l = listener;
            if (l != null) mainHandler.post(() -> l.onDisconnected(name));
            if (st.shouldRun.get()) scheduleReconnect(st, nextDelay(st.retryCount.getAndIncrement()));
        }
    }

    private void scheduleReconnect(ServerState st, long delayMs) {
        if (!st.shouldRun.get()) return;
        acquireWakeLock();

        st.retryFuture = workerExecutor.submit(() -> {
            try {
                Thread.sleep(delayMs);
                if (!st.shouldRun.get()) { releaseWakeLock(); return; }

                Log.i(TAG, "Reconnecting " + st.server.getName() + "…");

                st.suppressNextDisconnectReconnect.set(true);
                PircBotX old = st.bot;
                st.bot = null;
                if (old != null) {
                    try { old.close(); } catch (Exception ignored) {}
                }

                launchBot(st);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                releaseWakeLock();
            }
        });
    }

    private static long nextDelay(int attempt) {
        long delay = RECONNECT_DELAY_BASE_MS * (1L << Math.min(attempt, 6));
        return Math.min(delay, RECONNECT_DELAY_MAX_MS);
    }

    private void refreshNotification() {
        List<Server> connected = getConnectedServers();
        String text;
        if (connected.isEmpty()) {
            text = getString(R.string.notif_no_active_connections);
        } else {
            StringBuilder sb = new StringBuilder();
            for (Server s : connected) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(s.getName());
            }
            text = getString(R.string.notif_connected_to, sb.toString());
        }
        updateNotification(text);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel_connection),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.notif_channel_connection_desc));

            NotificationChannel dm = new NotificationChannel(
                    DM_CHANNEL_ID, getString(R.string.notif_channel_dms),
                    NotificationManager.IMPORTANCE_HIGH);
            dm.setDescription(getString(R.string.notif_channel_dms_desc));
            dm.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(ch);
            nm.createNotificationChannel(dm);
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    private void maybeNotifyDm(String serverName, String fromNick,
                               String preview, String imageUrl, boolean encrypted) {
        if (appVisible && listener != null) return;

        ServerState st = states.get(serverName);
        if (st != null && System.currentTimeMillis() < st.historyBackfillUntilMs) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }

        final String key = serverName + "/" + fromNick;
        int count = dmUnreadCounts.merge(key, 1, Integer::sum);

        long now = System.currentTimeMillis();
        Long last = dmLastAlertMs.get(key);
        boolean silentUpdate = last != null && (now - last) < DM_ALERT_THROTTLE_MS;
        if (!silentUpdate) dmLastAlertMs.put(key, now);

        String body;
        if (encrypted) {
            body = getResources().getQuantityString(
                    R.plurals.notif_dm_encrypted, count, count);
        } else {
            String base;
            if (preview == null || preview.isEmpty()) {
                base = imageUrl != null
                        ? getString(R.string.notif_dm_image)
                        : getString(R.string.notif_dm_new_message);
            } else {
                base = preview;
            }
            body = count > 1
                    ? getString(R.string.notif_dm_count_prefix, count, base)
                    : base;
        }

        int id = dmNotifIds.computeIfAbsent(key, k -> nextDmNotifId.getAndIncrement());

        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_DM_SERVER, serverName)
                .putExtra(EXTRA_DM_NICK, fromNick);
        PendingIntent pi = PendingIntent.getActivity(
                this, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification publicVersion = new NotificationCompat.Builder(this, DM_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_dm_public))
                .build();

        Notification n = new NotificationCompat.Builder(this, DM_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(getString(R.string.notif_dm_title, fromNick, serverName))
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(silentUpdate)
                .setNumber(count)
                .build();

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(id, n);
    }
}