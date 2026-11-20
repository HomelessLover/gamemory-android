package app.gamemory.movil;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Gamemory para Android (v1.0).
 *
 * La app enseña la web de Gamemory de tu servidor (así todo lo que cambia con
 * deploy.sh llega solo) y añade lo que la web no puede hacer desde Native
 * Alpha: elegir varias fotos a la vez, «Compartir → Gamemory», mirar al abrir
 * las carpetas de fotos que elijas (Fotos.java) y actualizarse desde GitHub.
 *
 * La web habla con la app por window.GamemoryApp (Puente, abajo) y la app le
 * cuenta cómo va la subida con window.gmApp.estado({...}) (static/js/app_movil.js).
 */
public class MainActivity extends Activity {

    private static final int REQ_ARCHIVOS = 1;
    private static final int REQ_CARPETA = 2;
    private static final String PREDETERMINADO = "https://gamemory.elhoyo.net";
    private static final long CADA_REVISION = 30_000L;
    private static final long CADA_ACTUALIZACION = 6 * 3600_000L;
    private static final int FONDO = Color.rgb(15, 19, 24);

    private WebView web;
    private Fotos fotos;
    private SharedPreferences p;
    private ValueCallback<Uri[]> archivosCb;
    private boolean cargada = false;
    private String pendienteJs = null;
    private List<Uri> pendienteCompartir = null;
    private long descargaApk = -1;
    private BroadcastReceiver alDescargar = null;

