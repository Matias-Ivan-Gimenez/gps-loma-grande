package com.expresolomagrande.gps;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Guarda el usuario y la contraseña del panel cifrados con una clave del Android Keystore.
 *
 * La clave exige la huella (o el PIN/patrón del celular) CADA VEZ que se usa, y se invalida si se
 * agrega otra huella al celular. Lo guardado solo se puede descifrar dentro de esta app y solo
 * después de que la persona se identifica.
 */
final class SecureLogin {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "expreso_gps_login_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String PREFS = "secure_login";

    private final SharedPreferences prefs;

    SecureLogin(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean hasSaved() {
        return prefs.contains("iv") && prefs.contains("data");
    }

    boolean askDisabled() {
        return prefs.getBoolean("ask_disabled", false);
    }

    void disableAsking() {
        prefs.edit().putBoolean("ask_disabled", true).apply();
    }

    /** El celular necesita un bloqueo de pantalla (huella, PIN o patrón) para guardar claves protegidas. */
    static boolean deviceSecure(Context context) {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && keyguard.isDeviceSecure();
    }

    private SecretKey createKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true);

        if (Build.VERSION.SDK_INT >= 30) {
            // 0 = hay que autenticarse en cada uso.
            spec.setUserAuthenticationParameters(0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
        }

        generator.init(spec.build());
        return generator.generateKey();
    }

    private SecretKey existingKey() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        Key key = store.getKey(KEY_ALIAS, null);
        return key instanceof SecretKey ? (SecretKey) key : null;
    }

    private void deleteKey() {
        try {
            KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) {
                store.deleteEntry(KEY_ALIAS);
            }
        } catch (Exception ignored) {
            // Si no se puede borrar, la próxima clave nueva la reemplaza.
        }
    }

    /** Cifrador listo para pasarle a la huella; al guardar siempre se estrena una clave nueva. */
    Cipher newEncryptCipher() throws GeneralSecurityException, IOException {
        deleteKey();
        SecretKey key = createKey();
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher;
    }

    /** Descifrador listo para la huella. Lanza KeyPermanentlyInvalidatedException si cambió la huella. */
    Cipher newDecryptCipher() throws GeneralSecurityException, IOException {
        SecretKey key = existingKey();
        if (key == null) {
            throw new KeyStoreException("No hay clave guardada");
        }

        byte[] iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return cipher;
    }

    /** @param authed cifrador ya autorizado por la huella */
    void save(Cipher authed, String user, String password) throws GeneralSecurityException, JSONException {
        JSONObject json = new JSONObject();
        json.put("u", user);
        json.put("p", password);

        byte[] encrypted = authed.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
        prefs.edit()
                .putString("iv", Base64.encodeToString(authed.getIV(), Base64.NO_WRAP))
                .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .apply();
    }

    /** @param authed descifrador ya autorizado por la huella */
    String[] load(Cipher authed) throws GeneralSecurityException, JSONException {
        byte[] plain = authed.doFinal(Base64.decode(prefs.getString("data", ""), Base64.NO_WRAP));
        JSONObject json = new JSONObject(new String(plain, StandardCharsets.UTF_8));

        return new String[]{json.getString("u"), json.getString("p")};
    }

    void clear() {
        deleteKey();
        prefs.edit().remove("iv").remove("data").apply();
    }
}
