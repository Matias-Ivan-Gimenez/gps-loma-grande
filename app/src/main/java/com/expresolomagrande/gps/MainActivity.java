package com.expresolomagrande.gps;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

import java.util.Locale;

import javax.crypto.Cipher;

/**
 * Expreso Loma Grande: abre directamente el seguimiento GPS del panel de Expreso Loma Grande.
 *
 * Es el mismo sistema web (mismo usuario, mismas pantallas y datos), pero dentro de una app.
 *
 * Seguridad:
 *  - Solo se navega dentro de panel.expresolomagrande.com por HTTPS; cualquier otro enlace se
 *    abre en el navegador del celular.
 *  - Un certificado inválido nunca se acepta.
 *  - No se accede a archivos ni a la ubicación del celular, y la depuración remota está apagada.
 *  - El único puente entre la página y la app es "GpsApp.candidate": solo ENTREGA a la app lo que
 *    la persona escribió en el login (para ofrecerle guardarlo con huella); no devuelve nada.
 *  - El usuario y la contraseña guardados quedan cifrados y solo se abren con la huella o el PIN.
 */
public class MainActivity extends Activity {

    private static final String HOST = "panel.expresolomagrande.com";
    private static final String HOME_URL = "https://" + HOST + "/index.php?page=seguimiento_gps";
    /** El panel detecta esta marca para ocultar los menús y mostrar solo el GPS. */
    private static final String UA_TAG = "GPSLomaGrandeApp/1.1";

    /** En la pantalla de login: avisa a la app lo que se escribió al enviar el formulario. */
    private static final String HOOK_JS =
            "(function(){if(window.__gpsHook){return;}window.__gpsHook=true;"
                    + "var a=document.getElementById('usuario'),b=document.getElementById('password');"
                    + "if(!a||!b||!a.form){return;}"
                    + "a.form.addEventListener('submit',function(){try{GpsApp.candidate(a.value,b.value);}catch(e){}});})();";

    /** Texto del aviso de la página de login (por ejemplo "La sesión venció"). */
    private static final String FLASH_JS =
            "(function(){var a=document.querySelector('.alert');return a?a.textContent:'';})()";

    private interface CipherAction {
        void run(Cipher cipher);
    }

    private WebView web;
    private ProgressBar progress;
    private LinearLayout offline;
    private TextView offlineText;
    private LinearLayout quickBar;

    private boolean loadFailed;
    /** Dirección que falló: sirve para que la página de error interna de Chrome no tape el aviso propio. */
    private String failedUrl;

    private SecureLogin secure;
    private volatile String candidateUser;
    private volatile String candidatePassword;
    private boolean freshLaunch;
    private boolean promptBusy;
    private long lastAutoSubmit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        secure = new SecureLogin(this);
        freshLaunch = savedInstanceState == null;

