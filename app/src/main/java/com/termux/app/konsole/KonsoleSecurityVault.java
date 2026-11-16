package com.termux.app.konsole;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Hardware-backed Security & Secret Vault stored in app-private storage (never on shared /sdcard).
 * Replaces plaintext/external-storage sudo.xml locking with PBKDF2 passphrase hashing and
 * AndroidKeyStore AES-256-GCM encryption for SSH keys, API tokens, and vault lock state.
 */
public final class KonsoleSecurityVault {

    private static final String TAG = "KonsoleSecurityVault";
    private static final String PREFS_NAME = "konsole_security_vault_prefs";
    private static final String KEY_ALIAS = "konsole_os_master_vault_key_v1";
    private static final String PREF_PASS_HASH = "vault_pass_pbkdf2_hash";
    private static final String PREF_PASS_SALT = "vault_pass_salt";
    private static final String PREF_VAULT_LOCKED = "vault_locked";
    private static final String PREF_ENCRYPTED_SECRETS = "vault_encrypted_secrets_blob";
    private static final String PREF_FALLBACK_KEY = "vault_fallback_aes_key";

    private static KonsoleSecurityVault sInstance;
    private volatile boolean mSessionLocked = false;

    private KonsoleSecurityVault() {
    }

    public static synchronized KonsoleSecurityVault getInstance() {
        if (sInstance == null) {
            sInstance = new KonsoleSecurityVault();
        }
        return sInstance;
    }

    private SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean hasPassphrase(Context context) {
        if (context == null) return false;
        String hash = prefs(context).getString(PREF_PASS_HASH, "");
        return hash != null && !hash.isEmpty();
    }

    public boolean isLocked(Context context) {
        if (mSessionLocked) return true;
        return context != null && prefs(context).getBoolean(PREF_VAULT_LOCKED, false);
    }

    public synchronized void setLocked(Context context, boolean locked) {
        mSessionLocked = locked;
        if (context != null) {
            prefs(context).edit().putBoolean(PREF_VAULT_LOCKED, locked).apply();
        }
    }

