package com.neurallink.chat;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class CredentialVault {
    private final String alias;
    private final SharedPreferences preferences;

    CredentialVault(Context context) {
        this(context, "provider-vault");
    }

    CredentialVault(Context context, String name) {
        alias = "hackgpt-" + name;
        preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private SecretKey key(boolean create) throws Exception {
        var store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        var existing = (SecretKey) store.getKey(alias, null);
        if (existing != null) return existing;
        if (!create) throw new IOException("Provider vault key is unavailable.");
        var generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    JSONArray load() throws Exception {
        String value = preferences.getString("providers", "");
        if (value.isEmpty()) return new JSONArray();
        byte[] envelope = Base64.decode(value, Base64.DEFAULT);
        if (envelope.length < 29) throw new IOException("Invalid provider vault.");
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, envelope, 0, 12));
        return new JSONArray(new String(cipher.doFinal(envelope, 12, envelope.length - 12),
                StandardCharsets.UTF_8));
    }

    void save(JSONArray providers) throws Exception {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        byte[] encrypted = cipher.doFinal(providers.toString().getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        if (iv.length != 12) throw new IOException("Invalid provider vault IV.");
        byte[] envelope = Arrays.copyOf(iv, iv.length + encrypted.length);
        System.arraycopy(encrypted, 0, envelope, iv.length, encrypted.length);
        if (!preferences.edit().putString("providers", Base64.encodeToString(envelope, Base64.NO_WRAP)).commit()) {
            throw new IOException("Could not write provider vault.");
        }
    }
}
