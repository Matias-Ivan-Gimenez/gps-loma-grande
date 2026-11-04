package com.expresolomagrande.gps;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Busca versiones nuevas de la app en las "Releases" de GitHub (repositorio público) y avisa.
 *
 * No instala nada por su cuenta: solo ofrece abrir el enlace de descarga. Android instala la
 * actualización encima de la app actual únicamente si viene firmada con la misma clave.
 */
final class UpdateChecker {

    interface Listener {
        /** Se llama en el hilo principal solo cuando hay una versión más nueva. */
        void onUpdateAvailable(String version, String downloadUrl);
    }

    private static final String PREFS = "updates";
    private static final long CHECK_EVERY_MS = 6L * 60 * 60 * 1000;
    private static final long LATER_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_BYTES = 256 * 1024;

    private UpdateChecker() {
    }

    static void check(final MainActivity activity, final Listener listener) {
        final String repo = activity.getString(R.string.update_repo).trim();
        if (repo.isEmpty() || !repo.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")) {
            return;
        }

        final SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("last_check", 0L) < CHECK_EVERY_MS) {
            return;
        }

        final String current = installedVersion(activity);

        new Thread(() -> {
            try {
                String[] found = latestRelease(repo, current);
                prefs.edit().putLong("last_check", System.currentTimeMillis()).apply();
                if (found == null || !isNewer(found[0], current)) {
                    return;
                }

                // "Más tarde" calla el aviso de esa versión durante 24 horas.
                if (found[0].equals(prefs.getString("later_version", ""))
                        && System.currentTimeMillis() - prefs.getLong("later_at", 0L) < LATER_MS) {
                    return;
                }

                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        listener.onUpdateAvailable(found[0], found[1]);
                    }
                });
            } catch (Exception ignored) {
                // Sin Internet o GitHub no responde: no pasa nada, se reintenta más tarde.
            }
        }, "update-check").start();
    }

    static void postpone(Context context, String version) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("later_version", version)
                .putLong("later_at", System.currentTimeMillis())
                .apply();
    }

    /** @return {versión, enlace de descarga} de la última release, o null si no se pudo leer. */
    private static String[] latestRelease(String repo, String current) throws Exception {
        URL url = new URL("https://api.github.com/repos/" + repo + "/releases/latest");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "ExpresoLomaGrandeApp/" + current);

        try {
            if (conn.getResponseCode() != 200) {
                return null;
            }

            JSONObject json = new JSONObject(readLimited(conn.getInputStream()));
            String tag = json.optString("tag_name", "").trim();
            if (tag.isEmpty() || json.optBoolean("draft") || json.optBoolean("prerelease")) {
                return null;
            }

            String link = json.optString("html_url", "");
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String name = asset.optString("name", "").toLowerCase(Locale.ROOT);
                    if (name.endsWith(".apk")) {
                        link = asset.optString("browser_download_url", link);
                        break;
                    }
                }
            }

            if (!trustedLink(link)) {
                return null;
            }

            return new String[]{tag, link};
        } finally {
            conn.disconnect();
        }
    }

    private static String readLimited(InputStream in) throws Exception {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_BYTES) {
                    throw new IllegalStateException("respuesta demasiado grande");
                }
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    /** Solo se abren enlaces https de GitHub. */
    static boolean trustedLink(String link) {
        try {
            java.net.URI uri = new java.net.URI(link);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(uri.getScheme())
                    && (host.equals("github.com") || host.endsWith(".github.com") || host.endsWith(".githubusercontent.com"));
        } catch (Exception e) {
            return false;
        }
    }

    static String installedVersion(Context context) {
        try {
            String name = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            return name == null ? "0" : name;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** "v1.2.0" > "1.1.9". Compara número por número. */
    static boolean isNewer(String remote, String local) {
        int[] a = parse(remote);
        int[] b = parse(local);
        int size = Math.max(a.length, b.length);

        for (int i = 0; i < size; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y;
            }
        }

        return false;
    }

    static int[] parse(String version) {
        String clean = version == null ? "" : version.trim().replaceFirst("^[vV]", "");
        int dash = clean.indexOf('-');
        if (dash >= 0) {
            clean = clean.substring(0, dash);
        }

        String[] parts = clean.split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String digits = parts[i].replaceAll("[^0-9]", "");
            try {
                numbers[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                numbers[i] = 0;
            }
        }

        return numbers;
    }
}
