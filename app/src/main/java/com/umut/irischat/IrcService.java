package com.umut.irischat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
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
    private static final String CHANNEL_ID = "irischat_conn";
    private static final int    NOTIF_ID   = 1;

    private static final long RECONNECT_DELAY_BASE_MS = 5_000L;
    private static final long RECONNECT_DELAY_MAX_MS  = 300_000L;
    private static final int  MAX_QUEUED_MSGS_PER_TAB = 200;

    private static final int MAX_INBOUND_MSG_BYTES = 8192;

    private static final int MAX_OUTBOUND_MSG_BYTES = 400;

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
    }

    private volatile Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
        final AtomicBoolean connected   = new AtomicBoolean(false);
        final AtomicBoolean shouldRun   = new AtomicBoolean(true);
        final AtomicInteger retryCount  = new AtomicInteger(0);
        volatile Future<?>  botFuture;
        volatile Future<?>  retryFuture;

        ServerState(Server s) { this.server = s; }
    }

    private final Map<String, ServerState> states =
            Collections.synchronizedMap(new LinkedHashMap<>());

    private ExecutorService botExecutor;
    private ExecutorService workerExecutor;
    private PowerManager.WakeLock wakeLock;

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
        startForeground(NOTIF_ID, buildNotification("Idle"));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterNetworkCallback();
        disconnectAll();
        botExecutor.shutdownNow();
        workerExecutor.shutdownNow();
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
        refreshNotification();
    }

    public void disconnectAll() {
        synchronized (states) {
            for (String name : new ArrayList<>(states.keySet())) stopServerState(name);
            states.clear();
        }
        refreshNotification();
    }

    public boolean sendMessage(String serverName, String channel, String message) {
        ServerState st = states.get(serverName);
        if (st == null || !st.connected.get() || st.bot == null) return false;
        final String sanitized = stripIrcInjection(message);
        final String safe = truncateToByteLimit(sanitized, MAX_OUTBOUND_MSG_BYTES);
        workerExecutor.execute(() -> {
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
        workerExecutor.execute(() -> {
            try { st.bot.send().notice(targetNick, safe); }
            catch (Exception e) { Log.e(TAG, "sendNotice error [" + serverName + "]", e); }
        });
        return true;
    }

    private static String stripIrcInjection(String s) {
        if (s == null) return null;
        return s.replace("\r", "").replace("\n", "");
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
            workerExecutor.execute(() -> {
                try { bot.close(); } catch (Exception ignored) {}
            });
            st.bot = null;
        }
        st.connected.set(false);
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
                    .addListener(new ListenerAdapter() {

                        @Override
                        public void onConnect(ConnectEvent event) {
                            st.connected.set(true);
                            st.retryCount.set(0);
                            releaseWakeLock();
                            refreshNotification();
                            Listener l = listener;
                            if (l != null) mainHandler.post(() -> l.onConnected(name));
                            Log.i(TAG, "Connected to " + name);
                        }

                        @Override
                        public void onDisconnect(DisconnectEvent event) {
                            st.connected.set(false);
                            refreshNotification();
                            Listener l = listener;
                            if (l != null) mainHandler.post(() -> l.onDisconnected(name));
                            Log.i(TAG, "Disconnected from " + name);

                            if (st.shouldRun.get()) {
                                long delay = nextDelay(st.retryCount.getAndIncrement());
                                Log.i(TAG, "Reconnect " + name + " in " + delay + " ms");
                                scheduleReconnect(st, delay);
                            }
                        }

                        @Override
                        public void onMessage(MessageEvent event) {
                            String channel = event.getChannel().getName();
                            String nick    = event.getUser().getNick();
                            String text    = event.getMessage();

                            if (text != null && text.getBytes(StandardCharsets.UTF_8).length
                                    > MAX_INBOUND_MSG_BYTES) {
                                Log.w(TAG, "onMessage: oversized message from " + nick + " dropped");
                                return;
                            }

                            String imgUrl  = MainActivity.extractImageUrl(text);
                            String display = imgUrl != null
                                    ? text.replace(imgUrl, "").trim() : text;
                            enqueueOrDeliver(name, channel, nick, display, imgUrl);
                        }

                        @Override
                        public void onPrivateMessage(PrivateMessageEvent event) {
                            String nick   = event.getUser().getNick();
                            String text   = event.getMessage();

                            if (text != null && text.getBytes(StandardCharsets.UTF_8).length
                                    > MAX_INBOUND_MSG_BYTES) {
                                Log.w(TAG, "onPrivateMessage: oversized message from " + nick + " dropped");
                                return;
                            }

                            if (SignalStore.isSignalMessage(text)) {
                                enqueueOrDeliver(name, nick, nick, text, null);
                                return;
                            }
                            String imgUrl  = MainActivity.extractImageUrl(text);
                            String display = imgUrl != null
                                    ? text.replace(imgUrl, "").trim() : text;
                            enqueueOrDeliver(name, nick, nick, display, imgUrl);
                        }

                        @Override
                        public void onNotice(NoticeEvent event) {
                            if (event.getUser() == null) return;
                            String nick = event.getUser().getNick();
                            if (nick == null || nick.isEmpty()) return;
                            String notice = event.getNotice();

                            if (notice != null && notice.getBytes(StandardCharsets.UTF_8).length
                                    > MAX_INBOUND_MSG_BYTES) {
                                Log.w(TAG, "onNotice: oversized notice from " + nick + " dropped");
                                return;
                            }

                            enqueueOrDeliverNotice(name, nick, notice);
                        }

                        @Override
                        public void onJoin(JoinEvent event) {
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
                                for (String ch : st.server.getChannels())
                                    notifyMembersChanged(name, ch, st.bot, myNick);
                            }, 300);
                        }
                    });

            if (st.server.isTls()) {
                SSLSocketFactory sslSF = HttpsURLConnection.getDefaultSSLSocketFactory();
                builder.setSocketFactory(sslSF);
            }

            for (String ch : st.server.getChannels()) builder.addAutoJoinChannel(ch);
            if (st.server.hasPassword())  builder.setServerPassword(st.server.getPassword());
            if (st.server.hasSasl()) {
                builder.addCapHandler(new org.pircbotx.cap.SASLCapHandler(
                        st.server.getSaslLogin(), st.server.getSaslPassword(), false));
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

                PircBotX old = st.bot;
                st.bot = null;
                if (old != null) try { old.close(); } catch (Exception ignored) {}
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
            text = "No active connections";
        } else {
            StringBuilder sb = new StringBuilder();
            for (Server s : connected) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(s.getName());
            }
            text = "Connected: " + sb;
        }
        updateNotification(text);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "IRC Connection",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps IRC connections alive in the background");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
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
                .setContentTitle("IrisChat")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}
