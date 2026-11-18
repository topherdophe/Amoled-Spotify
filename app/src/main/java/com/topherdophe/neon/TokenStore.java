package com.topherdophe.neon;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the Spotify login tokens encrypted with an AES-256-GCM key held in the Android Keystore.
 * The key never leaves secure hardware (where available), so the saved file is useless on its own.
 * If a value can't be decrypted (for example after the key was wiped) it is dropped and the user
 * simply logs in again. Tokens are never written in plain text.
 */
final class TokenStore {
    private static final String KEY_ALIAS = "neon_session_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private final SharedPreferences prefs;

    TokenStore(SharedPreferences prefs) {
        this.prefs = prefs;
        // Older builds saved tokens in plain text under these names: encrypt and remove them.
        migrate("access");
        migrate("refresh");
    }

    String get(String name) {
        String stored = prefs.getString(name + "_enc", "");
        if (stored.isEmpty()) return "";
        try {
            return decrypt(stored);
        } catch (Exception e) {
            remove(name);
            return "";
        }
    }

    void put(String name, String value) {
        if (value == null || value.isEmpty()) { remove(name); return; }
        try {
            prefs.edit().putString(name + "_enc", encrypt(value)).apply();
        } catch (Exception e) {
            // Keep the token in memory only rather than fall back to plain text.
            remove(name);
        }
    }

    void remove(String name) {
        prefs.edit().remove(name + "_enc").remove(name).apply();
    }

    private void migrate(String name) {
        String plain = prefs.getString(name, null);
        if (plain == null) return;
        prefs.edit().remove(name).apply();
        put(name, plain);
    }

    private static String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] data = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        Base64.Encoder b64 = Base64.getEncoder();
        return b64.encodeToString(cipher.getIV()) + ":" + b64.encodeToString(data);
    }

    private static String decrypt(String stored) throws Exception {
        int split = stored.indexOf(':');
        if (split <= 0) throw new IllegalArgumentException("Bad token format");
        Base64.Decoder b64 = Base64.getDecoder();
        byte[] iv = b64.decode(stored.substring(0, split));
        byte[] data = b64.decode(stored.substring(split + 1));
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
    }

    private static synchronized SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            ks.deleteEntry(KEY_ALIAS);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
