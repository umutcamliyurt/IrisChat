package com.umut.irischat;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class CryptoStore {

    private static final String TAG = "CryptoStore";

    private static final String TRANSFORMATION  = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LEN       = 12;
    private static final int    GCM_TAG_BITS     = 128;

    private static final String SENTINEL         = "iris-crypto-v1";

    private static final String PREFS_NAME       = "IrisCrypto";
    private static final String KEY_SALT         = "salt";
    private static final String KEY_PW_CHECK     = "pw_check";
    private static final String KEY_WRAPPED_MK   = "wrapped_master_key";
    private static final String ENTRY_PREFIX     = "enc_";

    private static final int MAX_ENTRIES = 2048;

    private static final int MIN_PASSWORD_LENGTH = 4;
    private static final int MAX_PASSWORD_LENGTH = 1024;
    private static final int MAX_KEY_LENGTH      = 512;


    private static final String KEY_KDF        = "kdf_algo";
    private static final String KEY_KDF_MEM_KB = "kdf_mem_kb";
    private static final String KEY_KDF_ITERS  = "kdf_iterations";
    private static final String KEY_KDF_PAR    = "kdf_parallelism";

    private static final String KDF_ARGON2ID   = "argon2id";

    private static final int DERIVED_KEY_LEN_BYTES = 32;

    private static final int ARGON2_MEMORY_KB   = 65_536;
    private static final int ARGON2_ITERATIONS  = 3;
    private static final int ARGON2_PARALLELISM = 4;
    private static final int ARGON2_SALT_LEN    = 16;
    private static final int ARGON2_VERSION     = Argon2Parameters.ARGON2_VERSION_13;

    private static final String LEGACY_KDF_ALGORITHM   = "PBKDF2WithHmacSHA256";
    private static final int    LEGACY_KDF_ITERATIONS   = 310_000;
    private static final int    LEGACY_KEY_LENGTH_BITS  = DERIVED_KEY_LEN_BYTES * 8;

    private final SharedPreferences prefs;

    private volatile byte[] secretKeyBytes;

    public CryptoStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isInitialized() {
        return prefs.contains(KEY_SALT)
                && (prefs.contains(KEY_WRAPPED_MK) || prefs.contains(KEY_PW_CHECK));
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

    public String getEncryptionDescription() {
        return "AES-" + (DERIVED_KEY_LEN_BYTES * 8) + "-GCM";
    }

    public boolean isLegacyKdf() {
        return !prefs.contains(KEY_WRAPPED_MK) && prefs.contains(KEY_PW_CHECK);
    }

    public void initPassword(String password) {
        if (isInitialized()) throw new IllegalStateException("Password already set.");
        validatePassword(password);

        byte[] mk = new byte[DERIVED_KEY_LEN_BYTES];
        new SecureRandom().nextBytes(mk);

        byte[] salt = new byte[ARGON2_SALT_LEN];
        new SecureRandom().nextBytes(salt);

        byte[] kekBytes = deriveKeyBytesArgon2id(
                password, salt, ARGON2_MEMORY_KB, ARGON2_ITERATIONS, ARGON2_PARALLELISM);
        try {
            SecretKey kek = new SecretKeySpec(kekBytes, "AES");
            byte[] wrapped = encryptWithKey(mk, kek);
            prefs.edit()
                    .putString(KEY_SALT,       b64Encode(salt))
                    .putString(KEY_WRAPPED_MK, b64Encode(wrapped))
                    .putString(KEY_KDF,        KDF_ARGON2ID)
                    .putInt(KEY_KDF_MEM_KB,    ARGON2_MEMORY_KB)
                    .putInt(KEY_KDF_ITERS,     ARGON2_ITERATIONS)
                    .putInt(KEY_KDF_PAR,       ARGON2_PARALLELISM)
                    .apply();
            secretKeyBytes = mk;
            mk = null;
        } finally {
            Arrays.fill(kekBytes, (byte) 0);
            if (mk != null) Arrays.fill(mk, (byte) 0);
        }
    }

    public boolean unlock(String password) {
        if (!isInitialized()) throw new IllegalStateException("Store not initialised.");
        if (password == null) return false;

        boolean legacyFormat = !prefs.contains(KEY_WRAPPED_MK);
        boolean paramsStale  = false;
        if (!legacyFormat) {
            int mem   = prefs.getInt(KEY_KDF_MEM_KB, ARGON2_MEMORY_KB);
            int iters = prefs.getInt(KEY_KDF_ITERS,  ARGON2_ITERATIONS);
            int par   = prefs.getInt(KEY_KDF_PAR,    ARGON2_PARALLELISM);
            paramsStale = mem != ARGON2_MEMORY_KB
                    || iters != ARGON2_ITERATIONS
                    || par != ARGON2_PARALLELISM;
        }

        byte[] mk = unwrapMasterKeyWithPassword(password);
        if (mk == null) return false;

        zeroKeyBytes();
        secretKeyBytes = mk;

        if (legacyFormat || paramsStale) {
            migrateToCurrentArgon2Params(password);
        }

        return true;
    }

    public void lock() {
        zeroKeyBytes();
    }

    public boolean changePassword(String oldPassword, String newPassword) {
        if (!isInitialized()) return false;
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) return false;
        if (newPassword.length() > MAX_PASSWORD_LENGTH) return false;

        byte[] mk = unwrapMasterKeyWithPassword(oldPassword);
        if (mk == null) return false;

        byte[] newSalt = new byte[ARGON2_SALT_LEN];
        new SecureRandom().nextBytes(newSalt);
        byte[] newKek = deriveKeyBytesArgon2id(
                newPassword, newSalt, ARGON2_MEMORY_KB, ARGON2_ITERATIONS, ARGON2_PARALLELISM);

        try {
            SecretKey kek = new SecretKeySpec(newKek, "AES");
            byte[] wrapped = encryptWithKey(mk, kek);
            prefs.edit()
                    .putString(KEY_SALT,       b64Encode(newSalt))
                    .putString(KEY_WRAPPED_MK, b64Encode(wrapped))
                    .putString(KEY_KDF,        KDF_ARGON2ID)
                    .putInt(KEY_KDF_MEM_KB,    ARGON2_MEMORY_KB)
                    .putInt(KEY_KDF_ITERS,     ARGON2_ITERATIONS)
                    .putInt(KEY_KDF_PAR,       ARGON2_PARALLELISM)
                    .remove(KEY_PW_CHECK)
                    .apply();

            zeroKeyBytes();
            secretKeyBytes = mk;
            return true;
        } catch (Exception e) {
            Arrays.fill(mk, (byte) 0);
            return false;
        } finally {
            Arrays.fill(newKek, (byte) 0);
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


    private byte[] unwrapMasterKeyWithPassword(String password) {
        if (password == null) return null;

        byte[] salt = b64Decode(prefs.getString(KEY_SALT, ""));
        boolean hasWrappedKey = prefs.contains(KEY_WRAPPED_MK);
        byte[] kekBytes = null;

        try {
            if (hasWrappedKey) {
                int mem   = prefs.getInt(KEY_KDF_MEM_KB, ARGON2_MEMORY_KB);
                int iters = prefs.getInt(KEY_KDF_ITERS,  ARGON2_ITERATIONS);
                int par   = prefs.getInt(KEY_KDF_PAR,    ARGON2_PARALLELISM);
                kekBytes = deriveKeyBytesArgon2id(password, salt, mem, iters, par);
                SecretKey kek = new SecretKeySpec(kekBytes, "AES");
                byte[] wrapped = b64Decode(prefs.getString(KEY_WRAPPED_MK, ""));
                return decrypt(wrapped, kek);
            } else {
                byte[] legacyRaw = deriveKeyBytesPbkdf2Legacy(password, salt);
                SecretKey legacyKey = new SecretKeySpec(legacyRaw, "AES");
                byte[] blob = b64Decode(prefs.getString(KEY_PW_CHECK, ""));
                byte[] plaintext = decrypt(blob, legacyKey);
                if (!constantTimeEquals(plaintext, SENTINEL.getBytes(StandardCharsets.UTF_8))) {
                    Arrays.fill(legacyRaw, (byte) 0);
                    return null;
                }
                return legacyRaw;
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (kekBytes != null) Arrays.fill(kekBytes, (byte) 0);
        }
    }

    private void migrateToCurrentArgon2Params(String password) {
        byte[] mk = secretKeyBytes;
        if (mk == null) return;

        byte[] newSalt = new byte[ARGON2_SALT_LEN];
        new SecureRandom().nextBytes(newSalt);
        byte[] newKek = deriveKeyBytesArgon2id(
                password, newSalt, ARGON2_MEMORY_KB, ARGON2_ITERATIONS, ARGON2_PARALLELISM);
        try {
            SecretKey kek = new SecretKeySpec(newKek, "AES");
            byte[] wrapped = encryptWithKey(mk, kek);
            prefs.edit()
                    .putString(KEY_SALT,       b64Encode(newSalt))
                    .putString(KEY_WRAPPED_MK, b64Encode(wrapped))
                    .putString(KEY_KDF,        KDF_ARGON2ID)
                    .putInt(KEY_KDF_MEM_KB,    ARGON2_MEMORY_KB)
                    .putInt(KEY_KDF_ITERS,     ARGON2_ITERATIONS)
                    .putInt(KEY_KDF_PAR,       ARGON2_PARALLELISM)
                    .remove(KEY_PW_CHECK)
                    .apply();
        } catch (Exception e) {
            Log.w(TAG, "Argon2id KDF migration failed; will retry on next unlock", e);
        } finally {
            Arrays.fill(newKek, (byte) 0);
        }
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
        if (key.equals(KEY_SALT) || key.equals(KEY_PW_CHECK) || key.equals(KEY_WRAPPED_MK)) {
            throw new IllegalArgumentException("Reserved key name: " + key);
        }
        if (key.startsWith(ENTRY_PREFIX)) {
            throw new IllegalArgumentException("Key must not start with internal prefix.");
        }
    }


    private byte[] deriveKeyBytesArgon2id(String password, byte[] salt,
                                          int memoryKb, int iterations, int parallelism) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(ARGON2_VERSION)
                .withIterations(iterations)
                .withMemoryAsKB(memoryKb)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build();

        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);

        byte[] out = new byte[DERIVED_KEY_LEN_BYTES];
        char[] pwChars = password.toCharArray();
        try {
            generator.generateBytes(pwChars, out, 0, out.length);
            return out;
        } finally {
            Arrays.fill(pwChars, '\0');
        }
    }

    private byte[] deriveKeyBytesPbkdf2Legacy(String password, byte[] salt) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(LEGACY_KDF_ALGORITHM);
            KeySpec spec = new PBEKeySpec(
                    password.toCharArray(), salt, LEGACY_KDF_ITERATIONS, LEGACY_KEY_LENGTH_BITS);
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("Legacy key derivation failed", e);
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