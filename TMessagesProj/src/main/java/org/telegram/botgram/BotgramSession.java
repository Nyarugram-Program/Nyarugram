package org.telegram.botgram;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.function.BooleanSupplier;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Bearer token is encrypted with a non-exportable Android Keystore key. */
public final class BotgramSession {
    private static final String KEY = "botgram.session.v1";
    private final SharedPreferences prefs;
    public String server;
    public String userId;
    public String displayName;
    public String token;
    public volatile boolean largeClientHello;
    private String savedCiphertext;

    public BotgramSession(Context context, int account) {
        prefs = context.getSharedPreferences("botgram.session." + account, Context.MODE_PRIVATE);
        String savedServer = prefs.getString("server", "");
        server = BotgramServerAddress.migrateLegacy(savedServer);
        // Older sessions did not retain the login TLS option. Use the compatible
        // handshake for existing HTTPS bindings on supported Android versions.
        largeClientHello = prefs.getBoolean("largeClientHello",
                android.os.Build.VERSION.SDK_INT >= 29 && server.startsWith("https://"));
        if (!server.equals(savedServer)) prefs.edit().putString("server", server).apply();
        try {
            String encrypted = prefs.getString("token", null);
            savedCiphertext = encrypted;
            if (encrypted == null) return;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)));
            token = new String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8);
            userId = prefs.getString("userId", null);
            displayName = prefs.getString("displayName", "");
            if (userId == null) clear();
        } catch (Exception error) { clear(); }
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) store.getEntry(KEY, null)).getSecretKey();
    }

    public void save(String server, JSONObject response) throws Exception {
        save(server, response, () -> true);
    }

    public boolean save(String server, JSONObject response, BooleanSupplier active) throws Exception {
        JSONObject user = response.getJSONObject("user");
        return save(server, response.getString("token"), user.getString("id"), user.getString("displayName"), active);
    }

    /** Protobuf authentication plane: no JSON session object is parsed. */
    public boolean save(String server, String token, String userId, String displayName, BooleanSupplier active) throws Exception {
        return save(server, token, userId, displayName, false, active);
    }

    public boolean save(String server, String token, String userId, String displayName,
                        boolean largeClientHello, BooleanSupplier active) throws Exception {
        String newToken = token;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        String encrypted = Base64.encodeToString(cipher.doFinal(newToken.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        synchronized (prefs) {
            if (!active.getAsBoolean()) return false;
            if (!prefs.edit().putString("server", server).putString("token", encrypted)
                    .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString("userId", userId).putString("displayName", displayName)
                    .putBoolean("largeClientHello", largeClientHello).remove("protocol").commit()) {
                throw new java.io.IOException("Unable to save login session");
            }
            this.server = server;
            this.token = newToken;
            this.userId = userId;
            this.displayName = displayName;
            this.largeClientHello = largeClientHello;
            savedCiphertext = encrypted;
            if (!active.getAsBoolean()) {
                clearIfCurrent();
                return false;
            }
            return true;
        }
    }

    public String protocol() {
        synchronized (prefs) {
            return savedCiphertext != null && savedCiphertext.equals(prefs.getString("token", null))
                    ? prefs.getString("protocol", null) : null;
        }
    }

    public void setLargeClientHello(boolean enabled) throws java.io.IOException {
        synchronized (prefs) {
            if (token == null || savedCiphertext == null || !savedCiphertext.equals(prefs.getString("token", null)))
                throw new java.io.IOException("Session no longer current");
            if (!prefs.edit().putBoolean("largeClientHello", enabled).commit())
                throw new java.io.IOException("Unable to save TLS setting");
            largeClientHello = enabled;
        }
    }

    public void pinProtocol(String protocol) throws java.io.IOException {
        synchronized (prefs) {
            if (token == null || savedCiphertext == null || !savedCiphertext.equals(prefs.getString("token", null)))
                throw new java.io.IOException("Session no longer current");
            if (!prefs.edit().putString("protocol", protocol).commit())
                throw new java.io.IOException("Unable to pin session protocol");
        }
    }

    // A late/cancelled login must never erase a newer session for the same slot.
    public void clearIfCurrent() {
        synchronized (prefs) {
            if (savedCiphertext != null && savedCiphertext.equals(prefs.getString("token", null))) clear();
        }
    }

    public void clear() {
        synchronized (prefs) {
            token = null; userId = null; displayName = null;
            if (savedCiphertext != null && savedCiphertext.equals(prefs.getString("token", null))) {
                prefs.edit().remove("token").remove("iv").remove("userId").remove("displayName")
                        .remove("protocol").remove("largeClientHello").apply();
            }
        }
    }
}
