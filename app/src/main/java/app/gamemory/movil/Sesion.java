package app.gamemory.movil;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.webkit.CookieManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * v1.1: la sesión activa en una notificación fija, como Bookmory.
 *
 * Plegada: carátula, juego y cronómetro (el del sistema: setUsesChronometer).
 * Desplegada: «Pausa» / «Seguir» (solo sesiones manuales: las de Playnite se
 * cierran solas), «Hito» y «Terminar» (abren la app en /sesion-activa con
 * ?accion=hito o ?accion=terminar). Tocarla abre /sesion-activa.
 *
 * El móvil se entera al abrir la app o volver a ella, cuando cambia algo en la
 * página de la sesión (window.GamemoryApp.sesionCambiada) y cada ~15 min
 * (SesionJob). GET /api/sesion-activa del servidor, con las cookies de la web
 * (Cloudflare Access).
 */
final class Sesion {

    static final String CANAL = "sesion";
    static final int ID = 4901;
    static final String PAUSAR = "app.gamemory.movil.PAUSAR";
    static final String SEGUIR = "app.gamemory.movil.SEGUIR";
    static final String BORRADA = "app.gamemory.movil.BORRADA";
    static final String EXTRA_URL = "gm_url";
    private static final int JOB = 4902;
    private static final int ACCENT = Color.rgb(95, 206, 221);
    private static long ultimaVez = 0;