    public synchronized boolean setPassphrase(Context context, String passphrase) {
        if (context == null || passphrase == null || passphrase.length() < 4) return false;
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP);
        String hashB64 = pbkdf2(passphrase, salt);
        if (hashB64 == null) return false;
        prefs(context).edit()
                .putString(PREF_PASS_SALT, saltB64)
                .putString(PREF_PASS_HASH, hashB64)
                .apply();
        return true;
    }

    public synchronized boolean verifyPassphrase(Context context, String candidate) {
        if (context == null || candidate == null) return false;
        SharedPreferences p = prefs(context);
        String saltB64 = p.getString(PREF_PASS_SALT, "");
        String expected = p.getString(PREF_PASS_HASH, "");
        if (saltB64.isEmpty() || expected.isEmpty()) return false;
        byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
        String actual = pbkdf2(candidate, salt);
        return constantTimeEquals(expected, actual);
    }

    public synchronized boolean unlockWithPassphrase(Context context, String candidate) {
        if (verifyPassphrase(context, candidate)) {
            setLocked(context, false);
            return true;
        }
        return false;
    }

    public synchronized void clearPassphraseAndUnlock(Context context) {
        mSessionLocked = false;
        if (context != null) {
            prefs(context).edit()
                    .remove(PREF_PASS_HASH)
                    .remove(PREF_PASS_SALT)
                    .putBoolean(PREF_VAULT_LOCKED, false)
                    .apply();
        }
    }

    public synchronized boolean putSecret(Context context, String key, String value) {
        if (context == null || key == null || key.trim().isEmpty()) return false;
        try {
            JSONObject obj = loadSecretsObject(context);
            obj.put(key.trim(), value != null ? value : "");
            return saveSecretsObject(context, obj);
        } catch (Exception e) {
            Log.e(TAG, "putSecret failed", e);
            return false;
        }
    }

    public synchronized String getSecret(Context context, String key) {
        if (context == null || key == null || key.trim().isEmpty() || isLocked(context)) return null;
        try {
            JSONObject obj = loadSecretsObject(context);
            return obj.has(key.trim()) ? obj.optString(key.trim(), null) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized boolean deleteSecret(Context context, String key) {
        if (context == null || key == null || key.trim().isEmpty()) return false;
        try {
            JSONObject obj = loadSecretsObject(context);
            if (!obj.has(key.trim())) return false;
            obj.remove(key.trim());
            return saveSecretsObject(context, obj);
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized List<String> listSecretKeys(Context context) {
        List<String> keys = new ArrayList<>();
        if (context == null) return keys;
        try {
            JSONObject obj = loadSecretsObject(context);
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                keys.add(it.next());
            }
            Collections.sort(keys);
        } catch (Exception ignored) {
        }
        return keys;
    }

    /**
     * Panic action: stops rootless packet capture, stops overlay shade, wipes ephemeral
     * incognito workspaces, and locks the vault.
     */
    public synchronized String triggerPanicLockdown(Context context) {
        setLocked(context, true);
        if (context == null) return "Vault locked.";

        int wiped = 0;
        File incognitoRoot = new File(context.getCacheDir(), "konsole_incognito");
        if (incognitoRoot.exists()) {
            wiped = countAndDelete(incognitoRoot);
        }
        try {
            KonsolePcapVpnService.stopCapture(context);
        } catch (Exception ignored) {
        }
        try {
            KonsoleShadeService.stopService(context);
        } catch (Exception ignored) {
        }
        return "Vault locked, PCAP stopped, wiped " + wiped + " ephemeral file(s).";
    }

    /** Removes legacy plaintext/external-storage sudo.xml if present from older builds. */
    public static void migrateRemoveLegacyExternalSudoXml() {
        try {
            File legacy = new File(KonsoleConfigManager.CUSTOMIZE_PATH, "sudo.xml");
            if (legacy.exists()) {
                //noinspection ResultOfMethodCallIgnored
                legacy.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private JSONObject loadSecretsObject(Context context) {
        String blob = prefs(context).getString(PREF_ENCRYPTED_SECRETS, "");
        if (blob == null || blob.isEmpty()) return new JSONObject();
        try {
            String plain = decryptString(context, blob);
            return new JSONObject(plain);
        } catch (Exception e) {
            Log.w(TAG, "Unable to decrypt secrets blob: " + e.getMessage());
            return new JSONObject();
        }
    }

    private boolean saveSecretsObject(Context context, JSONObject obj) {
        try {
            String encrypted = encryptString(context, obj.toString());
            if (encrypted == null) return false;
            prefs(context).edit().putString(PREF_ENCRYPTED_SECRETS, encrypted).apply();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Unable to encrypt secrets blob", e);
            return false;
        }
    }

    private SecretKey getOrCreateMasterKey(Context context) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                ks.load(null);
                if (ks.containsAlias(KEY_ALIAS)) {
                    KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
                    if (entry instanceof KeyStore.SecretKeyEntry) {
                        return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
                    }
                }
                KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                kg.init(new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build());
                return kg.generateKey();
            } catch (Throwable keyStoreUnavailable) {
                // Fallback only for environments lacking AndroidKeyStore (e.g. Robolectric JVM tests)
            }
        }
        SharedPreferences p = prefs(context);
        String rawB64 = p.getString(PREF_FALLBACK_KEY, "");
        byte[] raw;
        if (rawB64.isEmpty()) {
            raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            p.edit().putString(PREF_FALLBACK_KEY, Base64.encodeToString(raw, Base64.NO_WRAP)).apply();
        } else {
            raw = Base64.decode(rawB64, Base64.NO_WRAP);
        }
        return new SecretKeySpec(raw, "AES");
    }

    private String encryptString(Context context, String plaintext) throws Exception {
        SecretKey key = getOrCreateMasterKey(context);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
    }

    private String decryptString(Context context, String blob) throws Exception {
        String[] parts = blob.split(":", 2);
        if (parts.length != 2) throw new IllegalArgumentException("Malformed vault payload");
        byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] ct = Base64.decode(parts[1], Base64.NO_WRAP);
        SecretKey key = getOrCreateMasterKey(context);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] pt = cipher.doFinal(ct);
        return new String(pt, StandardCharsets.UTF_8);
    }

    private static String pbkdf2(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] encoded = skf.generateSecret(spec).getEncoded();
            return Base64.encodeToString(encoded, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int res = 0;
        for (int i = 0; i < a.length(); i++) {
            res |= a.charAt(i) ^ b.charAt(i);
        }
        return res == 0;
    }

    private static int countAndDelete(File f) {
        if (f == null || !f.exists()) return 0;
        int count = 0;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) count += countAndDelete(c);
            }
        } else {
            count = 1;
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        return count;
    }
}
