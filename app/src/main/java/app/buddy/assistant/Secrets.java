package app.buddy.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Secrets the app keeps for the user (an agent's API key), encrypted with a key in Android's Keystore: a copy of
 * Buddy's storage alone doesn't reveal them. They stay out of Buddy's Linux, where any agent could read a file.
 */
final class Secrets {
    private static final String ALIAS = "buddy_secrets";

    private Secrets() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("secrets", Context.MODE_PRIVATE);
    }

    static synchronized void put(Context c, String name, String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] data = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        sp(c).edit().putString(name, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(data, Base64.NO_WRAP)).apply();
    }

    /** The secret, or null when there's none (or it can't be read any more). */
    static synchronized String get(Context c, String name) {
        String s = sp(c).getString(name, null);
        if (s == null || s.indexOf(':') < 0) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                    Base64.decode(s.substring(0, s.indexOf(':')), Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(s.substring(s.indexOf(':') + 1), Base64.NO_WRAP)),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean has(Context c, String name) {
        return sp(c).contains(name);
    }

    static synchronized void remove(Context c, String name) {
        sp(c).edit().remove(name).apply();
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            g.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }
}