        buildLayout();
        configureWebView();

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(HOME_URL);
        }

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }

        UpdateChecker.check(this, this::showUpdateDialog);
    }

    /* ------------------------------------------------------------------ */
    /* Pantalla                                                            */
    /* ------------------------------------------------------------------ */

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.gps_navy));

        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        progress.setProgressTintList(ColorStateList.valueOf(getColor(R.color.gps_blue)));
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        progressParams.gravity = Gravity.TOP;
        root.addView(progress, progressParams);

        // Barra de entrada rápida: solo aparece en la pantalla de login cuando hay un acceso guardado.
        quickBar = new LinearLayout(this);
        quickBar.setOrientation(LinearLayout.HORIZONTAL);
        quickBar.setGravity(Gravity.CENTER_VERTICAL);
        quickBar.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable barBackground = new GradientDrawable();
        barBackground.setColor(Color.WHITE);
        barBackground.setCornerRadius(dp(28));
        barBackground.setStroke(dp(1), 0xFFDCE3EE);
        quickBar.setBackground(barBackground);
        quickBar.setElevation(dp(6));
        quickBar.setVisibility(View.GONE);

        Button enter = new Button(this);
        enter.setText(R.string.quick_enter);
        enter.setAllCaps(false);
        enter.setTypeface(Typeface.DEFAULT_BOLD);
        enter.setOnClickListener(v -> loginWithSaved());
        quickBar.addView(enter, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button forget = new Button(this, null, android.R.attr.borderlessButtonStyle);
        forget.setText(R.string.quick_forget);
        forget.setAllCaps(false);
        forget.setOnClickListener(v -> confirmForget());
        quickBar.addView(forget, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.gravity = Gravity.BOTTOM;
        barParams.setMargins(dp(16), dp(16), dp(16), dp(16));
        root.addView(quickBar, barParams);

        offline = new LinearLayout(this);
        offline.setOrientation(LinearLayout.VERTICAL);
        offline.setGravity(Gravity.CENTER);
        offline.setPadding(dp(32), dp(32), dp(32), dp(32));
        offline.setBackgroundColor(Color.WHITE);
        offline.setVisibility(View.GONE);

        TextView title = new TextView(this);
        title.setText(R.string.offline_title);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getColor(R.color.gps_text));
        title.setGravity(Gravity.CENTER);
        offline.addView(title);

        offlineText = new TextView(this);
        offlineText.setText(R.string.offline_text);
        offlineText.setTextSize(15);
        offlineText.setTextColor(getColor(R.color.gps_muted));
        offlineText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = dp(12);
        textParams.bottomMargin = dp(24);
        offline.addView(offlineText, textParams);

        Button retry = new Button(this);
        retry.setText(R.string.retry);
        retry.setAllCaps(false);
        retry.setOnClickListener(v -> retry());
        offline.addView(retry);

        root.addView(offline, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Desde Android 15 las apps dibujan bajo las barras del sistema: se deja el margen necesario.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;

            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }

            view.setPadding(left, top, right, bottom);

            return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
        });

        setContentView(root);
    }

    private void showOffline(String message) {
        loadFailed = true;
        offlineText.setText(message);
        offline.setVisibility(View.VISIBLE);
        quickBar.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
    }

    private void hideOffline() {
        offline.setVisibility(View.GONE);
    }

    private void retry() {
        loadFailed = false;
        failedUrl = null;
        hideOffline();

        String current = web.getUrl();
        if (current != null && current.startsWith("https://" + HOST + "/")) {
            web.loadUrl(current);
        } else {
            web.loadUrl(HOME_URL);
        }
    }

    private void toast(int message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /* ------------------------------------------------------------------ */
    /* WebView                                                             */
    /* ------------------------------------------------------------------ */

    private void configureWebView() {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); // Necesario: el mapa y la actualización en vivo usan JavaScript.
        settings.setDomStorageEnabled(true); // Recuerda el estilo de mapa y los filtros elegidos.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setGeolocationEnabled(false);
        settings.setSupportMultipleWindows(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSafeBrowsingEnabled(true);
        settings.setUserAgentString(settings.getUserAgentString() + " " + UA_TAG);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, false);

        WebView.setWebContentsDebuggingEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new LoginBridge(), "GpsApp");
        web.setWebViewClient(new PanelClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
    }

    private static boolean isPanel(Uri uri) {
        return uri != null && "https".equals(uri.getScheme()) && HOST.equalsIgnoreCase(uri.getHost());
    }

    /** La pantalla de login es index.php sin ninguna sección (?page=) ni acción (?action=). */
    private static boolean isLoginPage(Uri uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        boolean root = path.isEmpty() || path.equals("/") || path.equals("/index.php");

        return root && uri.getQueryParameter("page") == null && uri.getQueryParameter("action") == null;
    }

    private void openInBrowser(Uri uri) {
        if (uri == null) {
            return;
        }

        String scheme = uri.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            return; // Nada de tel:, intent:, file: ni otros esquemas.
        }

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            // El celular no tiene navegador: no pasa nada.
        }
    }

    /** Puente mínimo: la página le entrega a la app lo escrito en el login. No devuelve ningún dato. */
    private class LoginBridge {
        @JavascriptInterface
        public void candidate(String user, String password) {
            if (user == null || password == null || user.isEmpty() || password.isEmpty()
                    || user.length() > 120 || password.length() > 256) {
                return;
            }

            candidateUser = user;
            candidatePassword = password;
        }
    }

    private class PanelClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (isPanel(uri)) {
                return false; // Se queda dentro de la app.
            }

            openInBrowser(uri);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            progress.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progress.setVisibility(View.GONE);
            CookieManager.getInstance().flush();

            // Tras un error, Chrome carga su propia página de error: eso no cuenta como carga correcta.
            if (loadFailed && url != null && failedUrl != null
                    && !url.equals(failedUrl) && !url.startsWith("data:")) {
                loadFailed = false;
            }

            if (!loadFailed) {
                hideOffline();

                Uri uri = url == null ? null : Uri.parse(url);
                if (isPanel(uri)) {
                    if (isLoginPage(uri)) {
                        onLoginPage();
                    } else {
                        quickBar.setVisibility(View.GONE);
                        onLoggedInPage();
                    }
                }
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                failedUrl = request.getUrl().toString();
                showOffline(getString(R.string.offline_text));
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame() && response.getStatusCode() >= 500) {
                failedUrl = request.getUrl().toString();
                showOffline(getString(R.string.server_error_text));
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel(); // Nunca se acepta un certificado inválido.
            failedUrl = error.getUrl();
            showOffline(getString(R.string.ssl_error_text));
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // Si el motor de la página se cierra (poca memoria), se reinicia la pantalla en vez de cerrar la app.
            recreate();
            return true;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Actualizaciones desde GitHub                                        */
    /* ------------------------------------------------------------------ */

    private void showUpdateDialog(final String version, final String downloadUrl) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.update_title)
                .setMessage(getString(R.string.update_message, version))
                .setPositiveButton(R.string.update_now, (dialog, which) -> openInBrowser(Uri.parse(downloadUrl)))
                .setNegativeButton(R.string.update_later, (dialog, which) -> UpdateChecker.postpone(this, version))
                .setOnCancelListener(dialog -> UpdateChecker.postpone(this, version))
                .show();
    }

    /* ------------------------------------------------------------------ */
    /* Entrada rápida con huella                                           */
    /* ------------------------------------------------------------------ */

    private void onLoginPage() {
        web.evaluateJavascript(HOOK_JS, null);

        // Lo que se capturó antes pertenecía a un intento que no entró.
        candidateUser = null;
        candidatePassword = null;

        boolean saved = secure.hasSaved();
        quickBar.setVisibility(saved ? View.VISIBLE : View.GONE);
        if (!saved) {
            return;
        }

        web.evaluateJavascript(FLASH_JS, value -> {
            String flash = value == null ? "" : value.toLowerCase(Locale.ROOT);

            // Si volvió el login justo después de usar el acceso guardado, no entró: no se insiste
            // (cada intento fallido cuenta para el bloqueo del panel).
            if (System.currentTimeMillis() - lastAutoSubmit < 60_000L) {
                lastAutoSubmit = 0;
                freshLaunch = false;
                toast(R.string.quick_failed);
                return;
            }

            boolean sessionExpired = flash.contains("venci");
            boolean locked = flash.contains("demasiados");
            boolean automatic = (freshLaunch || sessionExpired) && !locked;
            freshLaunch = false;

            if (automatic) {
                loginWithSaved();
            }
        });
    }

    private void onLoggedInPage() {
        lastAutoSubmit = 0;

        final String user = candidateUser;
        final String password = candidatePassword;
        candidateUser = null;
        candidatePassword = null;

        if (user == null || password == null || isFinishing()) {
            return;
        }

        if (secure.hasSaved() || secure.askDisabled() || !SecureLogin.deviceSecure(this)) {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.quick_title)
                .setMessage(R.string.quick_message)
                .setPositiveButton(R.string.quick_enable, (dialog, which) -> saveWithFingerprint(user, password))
                .setNegativeButton(R.string.quick_not_now, null)
                .setNeutralButton(R.string.quick_never, (dialog, which) -> secure.disableAsking())
                .show();
    }

    private void saveWithFingerprint(final String user, final String password) {
        if (!SecureLogin.deviceSecure(this)) {
            toast(R.string.quick_need_lock);
            return;
        }

        try {
            Cipher cipher = secure.newEncryptCipher();
            authenticate(cipher, getString(R.string.quick_prompt_save), authed -> {
                try {
                    secure.save(authed, user, password);
                    toast(R.string.quick_saved);
                } catch (Exception e) {
                    secure.clear();
                    toast(R.string.quick_save_error);
                }
            }, null);
        } catch (Exception e) {
            toast(R.string.quick_save_error);
        }
    }

    private void loginWithSaved() {
        if (promptBusy || !secure.hasSaved()) {
            return;
        }

        try {
            Cipher cipher = secure.newDecryptCipher();
            promptBusy = true;
            authenticate(cipher, getString(R.string.quick_prompt_enter), authed -> {
                promptBusy = false;
                try {
                    String[] credentials = secure.load(authed);
                    submitLogin(credentials[0], credentials[1]);
                } catch (Exception e) {
                    toast(R.string.quick_failed);
                }
            }, () -> promptBusy = false);
        } catch (KeyPermanentlyInvalidatedException e) {
            // Se agregó otra huella al celular: por seguridad la clave deja de servir.
            secure.clear();
            quickBar.setVisibility(View.GONE);
            toast(R.string.quick_invalidated);
        } catch (Exception e) {
            toast(R.string.quick_failed);
        }
    }

    private void submitLogin(String user, String password) {
        lastAutoSubmit = System.currentTimeMillis();

        String script = "(function(u,p){var a=document.getElementById('usuario'),b=document.getElementById('password');"
                + "if(!a||!b){return 'no';}a.value=u;b.value=p;var f=a.form;"
                + "if(f){if(f.requestSubmit){f.requestSubmit();}else{f.submit();}}return 'ok';})("
                + JSONObject.quote(user) + "," + JSONObject.quote(password) + ")";

        web.evaluateJavascript(script, null);
    }

    private void confirmForget() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.quick_forget_title)
                .setMessage(R.string.quick_forget_message)
                .setPositiveButton(R.string.quick_forget_yes, (dialog, which) -> {
                    secure.clear();
                    quickBar.setVisibility(View.GONE);
                    toast(R.string.quick_forgotten);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Pide la huella (o el PIN/patrón en Android 11 o más) y entrega el cifrador ya autorizado. */
    private void authenticate(Cipher cipher, String subtitle, final CipherAction onSuccess, final Runnable onCancel) {
        BiometricPrompt.Builder builder = new BiometricPrompt.Builder(this)
                .setTitle(getString(R.string.quick_prompt_title))
                .setSubtitle(subtitle);

        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else {
            builder.setNegativeButton(getString(R.string.quick_use_password), getMainExecutor(), (dialog, which) -> {
                if (onCancel != null) {
                    onCancel.run();
                }
            });
        }

        builder.build().authenticate(
                new BiometricPrompt.CryptoObject(cipher),
                new CancellationSignal(),
                getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        Cipher authed = result.getCryptoObject() == null ? null : result.getCryptoObject().getCipher();
                        if (authed != null) {
                            onSuccess.run(authed);
                        } else if (onCancel != null) {
                            onCancel.run();
                        }
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        if (onCancel != null) {
                            onCancel.run();
                        }
                    }
                });
    }

    /* ------------------------------------------------------------------ */
    /* Botón Atrás y ciclo de vida                                         */
    /* ------------------------------------------------------------------ */

    private void handleBack() {
        String url = web.getUrl();
        boolean onMap = url != null && url.contains("page=seguimiento_gps");

        if (!onMap && web.canGoBack()) {
            web.goBack();
        } else {
            finish();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBack();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        ViewGroup parent = (ViewGroup) web.getParent();
        if (parent != null) {
            parent.removeView(web);
        }
        web.destroy();
        super.onDestroy();
    }
}