    @Override
    protected void onCreate(Bundle guardado) {
        super.onCreate(guardado);
        fotos = new Fotos(this);
        p = getSharedPreferences("gamemory", MODE_PRIVATE);
        getWindow().setStatusBarColor(FONDO);
        getWindow().setNavigationBarColor(FONDO);
        if (fotos.servidor().isEmpty()) pedirServidor(PREDETERMINADO);
        else abrirWeb();
        manejar(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        manejar(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web == null) return;
        if (p.getBoolean("revisar_al_abrir", true) && System.currentTimeMillis() - p.getLong("ultima", 0) > CADA_REVISION) {
            fotos.revisar(false, this::aviso);
        }
        buscarActualizacion(false);
    }

    @Override
    protected void onDestroy() {
        if (alDescargar != null) {
            try {
                unregisterReceiver(alDescargar);
            } catch (Exception e) {
                // ya estaba quitado
            }
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    // ------------------------------------------------------------ primera vez: la dirección
    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void pedirServidor(String valor) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setBackgroundColor(FONDO);
        l.setPadding(dp(28), dp(28), dp(28), dp(28));
        TextView t = new TextView(this);
        t.setText("Gamemory");
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        l.addView(t);
        TextView d = new TextView(this);
        d.setText("La dirección de tu Gamemory (la misma que abres en el navegador).");
        d.setTextColor(Color.rgb(170, 180, 192));
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        d.setPadding(0, dp(8), 0, dp(18));
        l.addView(d);
        EditText e = new EditText(this);
        e.setText(valor);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setTextColor(Color.WHITE);
        l.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button b = new Button(this);
        b.setText("Entrar");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        l.addView(b, lp);
        b.setOnClickListener(v -> {
            String u = e.getText().toString().trim();
            while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            if (u.isEmpty()) return;
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
            p.edit().putString("servidor", u).apply();
            abrirWeb();
            if (pendienteCompartir != null) {
                List<Uri> l2 = pendienteCompartir;
                pendienteCompartir = null;
                compartir(l2);
            }
        });
        if (web != null) {
            web.destroy();
            web = null;
        }
        setContentView(l);
    }

    // ------------------------------------------------------------ la web
    private void abrirWeb() {
        web = new WebView(this);
        web.setBackgroundColor(FONDO);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(s.getUserAgentString() + " GamemoryApp/" + BuildConfig.VERSION_NAME);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new Puente(), "GamemoryApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String esq = u.getScheme() == null ? "" : u.getScheme();
                Uri srv = Uri.parse(fotos.servidor());
                if ((esq.equals("http") || esq.equals("https")) && u.getHost() != null && u.getHost().equals(srv.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "No hay ninguna app para abrir esto", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap icono) {
                cargada = false;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                cargada = true;
                if (pendienteJs != null) {
                    String j = pendienteJs;
                    pendienteJs = null;
                    v.evaluateJavascript(j, null);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                return elegirArchivos(cb, params);
            }
        });
        web.setDownloadListener((url, ua, disposicion, mime, largo) -> descargar(url, disposicion, mime));
        setContentView(web);
        web.loadUrl(fotos.servidor() + "/");
    }

    private void js(String codigo) {
        runOnUiThread(() -> {
            if (web == null) return;
            if (!cargada) {
                pendienteJs = codigo;
                return;
            }
            web.evaluateJavascript(codigo, null);
        });
    }

    private void aviso(JSONObject d) {
        js("window.gmApp && window.gmApp.estado(" + d.toString() + ")");
    }

    // ------------------------------------------------------------ elegir archivos (varios a la vez)
    private boolean elegirArchivos(ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams params) {
        if (archivosCb != null) archivosCb.onReceiveValue(null);
        archivosCb = cb;
        boolean varios = params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;
        String[] tipos = params.getAcceptTypes();
        boolean imagenes = tipos != null && tipos.length > 0 && tipos[0] != null && tipos[0].startsWith("image");
        Intent it;
        if (imagenes && Build.VERSION.SDK_INT >= 33) {
            it = new Intent(MediaStore.ACTION_PICK_IMAGES);
            if (varios) it.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 100);  // el máximo habitual del selector
        } else {
            it = new Intent(Intent.ACTION_GET_CONTENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType(imagenes ? "image/*" : "*/*");
            if (varios) it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try {
            startActivityForResult(it, REQ_ARCHIVOS);
            return true;
        } catch (ActivityNotFoundException e) {
            archivosCb = null;
            return false;
        }
    }

    @Override
    protected void onActivityResult(int req, int code, Intent data) {
        super.onActivityResult(req, code, data);
        if (req == REQ_ARCHIVOS) {
            Uri[] res = null;
            if (code == RESULT_OK && data != null) {
                ClipData cd = data.getClipData();
                if (cd != null && cd.getItemCount() > 0) {
                    res = new Uri[cd.getItemCount()];
                    for (int i = 0; i < cd.getItemCount(); i++) res[i] = cd.getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    res = new Uri[]{data.getData()};
                }
            }
            if (archivosCb != null) archivosCb.onReceiveValue(res);
            archivosCb = null;
        } else if (req == REQ_CARPETA) {
            if (code == RESULT_OK && data != null && data.getData() != null) {
                fotos.anadirCarpeta(data.getData());
                js("window.gmApp && window.gmApp.cambio && window.gmApp.cambio()");
            }
        }
    }

    // ------------------------------------------------------------ compartir → Gamemory
    @SuppressWarnings("deprecation")
    private void manejar(Intent i) {
        if (i == null) return;
        String a = i.getAction();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(a)) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) {
            ArrayList<Uri> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        } else {
            return;
        }
        setIntent(new Intent(this, MainActivity.class));  // que no se repita al girar o volver
        if (uris.isEmpty()) return;
        if (web == null) {
            pendienteCompartir = uris;
            return;
        }
        compartir(uris);
    }

    private void compartir(List<Uri> uris) {
        Toast.makeText(this, uris.size() == 1 ? "Subiendo la foto a Gamemory…" : "Subiendo " + uris.size() + " fotos a Gamemory…", Toast.LENGTH_SHORT).show();
        fotos.compartir(uris, this::aviso, () -> runOnUiThread(() -> {
            int n = p.getInt("compartidas_por_colocar", 0);
            if (n > 0 && web != null) web.loadUrl(fotos.servidor() + "/fotos?nuevas=" + n);
        }));
    }

    // ------------------------------------------------------------ descargas
    private void descargar(String url, String disposicion, String mime) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            Toast.makeText(this, "No se puede descargar esto desde la app", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            String ck = CookieManager.getInstance().getCookie(url);
            if (ck != null) r.addRequestHeader("Cookie", ck);
            String nombre = URLUtil.guessFileName(url, disposicion, mime);
            r.setTitle(nombre);
            r.setMimeType(mime);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nombre);
            dm.enqueue(r);
            Toast.makeText(this, "Descargando " + nombre, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "No se ha podido descargar: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------ actualizarse desde GitHub
    private void buscarActualizacion(boolean aMano) {
        String repo = BuildConfig.REPO;
        if (repo == null || repo.isEmpty()) {
            if (aMano) Toast.makeText(this, "Esta app no sabe de dónde actualizarse (compilada sin GitHub).", Toast.LENGTH_LONG).show();
            return;
        }
        long ahora = System.currentTimeMillis();
        if (!aMano && ahora - p.getLong("actualizacion_t", 0) < CADA_ACTUALIZACION) return;
        p.edit().putLong("actualizacion_t", ahora).apply();
        new Thread(() -> {
            try {
                HttpURLConnection con = (HttpURLConnection) new URL("https://api.github.com/repos/" + repo + "/releases/latest").openConnection();
                con.setRequestProperty("Accept", "application/vnd.github+json");
                con.setRequestProperty("User-Agent", "GamemoryApp");
                con.setConnectTimeout(15000);
                con.setReadTimeout(20000);
                if (con.getResponseCode() != 200) throw new Exception("GitHub respondió " + con.getResponseCode());
                JSONObject r = new JSONObject(Fotos.texto(con.getInputStream()));
                String tag = r.optString("tag_name", "");
                int n = 0;
                try {
                    n = Integer.parseInt(tag.replaceAll("[^0-9]", ""));
                } catch (NumberFormatException e) {
                    // etiqueta rara
                }
                String apk = null;
                JSONArray as = r.optJSONArray("assets");
                for (int i = 0; as != null && i < as.length(); i++) {
                    JSONObject a = as.optJSONObject(i);
                    if (a != null && a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url");
                }
                final int nueva = n;
                final String url = apk;
                final String titulo = r.optString("name", "Gamemory para Android");
                final String notas = r.optString("body", "");
                runOnUiThread(() -> {
                    if (nueva > BuildConfig.VERSION_CODE && url != null) {
                        new AlertDialog.Builder(this)
                                .setTitle(titulo)
                                .setMessage("Hay una versión nueva de la app.\n\n" + notas)
                                .setPositiveButton("Actualizar", (dlg, w) -> descargarApk(url, nueva))
                                .setNegativeButton("Luego", null)
                                .show();
                    } else if (aMano) {
                        Toast.makeText(this, "Tienes la última versión (" + BuildConfig.VERSION_NAME + ")", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                if (aMano) runOnUiThread(() -> Toast.makeText(this, "No se ha podido mirar si hay versión nueva: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "gm-actualizar").start();
    }

    private void descargarApk(String url, int n) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Permite a Gamemory instalar apps y vuelve a pulsar «Actualizar».", Toast.LENGTH_LONG).show();
            p.edit().putLong("actualizacion_t", 0).apply();  // que vuelva a preguntar al volver
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
        r.setTitle("Gamemory para Android");
        r.setMimeType("application/vnd.android.package-archive");
        r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "gamemory-b" + n + ".apk");
        if (alDescargar == null) {
            alDescargar = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id != descargaApk) return;
                    Uri u = dm.getUriForDownloadedFile(id);
                    if (u == null) {
                        Toast.makeText(c, "No se ha podido descargar la actualización.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    Intent inst = new Intent(Intent.ACTION_VIEW);
                    inst.setDataAndType(u, "application/vnd.android.package-archive");
                    inst.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(inst);
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(c, "No se ha podido abrir el instalador.", Toast.LENGTH_LONG).show();
                    }
                }
            };
            IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(alDescargar, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(alDescargar, f);
        }
        descargaApk = dm.enqueue(r);
        Toast.makeText(this, "Descargando la versión nueva…", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ window.GamemoryApp
    public final class Puente {
        @JavascriptInterface
        public String info() {
            JSONObject o = new JSONObject();
            try {
                o.put("version", BuildConfig.VERSION_NAME);
                o.put("compilacion", BuildConfig.VERSION_CODE);
                o.put("repo", BuildConfig.REPO);
                o.put("servidor", fotos.servidor());
                o.put("carpetas", fotos.carpetas());
                o.put("revisar", p.getBoolean("revisar_al_abrir", true));
                o.put("desde", p.getLong("desde", 0));
                o.put("ultima", p.getLong("ultima", 0));
                o.put("ultima_n", p.getInt("ultima_n", 0));
                o.put("ocupado", fotos.ocupado());
            } catch (Exception e) {
                // nada
            }
            return o.toString();
        }

        @JavascriptInterface
        public void elegirCarpeta() {
            runOnUiThread(() -> {
                Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try {
                    startActivityForResult(it, REQ_CARPETA);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "Este móvil no deja elegir carpetas.", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void quitarCarpeta(String uri) {
            fotos.quitarCarpeta(uri);
        }

        @JavascriptInterface
        public void revisarAhora() {
            fotos.revisar(true, MainActivity.this::aviso);
        }

        @JavascriptInterface
        public void setRevisar(boolean v) {
            p.edit().putBoolean("revisar_al_abrir", v).apply();
        }

        @JavascriptInterface
        public void setDesde(String ms) {
            try {
                p.edit().putLong("desde", Long.parseLong(ms.trim())).apply();
            } catch (NumberFormatException e) {
                // fecha no válida
            }
        }

        @JavascriptInterface
        public void buscarActualizacion() {
            runOnUiThread(() -> MainActivity.this.buscarActualizacion(true));
        }

        @JavascriptInterface
        public void cambiarServidor() {
            runOnUiThread(() -> pedirServidor(fotos.servidor()));
        }
    }
}
