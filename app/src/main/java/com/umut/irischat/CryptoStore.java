package com.umut.irischat;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class CryptoStore {

    private static final String TRANSFORMATION  = "AES/GCM/NoPadding";
    private static final String KDF_ALGORITHM   = "PBKDF2WithHmacSHA256";
    private static final int    KEY_LENGTH_BITS  = 256;
    private static final int    GCM_IV_LEN       = 12;
    private static final int    GCM_TAG_BITS     = 128;
    private static final int    SALT_LEN         = 32;
    private static final int    KDF_ITERATIONS   = 310_000;

    private static final String SENTINEL         = "iris-crypto-v1";

    private static final String PREFS_NAME       = "IrisCrypto";
    private static final String KEY_SALT         = "salt";
    private static final String KEY_PW_CHECK     = "pw_check";
    private static final String ENTRY_PREFIX     = "enc_";

    private static final int MAX_ENTRIES = 2048;

    private static final int MIN_PASSWORD_LENGTH = 4;

    private static final int MAX_PASSWORD_LENGTH = 1024;
    private static final int MAX_KEY_LENGTH      = 512;

    private final SharedPreferences prefs;

    private volatile byte[] secretKeyBytes;

    public CryptoStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isInitialized() {
        return prefs.contains(KEY_SALT) && prefs.contains(KEY_PW_CHECK);
    }

    public boolean isUnlocked() {
        return secretKeyBytes != null;
    }

    private SecretKey currentKey() {
        byte[] b = secretKeyBytes;
        if (b == null) throw new IllegalStateException("CryptoStore is locked.");
        return new SecretKeySpec(b, "AES");
    }

    public SecretKey getMasterKey() {
        requireUnlocked();
        return currentKey();
    }

    public void initPassword(String password) {
        if (isInitialized()) throw new IllegalStateException("Password already set.");
        validatePassword(password);

        byte[] salt = new byte[SALT_LEN];
        new SecureRandom().nextBytes(salt);

        byte[] rawKey = deriveKeyBytes(password, salt);
        try {
            SecretKey key = new SecretKeySpec(rawKey, "AES");
            String pwCheck = b64Encode(encryptWithKey(SENTINEL.getBytes(StandardCharsets.UTF_8), key));
            prefs.edit()
                    .putString(KEY_SALT,     b64Encode(salt))
                    .putString(KEY_PW_CHECK, pwCheck)
                    .apply();
            secretKeyBytes = rawKey;
            rawKey = null;
        } finally {
            if (rawKey != null) Arrays.fill(rawKey, (byte) 0);
        }
    }

    public boolean unlock(String password) {
        if (!isInitialized()) throw new IllegalStateException("Store not initialised.");
        if (password == null) return false;

        byte[] salt   = b64Decode(prefs.getString(KEY_SALT, ""));
        byte[] rawKey = deriveKeyBytes(password, salt);
        try {
            SecretKey key = new SecretKeySpec(rawKey, "AES");
            byte[] blob      = b64Decode(prefs.getString(KEY_PW_CHECK, ""));
            byte[] plaintext = decrypt(blob, key);
            byte[] expected  = SENTINEL.getBytes(StandardCharsets.UTF_8);
            if (!constantTimeEquals(plaintext, expected)) {
                return false;
            }
            zeroKeyBytes();
            secretKeyBytes = rawKey;
            rawKey = null;
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (rawKey != null) Arrays.fill(rawKey, (byte) 0);
        }
    }

    public void lock() {
        zeroKeyBytes();
    }

    public boolean changePassword(String oldPassword, String newPassword) {
        if (!isInitialized()) return false;
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) return false;
        if (newPassword.length() > MAX_PASSWORD_LENGTH) return false;

        byte[] oldSalt  = b64Decode(prefs.getString(KEY_SALT, ""));
        byte[] oldRaw   = deriveKeyBytes(oldPassword, oldSalt);
        SecretKey oldKey = new SecretKeySpec(oldRaw, "AES");

        try {
            byte[] blob = b64Decode(prefs.getString(KEY_PW_CHECK, ""));
            byte[] pt   = decrypt(blob, oldKey);
            if (!constantTimeEquals(pt, SENTINEL.getBytes(StandardCharsets.UTF_8))) return false;
        } catch (Exception e) {
            return false;
        } finally {
            Arrays.fill(oldRaw, (byte) 0);
        }

        oldRaw = deriveKeyBytes(oldPassword, oldSalt);
        oldKey = new SecretKeySpec(oldRaw, "AES");

        byte[] newSalt = new byte[SALT_LEN];
        new SecureRandom().nextBytes(newSalt);
        byte[] newRaw = deriveKeyBytes(newPassword, newSalt);
        SecretKey newKey = new SecretKeySpec(newRaw, "AES");

        try {
            SharedPreferences.Editor editor = prefs.edit();
            Map<String, ?> snapshot = prefs.getAll();

            for (Map.Entry<String, ?> entry : snapshot.entrySet()) {
                String prefKey = entry.getKey();
                if (!prefKey.startsWith(ENTRY_PREFIX)) continue;
                try {
                    Object raw = entry.getValue();
                    if (!(raw instanceof String)) continue;
                    byte[] oldBlob = b64Decode((String) raw);
                    byte[] plainPt = decrypt(oldBlob, oldKey);
                    byte[] newBlob = encryptWithKey(plainPt, newKey);
                    editor.putString(prefKey, b64Encode(newBlob));
                } catch (Exception ignored) {
                    editor.remove(prefKey);
                }
            }

            String newPwCheck = b64Encode(encryptWithKey(
                    SENTINEL.getBytes(StandardCharsets.UTF_8), newKey));
            editor.putString(KEY_SALT,     b64Encode(newSalt));
            editor.putString(KEY_PW_CHECK, newPwCheck);
            editor.apply();

            zeroKeyBytes();
            secretKeyBytes = newRaw;
            newRaw = null;
            return true;
        } finally {
            Arrays.fill(oldRaw, (byte) 0);
            if (newRaw != null) Arrays.fill(newRaw, (byte) 0);
        }
    }

    public void putString(String key, String value) {
        requireUnlocked();
        validateEntryKey(key);

        if (value == null) {
            prefs.edit().remove(ENTRY_PREFIX + key).apply();
            return;
        }

        long encryptedCount = prefs.getAll().keySet().stream()
                .filter(k -> k.startsWith(ENTRY_PREFIX)).count();
        if (!prefs.contains(ENTRY_PREFIX + key) && encryptedCount >= MAX_ENTRIES) {
            throw new IllegalStateException("CryptoStore entry limit reached (" + MAX_ENTRIES + ").");
        }

        byte[] blob = encryptWithKey(value.getBytes(StandardCharsets.UTF_8), currentKey());
        prefs.edit().putString(ENTRY_PREFIX + key, b64Encode(blob)).apply();
    }

    public String getString(String key, String defValue) {
        requireUnlocked();
        String encoded = prefs.getString(ENTRY_PREFIX + key, null);
        if (encoded == null) return defValue;
        try {
            byte[] blob = b64Decode(encoded);
            return new String(decrypt(blob, currentKey()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return defValue;
        }
    }

    public void remove(String key) {
        prefs.edit().remove(ENTRY_PREFIX + key).apply();
    }

    public boolean contains(String key) {
        return prefs.contains(ENTRY_PREFIX + key);
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password too long.");
        }
    }

    private static void validateEntryKey(String key) {
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("Key must not be empty.");
        if (key.length() > MAX_KEY_LENGTH) throw new IllegalArgumentException("Key too long.");
        if (key.equals(KEY_SALT) || key.equals(KEY_PW_CHECK)) {
            throw new IllegalArgumentException("Reserved key name: " + key);
        }
        if (key.startsWith(ENTRY_PREFIX)) {
            throw new IllegalArgumentException("Key must not start with internal prefix.");
        }
    }

    private byte[] deriveKeyBytes(String password, byte[] salt) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_ALGORITHM);
            KeySpec spec = new PBEKeySpec(
                    password.toCharArray(), salt, KDF_ITERATIONS, KEY_LENGTH_BITS);
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("Key derivation failed", e);
        }
    }

    private byte[] encryptWithKey(byte[] plaintext, SecretKey key) {
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertextAndTag = cipher.doFinal(plaintext);

            byte[] out = new byte[GCM_IV_LEN + ciphertextAndTag.length];
            System.arraycopy(iv, 0, out, 0, GCM_IV_LEN);
            System.arraycopy(ciphertextAndTag, 0, out, GCM_IV_LEN, ciphertextAndTag.length);
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    private byte[] decrypt(byte[] blob, SecretKey key) {
        try {
            if (blob.length < GCM_IV_LEN + 1) throw new IllegalArgumentException("Blob too short");

            byte[] iv            = Arrays.copyOfRange(blob, 0, GCM_IV_LEN);
            byte[] ciphertextTag = Arrays.copyOfRange(blob, GCM_IV_LEN, blob.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(ciphertextTag);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    private synchronized void zeroKeyBytes() {
        if (secretKeyBytes != null) {
            Arrays.fill(secretKeyBytes, (byte) 0);
            secretKeyBytes = null;
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) return false;
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= (a[i] ^ b[i]);
        return diff == 0;
    }

    private static String b64Encode(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP | Base64.URL_SAFE);
    }

    private static byte[] b64Decode(String s) {
        return Base64.decode(s, Base64.NO_WRAP | Base64.URL_SAFE);
    }

    private void requireUnlocked() {
        if (secretKeyBytes == null) throw new IllegalStateException("CryptoStore is locked.");
    }
}
