package com.umut.irischat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class MessageDatabase {

    private static final String TAG = "MessageDatabase";

    private static final String TRANSFORMATION  = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LEN      = 12;
    private static final int    GCM_TAG_BITS    = 128;

    private static final String DB_NAME    = "irischat_messages.db";
    private static final int    DB_VERSION = 2;

    static final String TABLE          = "messages";
    static final String COL_ID         = "id";
    static final String COL_TAB_KEY    = "tab_key";
    static final String COL_TYPE       = "msg_type";
    static final String COL_NICK       = "nick";
    static final String COL_BODY       = "body";
    static final String COL_REPLY_NICK = "reply_nick";
    static final String COL_REPLY_TEXT = "reply_text";
    static final String COL_IMAGE_URL  = "image_url";
    static final String COL_ENCRYPTED  = "encrypted";
    static final String COL_RAW_WIRE   = "raw_wire";
    static final String COL_TIMESTAMP  = "timestamp";

    public static final int DEFAULT_PAGE_SIZE = 50;

    private final Helper    helper;

    private volatile byte[] dbKeyBytes;

    private static volatile MessageDatabase instance;

    public static MessageDatabase get(Context ctx) {
        if (instance == null) {
            synchronized (MessageDatabase.class) {
                if (instance == null) instance = new MessageDatabase(ctx.getApplicationContext());
            }
        }
        return instance;
    }

    private MessageDatabase(Context ctx) {
        helper = new Helper(ctx);
    }

    public void init(SecretKey masterKey) {
        this.dbKeyBytes = masterKey.getEncoded().clone();
        helper.getWritableDatabase();
    }

    public void lock() {
        byte[] old = dbKeyBytes;
        dbKeyBytes = null;
        if (old != null) Arrays.fill(old, (byte) 0);
    }

    public long insert(String tabKey, ChatMessage msg) {
        byte[] keySnapshot = requireKey();
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues(10);
        cv.put(COL_TAB_KEY,    tabKey);
        cv.put(COL_TYPE,       msg.getType().name());
        cv.put(COL_NICK,       encryptNullable(msg.getNick(),        keySnapshot));
        cv.put(COL_BODY,       encrypt(msg.getText() != null ? msg.getText() : "", keySnapshot));
        cv.put(COL_REPLY_NICK, encryptNullable(msg.getReplyToNick(), keySnapshot));
        cv.put(COL_REPLY_TEXT, encryptNullable(msg.getReplyToText(), keySnapshot));
        cv.put(COL_IMAGE_URL,  encryptNullable(msg.getImageUrl(),    keySnapshot));
        cv.put(COL_ENCRYPTED,  msg.isEncrypted() ? 1 : 0);
        cv.put(COL_RAW_WIRE,   encryptNullable(msg.getRawWire(),     keySnapshot));
        cv.put(COL_TIMESTAMP,  msg.getTimestamp());
        try {
            return db.insertOrThrow(TABLE, null, cv);
        } catch (Exception e) {
            Log.e(TAG, "insert failed", e);
            return -1;
        }
    }

    public void delete(long id) {
        helper.getWritableDatabase()
                .delete(TABLE, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    public void deleteTab(String tabKey) {
        helper.getWritableDatabase()
                .delete(TABLE, COL_TAB_KEY + "=?", new String[]{tabKey});
    }

    public List<StoredMessage> loadRecent(String tabKey, int limit) {
        byte[] keySnapshot = requireKey();
        String sql =
                "SELECT * FROM (" +
                        "  SELECT " + COL_ID + "," + COL_TYPE + "," + COL_NICK + "," + COL_BODY + "," +
                        COL_REPLY_NICK + "," + COL_REPLY_TEXT + "," + COL_IMAGE_URL + "," +
                        COL_ENCRYPTED + "," + COL_RAW_WIRE + "," + COL_TIMESTAMP +
                        "  FROM " + TABLE +
                        "  WHERE " + COL_TAB_KEY + "=?" +
                        "  ORDER BY " + COL_ID + " DESC" +
                        "  LIMIT ?" +
                        ") ORDER BY " + COL_ID + " ASC";
        return query(sql, new String[]{tabKey, String.valueOf(limit)}, keySnapshot);
    }

    public List<StoredMessage> loadPage(String tabKey, long beforeId, int limit) {
        byte[] keySnapshot = requireKey();
        String sql =
                "SELECT * FROM (" +
                        "  SELECT " + COL_ID + "," + COL_TYPE + "," + COL_NICK + "," + COL_BODY + "," +
                        COL_REPLY_NICK + "," + COL_REPLY_TEXT + "," + COL_IMAGE_URL + "," +
                        COL_ENCRYPTED + "," + COL_RAW_WIRE + "," + COL_TIMESTAMP +
                        "  FROM " + TABLE +
                        "  WHERE " + COL_TAB_KEY + "=? AND " + COL_ID + "<?" +
                        "  ORDER BY " + COL_ID + " DESC" +
                        "  LIMIT ?" +
                        ") ORDER BY " + COL_ID + " ASC";
        return query(sql, new String[]{tabKey, String.valueOf(beforeId), String.valueOf(limit)},
                keySnapshot);
    }

    public List<String> getKnownTargetsForServer(String serverName) {
        List<String> targets = new ArrayList<>();
        if (serverName == null || serverName.isEmpty()) return targets;

        String prefix = serverName + "/";
        String escapedPrefix = prefix
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT DISTINCT " + COL_TAB_KEY + " FROM " + TABLE +
                        " WHERE " + COL_TAB_KEY + " LIKE ? ESCAPE '\\'",
                new String[]{escapedPrefix + "%"});
        try {
            int iTabKey = c.getColumnIndexOrThrow(COL_TAB_KEY);
            while (c.moveToNext()) {
                String tabKey = c.getString(iTabKey);
                if (tabKey == null || !tabKey.startsWith(prefix)) continue;
                String target = tabKey.substring(prefix.length());
                if (!target.isEmpty()) targets.add(target);
            }
        } finally {
            c.close();
        }
        return targets;
    }

    public boolean existsAtTimestamp(String tabKey, String nick, String text, long timestamp) {
        byte[] keySnapshot = requireKey();
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT " + COL_NICK + "," + COL_BODY + " FROM " + TABLE +
                        " WHERE " + COL_TAB_KEY + "=? AND " + COL_TIMESTAMP + "=?",
                new String[]{tabKey, String.valueOf(timestamp)});
        try {
            int iNick = c.getColumnIndexOrThrow(COL_NICK);
            int iBody = c.getColumnIndexOrThrow(COL_BODY);
            while (c.moveToNext()) {
                try {
                    String storedNick = decryptNullable(c.getBlob(iNick), keySnapshot);
                    String storedBody = decryptBytes(c.getBlob(iBody), keySnapshot);
                    if (java.util.Objects.equals(storedNick, nick)
                            && java.util.Objects.equals(storedBody, text)) {
                        return true;
                    }
                } catch (Exception ignored) {
                }
            }
        } finally {
            c.close();
        }
        return false;
    }

    public long getMaxTimestampForTab(String tabKey) {
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT MAX(" + COL_TIMESTAMP + ") FROM " + TABLE + " WHERE " + COL_TAB_KEY + "=?",
                new String[]{tabKey});
        try {
            return (c.moveToFirst() && !c.isNull(0)) ? c.getLong(0) : -1L;
        } finally {
            c.close();
        }
    }

    private List<StoredMessage> query(String sql, String[] args, byte[] keySnapshot) {
        List<StoredMessage> list = new ArrayList<>();
        Cursor c = helper.getReadableDatabase().rawQuery(sql, args);
        try {
            int iId        = c.getColumnIndexOrThrow(COL_ID);
            int iType      = c.getColumnIndexOrThrow(COL_TYPE);
            int iNick      = c.getColumnIndexOrThrow(COL_NICK);
            int iBody      = c.getColumnIndexOrThrow(COL_BODY);
            int iRNick     = c.getColumnIndexOrThrow(COL_REPLY_NICK);
            int iRText     = c.getColumnIndexOrThrow(COL_REPLY_TEXT);
            int iImgUrl    = c.getColumnIndexOrThrow(COL_IMAGE_URL);
            int iEncrypted = c.getColumnIndexOrThrow(COL_ENCRYPTED);
            int iRawWire   = c.getColumnIndexOrThrow(COL_RAW_WIRE);
            int iTimestamp = c.getColumnIndexOrThrow(COL_TIMESTAMP);

            while (c.moveToNext()) {
                try {
                    long   id        = c.getLong(iId);
                    String typeStr   = c.getString(iType);
                    String nick      = decryptNullable(c.getBlob(iNick),    keySnapshot);
                    String body      = decryptBytes(c.getBlob(iBody),       keySnapshot);
                    String replyNick = decryptNullable(c.getBlob(iRNick),   keySnapshot);
                    String replyText = decryptNullable(c.getBlob(iRText),   keySnapshot);
                    String imageUrl  = decryptNullable(c.getBlob(iImgUrl),  keySnapshot);
                    boolean encrypted = c.getInt(iEncrypted) != 0;
                    String rawWire   = decryptNullable(c.getBlob(iRawWire), keySnapshot);
                    long   timestamp = c.isNull(iTimestamp) ? 0L : c.getLong(iTimestamp);

                    ChatMessage.Type type;
                    try { type = ChatMessage.Type.valueOf(typeStr); }
                    catch (IllegalArgumentException e) { type = ChatMessage.Type.RECEIVED; }

                    ChatMessage msg = new ChatMessage(
                            nick, body, type, replyNick, replyText, imageUrl, encrypted, rawWire,
                            timestamp);
                    list.add(new StoredMessage(id, msg));
                } catch (Exception e) {
                    Log.w(TAG, "Skipping corrupt row", e);
                }
            }
        } finally {
            c.close();
        }
        return list;
    }

    private byte[] encrypt(String plaintext, byte[] keyBytes) {
        return encryptBytes(plaintext.getBytes(StandardCharsets.UTF_8), keyBytes);
    }

    private byte[] encryptNullable(String value, byte[] keyBytes) {
        return value == null ? null : encrypt(value, keyBytes);
    }

    private byte[] encryptBytes(byte[] plaintext, byte[] keyBytes) {
        SecretKey key = new SecretKeySpec(keyBytes, "AES");
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext);
            byte[] out = new byte[GCM_IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0,          GCM_IV_LEN);
            System.arraycopy(ct, 0, out, GCM_IV_LEN, ct.length);
            return out;
        } catch (Exception e) {
            throw new RuntimeException("DB encrypt failed", e);
        }
    }

    private String decryptBytes(byte[] blob, byte[] keyBytes) {
        if (blob == null || blob.length < GCM_IV_LEN + 1)
            throw new IllegalArgumentException("Blob too short");
        SecretKey key = new SecretKeySpec(keyBytes, "AES");
        try {
            byte[] iv = Arrays.copyOfRange(blob, 0, GCM_IV_LEN);
            byte[] ct = Arrays.copyOfRange(blob, GCM_IV_LEN, blob.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("DB decrypt failed", e);
        }
    }

    private String decryptNullable(byte[] blob, byte[] keyBytes) {
        return blob == null ? null : decryptBytes(blob, keyBytes);
    }

    private byte[] requireKey() {
        byte[] snapshot = dbKeyBytes;
        if (snapshot == null) throw new IllegalStateException("MessageDatabase is locked.");
        return snapshot;
    }

    public static final class StoredMessage {
        public final long        id;
        public final ChatMessage message;

        public StoredMessage(long id, ChatMessage message) {
            this.id      = id;
            this.message = message;
        }
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context ctx) { super(ctx, DB_NAME, null, DB_VERSION); }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(
                    "CREATE TABLE " + TABLE + " (" +
                            COL_ID         + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            COL_TAB_KEY    + " TEXT    NOT NULL, " +
                            COL_TYPE       + " TEXT    NOT NULL, " +
                            COL_NICK       + " BLOB, " +
                            COL_BODY       + " BLOB    NOT NULL, " +
                            COL_REPLY_NICK + " BLOB, " +
                            COL_REPLY_TEXT + " BLOB, " +
                            COL_IMAGE_URL  + " BLOB, " +
                            COL_ENCRYPTED  + " INTEGER NOT NULL DEFAULT 0, " +
                            COL_RAW_WIRE   + " BLOB, " +
                            COL_TIMESTAMP  + " INTEGER NOT NULL DEFAULT 0" +
                            ")"
            );
            db.execSQL(
                    "CREATE INDEX idx_messages_tab ON " + TABLE +
                            "(" + COL_TAB_KEY + "," + COL_ID + ")"
            );
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE " + TABLE +
                        " ADD COLUMN " + COL_TIMESTAMP + " INTEGER NOT NULL DEFAULT 0");
            }
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            android.database.Cursor c = db.rawQuery("PRAGMA journal_mode=WAL", null);
            if (c != null) c.close();
        }
    }
}