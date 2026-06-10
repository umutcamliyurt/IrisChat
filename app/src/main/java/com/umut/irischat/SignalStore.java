package com.umut.irischat;

import android.util.Base64;
import android.util.Log;

import org.signal.libsignal.protocol.DuplicateMessageException;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.SessionBuilder;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.ecc.ECPrivateKey;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.kem.KEMPublicKey;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.message.SignalMessage;
import org.signal.libsignal.protocol.state.IdentityKeyStore;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.KyberPreKeyStore;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyStore;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SessionStore;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SignalStore {

    private static final String TAG = "SignalStore";

    public static final String KEY_ANNOUNCE_PREFIX = "IRISSIG-KEY:";
    public static final String MSG_PREFIX          = "IRISSIG-MSG:";

    private static final int CHUNK_BYTES  = 200;

    private static final int MAX_CHUNKS_PER_MSG  = 32;
    private static final int MAX_TOKENS_PER_NICK = 8;

    private static final int MAX_BLOB_BYTES = 4096;

    private static final int MAX_NICK_LENGTH = 64;

    private static final java.util.regex.Pattern TOKEN_PATTERN =
            java.util.regex.Pattern.compile("^[0-9a-f]{8}$");

    private static final String KEY_IDENTITY       = "signal_identity";
    private static final String KEY_REG_ID         = "signal_reg_id";
    private static final String KEY_SPK            = "signal_spk";
    private static final String KEY_KPK            = "signal_kpk";
    private static final String KEY_CPK_PFX        = "signal_cpk/";
    private static final String KEY_BUNDLE_PFX     = "signal_bundle/";
    private static final String KEY_IDKEY_PFX      = "signal_idkey/";
    private static final String KEY_PENDING_PFX    = "signal_pending/";
    static final String KEY_SESSION_PFX            = "signal_sess/";

    private static final int SPK_ID    = 1;
    private static final int KPK_ID    = 1;
    private static final int LOCAL_DEV = 1;

    private final CryptoStore crypto;

    private IdentityKeyPair    identityKeyPair;
    private int                registrationId;
    private SignedPreKeyRecord  signedPreKey;
    private KyberPreKeyRecord   kyberPreKey;

    private final Map<String, PreKeyRecord> contactPreKeys = new ConcurrentHashMap<>();

    private final Map<String, IrcSignalStore> stores = new ConcurrentHashMap<>();

    private final Map<String, Object> nickLocks = new ConcurrentHashMap<>();

    private Object lockFor(String nick) {
        return nickLocks.computeIfAbsent(lc(nick), k -> new Object());
    }

    public SignalStore(CryptoStore crypto) {
        this.crypto = crypto;
        tryLoad();
    }

    public boolean hasIdentity() { return identityKeyPair != null; }

    public void generateIdentity() throws Exception {
        ECKeyPair    idPair  = ECKeyPair.generate();
        ECPrivateKey idPriv  = idPair.getPrivateKey();
        ECPublicKey  idPub   = idPair.getPublicKey();
        identityKeyPair = new IdentityKeyPair(new IdentityKey(idPub), idPriv);

        registrationId = 1 + (new java.security.SecureRandom().nextInt(16382));

        ECKeyPair spkPair = ECKeyPair.generate();
        byte[] spkSig = idPriv.calculateSignature(spkPair.getPublicKey().serialize());
        signedPreKey = new SignedPreKeyRecord(SPK_ID, System.currentTimeMillis(),
                spkPair, spkSig);

        KEMKeyPair kemPair  = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
        byte[]     kyberSig = idPriv.calculateSignature(kemPair.getPublicKey().serialize());
        kyberPreKey = new KyberPreKeyRecord(KPK_ID, System.currentTimeMillis(),
                kemPair, kyberSig);

        crypto.putString(KEY_IDENTITY, b64(identityKeyPair.serialize()));
        crypto.putString(KEY_REG_ID,   String.valueOf(registrationId));
        crypto.putString(KEY_SPK,      b64(signedPreKey.serialize()));
        crypto.putString(KEY_KPK,      b64(kyberPreKey.serialize()));

        Log.i(TAG, "Identity generated  regId=" + registrationId);
    }

    public void clearIdentity() {
        crypto.remove(KEY_IDENTITY); crypto.remove(KEY_REG_ID);
        crypto.remove(KEY_SPK);      crypto.remove(KEY_KPK);
        for (String nick : new java.util.ArrayList<>(contactPreKeys.keySet())) {
            crypto.remove(KEY_CPK_PFX + nick);
        }
        identityKeyPair = null; signedPreKey = null;
        kyberPreKey     = null;
        contactPreKeys.clear();
        stores.clear();
        chunkStore.clear();
        msgChunkStore.clear();
        msgRawStore.clear();
        Log.i(TAG, "Identity cleared");
    }

    public List<String> buildKeyAnnouncement(String nick) {
        if (!hasIdentity() || signedPreKey == null || kyberPreKey == null) {
            Log.w(TAG, "buildKeyAnnouncement: identity not fully initialised");
            return Collections.emptyList();
        }
        try {
            PreKeyRecord contactPk = getOrCreatePreKeyForNick(nick);

            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            writeInt(buf, registrationId);
            writeBlob(buf, identityKeyPair.getPublicKey().serialize());
            writeInt(buf, signedPreKey.getId());
            writeBlob(buf, signedPreKey.getKeyPair().getPublicKey().serialize());
            writeBlob(buf, signedPreKey.getSignature());
            writeInt(buf, contactPk.getId());
            writeBlob(buf, contactPk.getKeyPair().getPublicKey().serialize());
            writeInt(buf, kyberPreKey.getId());
            writeBlob(buf, kyberPreKey.getKeyPair().getPublicKey().serialize());
            writeBlob(buf, kyberPreKey.getSignature());

            byte[] raw    = buf.toByteArray();
            String token  = randomToken();
            int    total  = (raw.length + CHUNK_BYTES - 1) / CHUNK_BYTES;

            List<String> lines = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                int from  = i * CHUNK_BYTES;
                int to    = Math.min(from + CHUNK_BYTES, raw.length);
                String chunk = b64(Arrays.copyOfRange(raw, from, to));
                lines.add(KEY_ANNOUNCE_PREFIX + token + ":" + total + ":" + i + ":" + chunk);
            }
            Log.i(TAG, "buildKeyAnnouncement[" + nick + "]: " + raw.length + " bytes → " + total + " chunks");
            return lines;
        } catch (Exception e) {
            Log.w(TAG, "buildKeyAnnouncement failed for " + nick, e);
            return Collections.emptyList();
        }
    }

    private PreKeyRecord getOrCreatePreKeyForNick(String nick) throws Exception {
        String key = lc(nick);
        PreKeyRecord existing = contactPreKeys.get(key);
        if (existing != null) return existing;

        String stored = crypto.getString(KEY_CPK_PFX + key, null);
        if (stored != null) {
            try {
                PreKeyRecord loaded = new PreKeyRecord(unb64(stored));
                contactPreKeys.put(key, loaded);
                return loaded;
            } catch (Exception e) {
                Log.w(TAG, "Could not load stored pre-key for " + nick + ", regenerating", e);
            }
        }

        int pkId = 1 + (new java.security.SecureRandom().nextInt(0xFFFFFE));
        ECKeyPair pkPair = ECKeyPair.generate();
        PreKeyRecord record = new PreKeyRecord(pkId, pkPair);
        contactPreKeys.put(key, record);
        crypto.putString(KEY_CPK_PFX + key, b64(record.serialize()));
        Log.i(TAG, "Generated EC pre-key id=" + pkId + " for " + nick);
        return record;
    }

    public static boolean isKeyAnnouncement(String t) {
        return t != null && t.startsWith(KEY_ANNOUNCE_PREFIX);
    }

    public static boolean isSignalMessage(String t) {
        return t != null && t.startsWith(MSG_PREFIX);
    }

    private final Map<String, Map<String, Map<Integer, byte[]>>> chunkStore =
            new ConcurrentHashMap<>();

    private final Map<String, Map<String, Map<Integer, byte[]>>> msgChunkStore =
            new ConcurrentHashMap<>();

    private final Map<String, Map<String, List<String>>> msgRawStore =
            new ConcurrentHashMap<>();

    public byte[] receiveChunk(String nick, String noticeText) {
        if (!isKeyAnnouncement(noticeText)) return null;
        if (nick == null || nick.length() > MAX_NICK_LENGTH) {
            Log.w(TAG, "receiveChunk: nick too long or null, dropping");
            return new byte[0];
        }

        String body  = noticeText.substring(KEY_ANNOUNCE_PREFIX.length()).trim();
        String[] pts = body.split(":", 4);
        if (pts.length != 4) {
            Log.w(TAG, "receiveChunk: malformed notice from " + nick); return new byte[0];
        }
        try {
            String token = pts[0];
            if (!TOKEN_PATTERN.matcher(token).matches()) {
                Log.w(TAG, "receiveChunk: invalid token from " + nick); return new byte[0];
            }
            int    total = Integer.parseInt(pts[1]);
            int    index = Integer.parseInt(pts[2]);
            byte[] data  = unb64(pts[3]);

            if (total < 1 || total > MAX_CHUNKS_PER_MSG || index < 0 || index >= total) {
                Log.w(TAG, "receiveChunk: bad total/index from " + nick); return new byte[0];
            }
            if (data.length > CHUNK_BYTES * 2) {
                Log.w(TAG, "receiveChunk: oversized chunk (" + data.length + " bytes) from " + nick);
                return new byte[0];
            }

            String nick_lc = lc(nick);
            Map<String, Map<Integer, byte[]>> nickMap =
                    chunkStore.computeIfAbsent(nick_lc, k -> new ConcurrentHashMap<>());

            if (!nickMap.containsKey(token) && nickMap.size() >= MAX_TOKENS_PER_NICK) {
                String oldest = nickMap.keySet().iterator().next();
                nickMap.remove(oldest);
                Log.w(TAG, "receiveChunk: evicted oldest token for " + nick);
            }

            nickMap.computeIfAbsent(token, k -> new ConcurrentHashMap<>()).put(index, data);

            Map<Integer, byte[]> slots = nickMap.get(token);
            if (slots.size() < total) return null;

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < total; i++) {
                byte[] chunk = slots.get(i);
                if (chunk == null) {
                    Log.w(TAG, "receiveChunk: missing slot " + i + " from " + nick);
                    return new byte[0];
                }
                out.write(chunk);
            }
            nickMap.remove(token);
            if (nickMap.isEmpty()) chunkStore.remove(nick_lc);

            byte[] bundle = out.toByteArray();
            Log.i(TAG, "receiveChunk: reassembled " + bundle.length + " bytes from " + nick);
            return bundle;
        } catch (Exception e) {
            Log.w(TAG, "receiveChunk error from " + nick, e); return new byte[0];
        }
    }

    public boolean hasContactBundle(String nick) {
        return crypto.contains(KEY_BUNDLE_PFX + lc(nick));
    }

    public String peekBundleFingerprint(byte[] bundle) {
        try {
            java.io.DataInputStream in = din(bundle);
            in.readInt();
            byte[] idKeyBytes = readBlob(in);
            return fingerprint(new IdentityKey(idKeyBytes, 0).getPublicKey().serialize());
        } catch (Exception e) { return null; }
    }

    public String storeBundleForNick(String nick, byte[] bundle) {
        try {
            java.io.DataInputStream in = din(bundle);
            in.readInt();
            byte[] idKeyBytes = readBlob(in);
            crypto.putString(KEY_BUNDLE_PFX + lc(nick), b64(bundle));
            crypto.putString(KEY_IDKEY_PFX  + lc(nick), b64(idKeyBytes));
            stores.remove(lc(nick));
            Log.i(TAG, "Bundle stored for " + nick);
            return fingerprint(new IdentityKey(idKeyBytes, 0).getPublicKey().serialize());
        } catch (Exception e) {
            Log.w(TAG, "storeBundleForNick failed for " + nick, e);
            return null;
        }
    }

    public enum BundleStatus { NEW, UNCHANGED, CHANGED, INVALID }

    public static final class BundleClassification {
        public final BundleStatus status;
        public final String       oldFingerprint;
        public final String       newFingerprint;
        BundleClassification(BundleStatus s, String oldFp, String newFp) {
            this.status = s; this.oldFingerprint = oldFp; this.newFingerprint = newFp;
        }
    }

    public BundleClassification classifyIncomingBundle(String nick, byte[] bundle) {
        String incomingIdFp = peekBundleFingerprint(bundle);
        if (incomingIdFp == null) {
            return new BundleClassification(BundleStatus.INVALID, null, null);
        }
        synchronized (lockFor(nick)) {
            String oldIdFp = contactFingerprint(nick);
            if (oldIdFp == null) {
                String fp = storeBundleForNick(nick, bundle);
                if (fp == null) return new BundleClassification(BundleStatus.INVALID, null, null);
                crypto.remove(KEY_PENDING_PFX + lc(nick));
                return new BundleClassification(BundleStatus.NEW, null, combinedFingerprint(nick));
            }
            if (incomingIdFp.equals(oldIdFp)) {
                crypto.remove(KEY_PENDING_PFX + lc(nick));
                String fp = combinedFingerprint(nick);
                return new BundleClassification(BundleStatus.UNCHANGED, fp, fp);
            }
            crypto.putString(KEY_PENDING_PFX + lc(nick), b64(bundle));
            Log.w(TAG, "Identity key CHANGED for " + nick + " — parked as pending, awaiting user review");
            return new BundleClassification(BundleStatus.CHANGED,
                    combinedFingerprint(nick),
                    combinedBundleFingerprint(bundle));
        }
    }

    public boolean hasPendingIdentity(String nick) {
        return crypto.contains(KEY_PENDING_PFX + lc(nick));
    }

    public String pendingFingerprint(String nick) {
        String raw = crypto.getString(KEY_PENDING_PFX + lc(nick), null);
        if (raw == null) return null;
        try {
            return combinedBundleFingerprint(unb64(raw));
        } catch (Exception e) { return null; }
    }

    public String acceptPendingIdentity(String nick) {
        synchronized (lockFor(nick)) {
            String raw = crypto.getString(KEY_PENDING_PFX + lc(nick), null);
            if (raw == null) return null;
            byte[] bundle;
            try { bundle = unb64(raw); }
            catch (Exception e) { crypto.remove(KEY_PENDING_PFX + lc(nick)); return null; }

            crypto.remove(KEY_SESSION_PFX + lc(nick));
            stores.remove(lc(nick));

            String fp = storeBundleForNick(nick, bundle);
            crypto.remove(KEY_PENDING_PFX + lc(nick));
            if (fp == null) return null;
            Log.i(TAG, "Pending identity for " + nick + " accepted by user");
            return combinedFingerprint(nick);
        }
    }

    public void rejectPendingIdentity(String nick) {
        crypto.remove(KEY_PENDING_PFX + lc(nick));
        Log.i(TAG, "Pending identity for " + nick + " rejected by user");
    }

    public String contactFingerprint(String nick) {
        String raw = crypto.getString(KEY_IDKEY_PFX + lc(nick), null);
        if (raw == null) return null;
        try {
            return fingerprint(new IdentityKey(unb64(raw), 0).getPublicKey().serialize());
        } catch (Exception e) { return null; }
    }

    public String combinedFingerprint(String nick) {
        String raw = crypto.getString(KEY_IDKEY_PFX + lc(nick), null);
        if (raw == null) return null;
        try {
            return combinedFingerprintFor(unb64(raw));
        } catch (Exception e) { return null; }
    }

    public String combinedBundleFingerprint(byte[] bundle) {
        try {
            java.io.DataInputStream in = din(bundle);
            in.readInt();
            byte[] idKeyBytes = readBlob(in);
            return combinedFingerprintFor(idKeyBytes);
        } catch (Exception e) { return null; }
    }

    private String combinedFingerprintFor(byte[] contactIdKeySerialized) {
        if (identityKeyPair == null) return null;
        try {
            byte[] ownBytes     = identityKeyPair.getPublicKey().getPublicKey().serialize();
            byte[] contactBytes = new IdentityKey(contactIdKeySerialized, 0)
                    .getPublicKey().serialize();
            int cmp = 0;
            int minLen = Math.min(ownBytes.length, contactBytes.length);
            for (int i = 0; i < minLen && cmp == 0; i++)
                cmp = (ownBytes[i] & 0xFF) - (contactBytes[i] & 0xFF);
            if (cmp == 0) cmp = ownBytes.length - contactBytes.length;
            byte[] first  = cmp <= 0 ? ownBytes : contactBytes;
            byte[] second = cmp <= 0 ? contactBytes : ownBytes;
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            sha.update(first);
            sha.update(second);
            return fingerprint(sha.digest());
        } catch (Exception e) { return null; }
    }

    public void removeContact(String nick) {
        crypto.remove(KEY_BUNDLE_PFX + lc(nick));
        crypto.remove(KEY_IDKEY_PFX  + lc(nick));
        crypto.remove(KEY_CPK_PFX    + lc(nick));
        crypto.remove(KEY_PENDING_PFX + lc(nick));
        crypto.remove(KEY_SESSION_PFX + lc(nick));
        contactPreKeys.remove(lc(nick));
        stores.remove(lc(nick));
        chunkStore.remove(lc(nick));
        msgChunkStore.remove(lc(nick));
        msgRawStore.remove(lc(nick));
        Log.i(TAG, "Contact removed: " + nick);
    }

    public List<String> encryptForWire(String nick, String plaintext) throws Exception {
        if (!hasIdentity()) throw new IllegalStateException("No Signal identity");
        synchronized (lockFor(nick)) {
            IrcSignalStore store = getOrCreate(nick);
            SignalProtocolAddress addr = addr(nick);

            if (!store.containsSession(addr)) buildSession(nick, store, addr);

            SessionCipher cipher = new SessionCipher(store, store, store, store, store, addr);
            CiphertextMessage msg = cipher.encrypt(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] msgBytes = msg.serialize();
            byte[] raw = new byte[1 + msgBytes.length];
            raw[0] = (byte) msg.getType();
            System.arraycopy(msgBytes, 0, raw, 1, msgBytes.length);

            store.flushSession(addr, nick);

            String token = randomToken();
            int total = (raw.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
            List<String> lines = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                int from  = i * CHUNK_BYTES;
                int to    = Math.min(from + CHUNK_BYTES, raw.length);
                lines.add(MSG_PREFIX + token + ":" + total + ":" + i + ":"
                        + b64(Arrays.copyOfRange(raw, from, to)));
            }
            return lines;
        }
    }

    public static final class DecryptResult {
        public final String plaintext;
        public final String rawWire;
        DecryptResult(String plaintext, String rawWire) {
            this.plaintext = plaintext;
            this.rawWire   = rawWire;
        }
    }

    public DecryptResult receiveMsgChunk(String nick, String wireText) throws Exception {
        if (!isSignalMessage(wireText)) return null;
        if (!hasIdentity()) return null;
        if (nick == null || nick.length() > MAX_NICK_LENGTH) {
            Log.w(TAG, "receiveMsgChunk: nick too long or null, dropping");
            return null;
        }

        String body  = wireText.substring(MSG_PREFIX.length()).trim();
        String[] pts = body.split(":", 4);
        if (pts.length != 4) {
            Log.w(TAG, "receiveMsgChunk: malformed line from " + nick); return null;
        }

        String token = pts[0];
        if (!TOKEN_PATTERN.matcher(token).matches()) {
            Log.w(TAG, "receiveMsgChunk: invalid token from " + nick); return null;
        }
        int    total = Integer.parseInt(pts[1]);
        int    index = Integer.parseInt(pts[2]);
        byte[] data  = unb64(pts[3]);

        if (total < 1 || total > MAX_CHUNKS_PER_MSG || index < 0 || index >= total) {
            Log.w(TAG, "receiveMsgChunk: bad total/index from " + nick); return null;
        }
        if (data.length > CHUNK_BYTES * 2) {
            Log.w(TAG, "receiveMsgChunk: oversized chunk (" + data.length + " bytes) from " + nick);
            return null;
        }

        String nick_lc = lc(nick);
        Map<String, Map<Integer, byte[]>> msgNickMap =
                msgChunkStore.computeIfAbsent(nick_lc, k -> new ConcurrentHashMap<>());

        if (!msgNickMap.containsKey(token) && msgNickMap.size() >= MAX_TOKENS_PER_NICK) {
            String oldest = msgNickMap.keySet().iterator().next();
            msgNickMap.remove(oldest);
            msgRawStore.computeIfPresent(nick_lc, (k, v) -> { v.remove(oldest); return v.isEmpty() ? null : v; });
            Log.w(TAG, "receiveMsgChunk: evicted oldest token for " + nick);
        }

        msgNickMap.computeIfAbsent(token, k -> new ConcurrentHashMap<>()).put(index, data);

        msgRawStore
                .computeIfAbsent(nick_lc, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(token,   k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(wireText);

        Map<Integer, byte[]> slots = msgNickMap.get(token);
        if (slots == null || slots.size() < total) return null;

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < total; i++) {
            byte[] chunk = slots.get(i);
            if (chunk == null) {
                Log.w(TAG, "receiveMsgChunk: missing slot " + i + " from " + nick);
                msgNickMap.remove(token);
                if (msgNickMap.isEmpty()) msgChunkStore.remove(nick_lc);
                msgRawStore.computeIfPresent(nick_lc, (k, v) -> { v.remove(token); return v.isEmpty() ? null : v; });
                return null;
            }
            out.write(chunk);
        }

        List<String> rawLines = msgRawStore.getOrDefault(nick_lc, Collections.emptyMap())
                .getOrDefault(token, Collections.emptyList());
        String rawWire = String.join("\n", rawLines);

        msgNickMap.remove(token);
        if (msgNickMap.isEmpty()) msgChunkStore.remove(nick_lc);
        msgRawStore.computeIfPresent(nick_lc, (k, v) -> { v.remove(token); return v.isEmpty() ? null : v; });

        byte[] typeAndBody = out.toByteArray();
        if (typeAndBody.length < 2) return null;

        int    type = typeAndBody[0] & 0xFF;
        byte[] cipherBytes = new byte[typeAndBody.length - 1];
        System.arraycopy(typeAndBody, 1, cipherBytes, 0, cipherBytes.length);

        IrcSignalStore store  = getOrCreate(nick);
        SignalProtocolAddress addr  = addr(nick);
        byte[]         plain;

        synchronized (lockFor(nick)) {
            SessionCipher cipher = new SessionCipher(store, store, store, store, store, addr);
            try {
                if (type == CiphertextMessage.PREKEY_TYPE) {
                    plain = cipher.decrypt(new PreKeySignalMessage(cipherBytes));
                } else if (type == CiphertextMessage.WHISPER_TYPE) {
                    if (!store.containsSession(addr)) {
                        Log.w(TAG, "receiveMsgChunk: WhisperMessage with no session from " + nick
                                + " — cannot decrypt");
                        return null;
                    }
                    plain = cipher.decrypt(new SignalMessage(cipherBytes));
                } else {
                    Log.w(TAG, "receiveMsgChunk: unknown type " + type + " from " + nick);
                    return null;
                }
            } catch (DuplicateMessageException e) {
                Log.w(TAG, "receiveMsgChunk: duplicate from " + nick);
                return null;
            } catch (InvalidMessageException e) {
                Log.w(TAG, "receiveMsgChunk: InvalidMessage from " + nick
                        + " — dropping, session preserved: " + e.getMessage());
                throw e;
            }

            store.flushSession(addr, nick);
        }
        return new DecryptResult(new String(plain, StandardCharsets.UTF_8), rawWire);
    }

    private void tryLoad() {
        try {
            String idRaw  = crypto.getString(KEY_IDENTITY, null);
            String regRaw = crypto.getString(KEY_REG_ID,   null);
            String spkRaw = crypto.getString(KEY_SPK,      null);
            String kpkRaw = crypto.getString(KEY_KPK,      null);
            if (idRaw == null || regRaw == null || spkRaw == null) return;

            identityKeyPair = new IdentityKeyPair(unb64(idRaw));
            registrationId  = Integer.parseInt(regRaw);
            signedPreKey    = new SignedPreKeyRecord(unb64(spkRaw));
            if (kpkRaw != null) kyberPreKey = new KyberPreKeyRecord(unb64(kpkRaw));
            Log.i(TAG, "Identity loaded  regId=" + registrationId);
        } catch (Exception e) {
            Log.w(TAG, "Could not load identity", e);
            identityKeyPair = null;
        }
    }

    private void buildSession(String nick, IrcSignalStore store,
                              SignalProtocolAddress remoteAddr) throws Exception {
        String raw64 = crypto.getString(KEY_BUNDLE_PFX + lc(nick), null);
        if (raw64 == null) throw new IllegalStateException("No bundle for " + nick);

        java.io.DataInputStream in = din(unb64(raw64));
        int    remoteRegId = in.readInt();
        byte[] idKeyBytes  = readBlob(in);

        int         spkId     = in.readInt();
        byte[]      spkBytes  = readBlob(in);
        byte[]      spkSig    = readBlob(in);

        int         ecPkId    = in.readInt();
        byte[]      ecPkBytes = readBlob(in);

        int          kpkId    = in.readInt();
        byte[]       kpkBytes = readBlob(in);
        byte[]       kpkSig   = readBlob(in);

        ECPublicKey  spkPub  = new ECPublicKey(spkBytes);
        ECPublicKey  ecPkPub = new ECPublicKey(ecPkBytes);
        KEMPublicKey kpkPub  = new KEMPublicKey(kpkBytes);

        IdentityKey remoteIdKey = new IdentityKey(idKeyBytes, 0);

        PreKeyBundle bundle = new PreKeyBundle(
                remoteRegId, remoteAddr.getDeviceId(),
                ecPkId,  ecPkPub,
                spkId,   spkPub,  spkSig,
                remoteIdKey,
                kpkId,   kpkPub,  kpkSig);

        new SessionBuilder(store, store, store, store, remoteAddr).process(bundle);
    }

    private IrcSignalStore getOrCreate(String nick) {
        String key = lc(nick);
        synchronized (lockFor(nick)) {
            IrcSignalStore s = stores.get(key);
            if (s == null) {
                PreKeyRecord localOpk = null;
                String storedOpk = crypto.getString(KEY_CPK_PFX + key, null);
                if (storedOpk != null) {
                    try { localOpk = new PreKeyRecord(unb64(storedOpk)); }
                    catch (Exception e) {
                        Log.w(TAG, "getOrCreate: corrupt stored OPK for " + nick + ", regenerating", e);
                    }
                }
                if (localOpk == null) {
                    try { localOpk = getOrCreatePreKeyForNick(nick); }
                    catch (Exception e) {
                        Log.w(TAG, "getOrCreate: could not obtain pre-key for " + nick, e);
                    }
                }
                List<PreKeyRecord> pks = localOpk != null
                        ? new ArrayList<>(Collections.singletonList(localOpk)) : new ArrayList<>();
                s = new IrcSignalStore(identityKeyPair, registrationId,
                        signedPreKey, kyberPreKey, pks, crypto, nick);
                stores.put(key, s);
            }
            return s;
        }
    }

    private static SignalProtocolAddress addr(String nick) {
        return new SignalProtocolAddress(nick.toLowerCase(), LOCAL_DEV);
    }

    private static String lc(String s) { return s.toLowerCase(); }

    private static String randomToken() {
        byte[] b = new byte[4];
        new java.security.SecureRandom().nextBytes(b);
        return String.format("%08x", ((long)(b[0]&0xFF)<<24)|((b[1]&0xFF)<<16)|((b[2]&0xFF)<<8)|(b[3]&0xFF));
    }

    private static String fingerprint(byte[] keyBytes) {
        int start = Math.max(0, keyBytes.length - 20);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < keyBytes.length; i++) {
            if (sb.length() > 0 && (i - start) % 2 == 0) sb.append(' ');
            sb.append(String.format("%02X", keyBytes[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String b64(byte[] d) {
        return Base64.encodeToString(d, Base64.NO_WRAP | Base64.URL_SAFE);
    }
    private static byte[] unb64(String s) {
        return Base64.decode(s, Base64.NO_WRAP | Base64.URL_SAFE);
    }
    private static void writeInt(java.io.OutputStream o, int v) throws java.io.IOException {
        o.write((v>>>24)&0xFF); o.write((v>>>16)&0xFF);
        o.write((v>>> 8)&0xFF); o.write(v&0xFF);
    }
    private static void writeBlob(java.io.OutputStream o, byte[] d) throws java.io.IOException {
        writeInt(o, d.length); o.write(d);
    }
    private static java.io.DataInputStream din(byte[] b) {
        return new java.io.DataInputStream(new java.io.ByteArrayInputStream(b));
    }

    private static byte[] readBlob(java.io.DataInputStream in) throws java.io.IOException {
        int len = in.readInt();
        if (len < 0 || len > MAX_BLOB_BYTES) {
            throw new IOException("readBlob: length " + len + " exceeds limit " + MAX_BLOB_BYTES);
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return b;
    }

    static final class IrcSignalStore
            implements IdentityKeyStore, PreKeyStore, SignedPreKeyStore,
            KyberPreKeyStore, SessionStore {

        private static final String TAG2       = "IrcSignalStore";
        private static final String CS_SESSION = KEY_SESSION_PFX;
        private static final String CS_IDKEY   = "signal_idkey/";

        private final IdentityKeyPair        idKP;
        private final int                    regId;
        private final SignedPreKeyRecord     spk;
        private final KyberPreKeyRecord      kpk;
        private final List<PreKeyRecord>     preKeys;
        private final CryptoStore            crypto;
        private final String                 nick;

        private final Map<SignalProtocolAddress, SessionRecord> sessCache = new ConcurrentHashMap<>();
        private final Map<SignalProtocolAddress, IdentityKey>   idCache   = new ConcurrentHashMap<>();

        IrcSignalStore(IdentityKeyPair idKP, int regId,
                       SignedPreKeyRecord spk, KyberPreKeyRecord kpk,
                       List<PreKeyRecord> pks, CryptoStore crypto, String nick) {
            this.idKP    = idKP;
            this.regId   = regId;
            this.spk     = spk;
            this.kpk     = kpk;
            this.preKeys = pks != null ? new ArrayList<>(pks) : new ArrayList<>();
            this.crypto  = crypto;
            this.nick    = nick;

            try {
                String raw = crypto.getString(CS_SESSION + nick.toLowerCase(), null);
                if (raw != null) {
                    SignalProtocolAddress a = new SignalProtocolAddress(nick.toLowerCase(), 1);
                    sessCache.put(a, new SessionRecord(
                            Base64.decode(raw, Base64.NO_WRAP | Base64.URL_SAFE)));
                }
            } catch (Exception e) {
                Log.w(TAG2, "Could not load session for " + nick, e);
            }
        }

        void flushSession(SignalProtocolAddress addr, String nick) {
            SessionRecord r = sessCache.get(addr);
            if (r != null)
                crypto.putString(CS_SESSION + nick.toLowerCase(),
                        Base64.encodeToString(r.serialize(),
                                Base64.NO_WRAP | Base64.URL_SAFE));
        }

        @Override
        public boolean containsSession(SignalProtocolAddress addr) {
            return sessCache.containsKey(addr)
                    || crypto.contains(CS_SESSION + addr.getName());
        }

        @Override public IdentityKeyPair getIdentityKeyPair()     { return idKP; }
        @Override public int             getLocalRegistrationId()  { return regId; }

        @Override
        public IdentityKeyStore.IdentityChange saveIdentity(SignalProtocolAddress address,
                                                            IdentityKey identityKey) {
            IdentityKey prev = idCache.put(address, identityKey);
            crypto.putString(CS_IDKEY + address.getName(),
                    Base64.encodeToString(identityKey.serialize(),
                            Base64.NO_WRAP | Base64.URL_SAFE));
            return (prev == null || identityKey.equals(prev))
                    ? IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
                    : IdentityKeyStore.IdentityChange.REPLACED_EXISTING;
        }

        @Override
        public boolean isTrustedIdentity(SignalProtocolAddress address,
                                         IdentityKey identityKey,
                                         Direction direction) {
            IdentityKey stored = idCache.get(address);
            if (stored == null) {
                String raw = crypto.getString(CS_IDKEY + address.getName(), null);
                if (raw == null) return true;
                try {
                    stored = new IdentityKey(
                            Base64.decode(raw, Base64.NO_WRAP | Base64.URL_SAFE), 0);
                    idCache.put(address, stored);
                } catch (Exception e) { return false; }
            }
            return stored.equals(identityKey);
        }

        @Override
        public IdentityKey getIdentity(SignalProtocolAddress address) {
            IdentityKey cached = idCache.get(address);
            if (cached != null) return cached;
            String raw = crypto.getString(CS_IDKEY + address.getName(), null);
            if (raw == null) return null;
            try {
                IdentityKey k = new IdentityKey(
                        Base64.decode(raw, Base64.NO_WRAP | Base64.URL_SAFE), 0);
                idCache.put(address, k);
                return k;
            } catch (Exception e) { return null; }
        }

        @Override
        public PreKeyRecord loadPreKey(int preKeyId)
                throws org.signal.libsignal.protocol.InvalidKeyIdException {
            for (PreKeyRecord pk : preKeys)
                if (pk.getId() == preKeyId) return pk;
            throw new org.signal.libsignal.protocol.InvalidKeyIdException(
                    "No pre-key " + preKeyId);
        }

        public void storePreKey(int preKeyId, PreKeyRecord record) {
            preKeys.removeIf(pk -> pk.getId() == preKeyId);
            preKeys.add(record);
            try {
                crypto.putString("signal_pk_id/" + preKeyId,
                        Base64.encodeToString(record.serialize(),
                                Base64.NO_WRAP | Base64.URL_SAFE));
            } catch (Exception ignored) {}
        }

        @Override
        public boolean containsPreKey(int preKeyId) {
            return preKeys.stream().anyMatch(pk -> pk.getId() == preKeyId);
        }

        @Override
        public void removePreKey(int preKeyId) {
            preKeys.removeIf(pk -> pk.getId() == preKeyId);
        }

        @Override
        public List<SignedPreKeyRecord> loadSignedPreKeys() {
            return spk != null ? Collections.singletonList(spk) : new ArrayList<>();
        }

        @Override
        public SignedPreKeyRecord loadSignedPreKey(int signedPreKeyId)
                throws org.signal.libsignal.protocol.InvalidKeyIdException {
            if (spk != null && spk.getId() == signedPreKeyId) return spk;
            throw new org.signal.libsignal.protocol.InvalidKeyIdException(
                    "No signed pre-key " + signedPreKeyId);
        }

        @Override public void storeSignedPreKey(int id, SignedPreKeyRecord record) {}
        public boolean containsSignedPreKey(int id) { return spk != null && spk.getId() == id; }
        public void removeSignedPreKey(int id) {}

        @Override
        public List<KyberPreKeyRecord> loadKyberPreKeys() {
            return kpk != null ? Collections.singletonList(kpk) : new ArrayList<>();
        }

        @Override
        public KyberPreKeyRecord loadKyberPreKey(int kyberPreKeyId)
                throws org.signal.libsignal.protocol.InvalidKeyIdException {
            if (kpk != null && kpk.getId() == kyberPreKeyId) return kpk;
            throw new org.signal.libsignal.protocol.InvalidKeyIdException(
                    "No Kyber pre-key " + kyberPreKeyId);
        }

        public void storeKyberPreKey(int id, KyberPreKeyRecord record) {}
        public boolean containsKyberPreKey(int id) { return kpk != null && kpk.getId() == id; }

        @Override
        public void markKyberPreKeyUsed(int kyberPreKeyId, int nextKyberPreKeyId,
                                        ECPublicKey nextSignedPreKey) {
        }

        @Override
        public SessionRecord loadSession(SignalProtocolAddress address) {
            SessionRecord r = sessCache.get(address);
            if (r != null) return r;
            return new SessionRecord();
        }

        @Override
        public List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses) {
            List<SessionRecord> result = new ArrayList<>();
            for (SignalProtocolAddress a : addresses) {
                SessionRecord r = sessCache.get(a);
                if (r != null) result.add(r);
            }
            return result;
        }

        public List<Integer> getSubDeviceSessions(String name) { return new ArrayList<>(); }

        @Override
        public void storeSession(SignalProtocolAddress address, SessionRecord record) {
            sessCache.put(address, record);
            crypto.putString(CS_SESSION + address.getName().toLowerCase(),
                    Base64.encodeToString(record.serialize(),
                            Base64.NO_WRAP | Base64.URL_SAFE));
        }

        @Override
        public void deleteSession(SignalProtocolAddress address) {
            sessCache.remove(address);
            crypto.remove(CS_SESSION + address.getName());
        }

        @Override
        public void deleteAllSessions(String name) {
            sessCache.entrySet().removeIf(e -> e.getKey().getName().equals(name));
            crypto.remove(CS_SESSION + name.toLowerCase());
        }
    }
}