    private Sesion() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("gamemory", Context.MODE_PRIVATE);
    }

    static String servidor(Context c) {
        return prefs(c).getString("servidor", "");
    }

    static boolean activada(Context c) {
        return prefs(c).getBoolean("notif_sesion", true);
    }

    // ------------------------------------------------------------ preguntar al servidor
    /** En segundo plano. `siempre`: aunque se haya preguntado hace nada. */
    static void refrescar(Context c, boolean siempre) {
        long ahora = System.currentTimeMillis();
        if (!siempre && ahora - ultimaVez < 2500) return;
        ultimaVez = ahora;
        Context app = c.getApplicationContext();
        new Thread(() -> refrescarYa(app), "gm-sesion").start();
    }

    /** En el hilo que llama (el del JobService o el del receptor). */
    static void refrescarYa(Context c) {
        if (!activada(c) || servidor(c).isEmpty()) {
            quitar(c);
            return;
        }
        try {
            JSONObject d = pedir(c, "GET", "/api/sesion-activa", null);
            if (d != null) mostrar(c, d);
        } catch (Exception e) {
            // sin red o sin sesión de Cloudflare: se deja la que haya
        }
    }

    static JSONObject pedir(Context c, String metodo, String ruta, JSONObject cuerpo) throws Exception {
        String srv = servidor(c);
        HttpURLConnection con = (HttpURLConnection) new URL(srv + ruta).openConnection();
        try {
            con.setRequestMethod(metodo);
            con.setConnectTimeout(12000);
            con.setReadTimeout(15000);
            con.setInstanceFollowRedirects(false);  // una redirección = Cloudflare pide iniciar sesión
            con.setRequestProperty("Accept", "application/json");
            String ck = CookieManager.getInstance().getCookie(srv);
            if (ck != null) con.setRequestProperty("Cookie", ck);
            if (cuerpo != null) {
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = con.getOutputStream()) {
                    o.write(cuerpo.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = con.getResponseCode();
            if (code != 200) return null;
            String ct = con.getContentType();
            if (ct == null || !ct.contains("json")) return null;
            return new JSONObject(Fotos.texto(con.getInputStream()));
        } finally {
            con.disconnect();
        }
    }

    // ------------------------------------------------------------ la notificación
    static void canal(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CANAL) != null) return;
        NotificationChannel ch = new NotificationChannel(CANAL, "Sesión activa", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("El juego al que estás jugando, con su cronómetro, Pausa, Hito y Terminar.");
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    static boolean puede(Context c) {
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.areNotificationsEnabled();
    }

    static void quitar(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(ID);
        prefs(c).edit().remove("sesion_clave").apply();
    }

    static void mostrar(Context c, JSONObject d) {
        if (!d.optBoolean("activa", false)) {
            quitar(c);
            return;
        }
        if (!puede(c)) return;
        SharedPreferences p = prefs(c);
        String id = d.optString("id");
        boolean pausada = d.optBoolean("pausada", false);
        // Si la quitaste deslizando, no vuelve hasta la sesión siguiente.
        if (id.equals(p.getString("sesion_quitada", ""))) return;
        canal(c);
        String juego = d.optString("juego", "Juego");
        long seg = d.optLong("segundos", 0);
        long ahora = System.currentTimeMillis();
        String partida = d.optString("partida", "");
        String texto;
        if (pausada) {
            texto = "En pausa · llevas " + duracion(seg);
        } else {
            texto = "Jugando" + (d.optBoolean("manual", false) ? "" : " · Playnite") + desde(d.optString("inicio", ""));
        }
        Notification.Builder b = new Notification.Builder(c, CANAL)
                .setSmallIcon(R.drawable.ic_sesion)
                .setContentTitle(juego)
                .setContentText(texto)
                .setSubText(partida.isEmpty() ? null : partida)
                .setColor(ACCENT)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(!pausada)
                .setUsesChronometer(!pausada)
                .setWhen(ahora - seg * 1000L)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(abrir(c, "/sesion-activa", 1))
                .setDeleteIntent(difusion(c, BORRADA, id, 2));
        Bitmap cover = portada(c, d.optString("portada", ""));
        if (cover != null) b.setLargeIcon(cover);
        if (d.optBoolean("manual", false)) {
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(c, pausada ? R.drawable.ic_seguir : R.drawable.ic_pausa),
                    pausada ? "Seguir" : "Pausa", difusion(c, pausada ? SEGUIR : PAUSAR, id, 3)).build());
        }
        b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_hito), "Hito",
                abrir(c, "/sesion-activa?accion=hito", 4)).build());
        b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_terminar), "Terminar",
                abrir(c, "/sesion-activa?accion=terminar", 5)).build());
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        try {
            nm.notify(ID, b.build());
            p.edit().putString("sesion_clave", id + "|" + pausada).apply();
        } catch (SecurityException e) {
            // sin permiso de notificaciones
        }
    }

    private static PendingIntent abrir(Context c, String ruta, int req) {
        Intent i = new Intent(c, MainActivity.class);
        i.setAction(Intent.ACTION_VIEW);
        i.putExtra(EXTRA_URL, ruta);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent difusion(Context c, String accion, String id, int req) {
        Intent i = new Intent(c, SesionReceiver.class);
        i.setAction(accion);
        i.putExtra("id", id);
        return PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static String duracion(long seg) {
        long h = seg / 3600, m = (seg % 3600) / 60;
        return h > 0 ? h + " h " + String.format(Locale.ROOT, "%02d", m) + " min" : m + " min";
    }

    private static String desde(String iso) {
        try {
            return " · desde las " + java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------ la carátula (con caché)
    private static Bitmap portada(Context c, String ruta) {
        if (ruta == null || ruta.isEmpty()) return null;
        String url = ruta.startsWith("http") ? ruta : servidor(c) + (ruta.startsWith("/") ? "" : "/") + ruta;
        File f = new File(c.getCacheDir(), "portada-" + Integer.toHexString(url.hashCode()) + ".png");
        if (f.exists()) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) return b;
        }
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            try {
                con.setConnectTimeout(12000);
                con.setReadTimeout(20000);
                String ck = CookieManager.getInstance().getCookie(Uri.parse(url).getScheme() + "://" + Uri.parse(url).getHost());
                if (ck != null) con.setRequestProperty("Cookie", ck);
                if (con.getResponseCode() != 200) return null;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream in = con.getInputStream()) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                }
                byte[] bytes = bo.toByteArray();
                Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (b == null) return null;
                b = recortar(b);
                try (FileOutputStream o = new FileOutputStream(f)) {
                    b.compress(Bitmap.CompressFormat.PNG, 100, o);
                }
                return b;
            } finally {
                con.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Cuadrada y pequeña: Android la recorta en círculo o cuadrado según el móvil. */
    private static Bitmap recortar(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), lado = Math.min(w, h);
        Bitmap cuadro = Bitmap.createBitmap(b, (w - lado) / 2, Math.max(0, (h - lado) / 4), lado, lado);
        return Bitmap.createScaledBitmap(cuadro, 256, 256, true);
    }

    // ------------------------------------------------------------ cada ~15 min
    static void programar(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (!activada(c)) {
            js.cancel(JOB);
            return;
        }
        if (js.getPendingJob(JOB) != null) return;
        JobInfo j = new JobInfo.Builder(JOB, new ComponentName(c, SesionJob.class))
                .setPeriodic(15 * 60_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        try {
            js.schedule(j);
        } catch (Exception e) {
            // algunos móviles limitan los trabajos periódicos
        }
    }
}
