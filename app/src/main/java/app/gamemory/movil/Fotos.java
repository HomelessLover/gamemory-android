package app.gamemory.movil;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Las fotos del móvil (v1.0).
 *
 * - Carpetas vigiladas: las eliges con el selector de Android (acceso
 *   permanente de solo lectura). Al abrir la app se mira lo nuevo de cada una
 *   (fecha del archivo posterior a la última subida y a «Contar desde») y se
 *   sube a Gamemory (POST /fotos/subir, con sesion=1: las de la sesión en
 *   marcha se colocan ya; las demás van a «Fotos por colocar»).
 * - Compartir → Gamemory: lo mismo con las fotos que llegan.
 * - Con cada foto va la hora en que se hizo (EXIF) por si al reducirla se
 *   pierde; las grandes se reducen a 1920 px antes de subir.
 *
 * Nada corre en segundo plano: solo al abrir la app o al pedirlo.
 */
final class Fotos {

    interface Aviso {
        void estado(JSONObject d);
    }

    static final class Doc {
        Uri uri;
        String nombre;
        String mime;
        long mod;
        long tam;
        String carpeta;
    }

    private static final int TANDA = 5;
    private static final int MAX_POR_VEZ = 200;
    private static final int LADO = 1920;

    private final Context ctx;
    private final SharedPreferences p;
    private final AtomicBoolean ocupado = new AtomicBoolean(false);

    Fotos(Context c) {
        ctx = c.getApplicationContext();
        p = ctx.getSharedPreferences("gamemory", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ ajustes
    String servidor() {
        return p.getString("servidor", "");
    }

    JSONArray carpetas() {
        try {
            return new JSONArray(p.getString("carpetas", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private void guardarCarpetas(JSONArray a) {
        p.edit().putString("carpetas", a.toString()).apply();
    }

    static String ruta(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        int i = id.indexOf(':');
        return i >= 0 ? id.substring(i + 1) : id;
    }

    static String nombre(Uri tree) {
        String r = ruta(tree);
        if (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        int j = r.lastIndexOf('/');
        String n = j >= 0 ? r.substring(j + 1) : r;
        return n.isEmpty() ? "Almacenamiento" : n;
    }

    void anadirCarpeta(Uri tree) {
        try {
            ctx.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            // sin permiso permanente: se intentará igual mientras dure
        }
        JSONArray a = carpetas();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && tree.toString().equals(o.optString("uri"))) return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("uri", tree.toString());
            o.put("nombre", nombre(tree));
            o.put("ruta", ruta(tree));
            a.put(o);
        } catch (JSONException e) {
            return;
        }
        guardarCarpetas(a);
        if (p.getLong("desde", 0) == 0) p.edit().putLong("desde", System.currentTimeMillis()).apply();
    }

    void quitarCarpeta(String uri) {
        JSONArray a = carpetas(), b = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !uri.equals(o.optString("uri"))) b.put(o);
        }
        guardarCarpetas(b);
        p.edit().remove("marca:" + uri).apply();
        try {
            ctx.getContentResolver().releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            // ya no estaba
        }
    }

    // ------------------------------------------------------------ listar
    List<Doc> listar(Uri tree) {
        List<Doc> out = new ArrayList<>();
        try {
            listar(tree, DocumentsContract.getTreeDocumentId(tree), 0, out);
        } catch (Exception e) {
            // carpeta que ya no existe o sin permiso
        }
        return out;
    }

    private void listar(Uri tree, String docId, int nivel, List<Doc> out) {
        Uri hijos = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
        };
        try (Cursor c = ctx.getContentResolver().query(hijos, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String nom = c.isNull(1) ? "" : c.getString(1);
                String mime = c.isNull(2) ? "" : c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (nivel < 2 && !nom.startsWith(".")) listar(tree, id, nivel + 1, out);
                } else if (mime.startsWith("image/")) {
                    Doc d = new Doc();
                    d.uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    d.nombre = nom;
                    d.mime = mime;
                    d.mod = c.isNull(3) ? 0 : c.getLong(3);
                    d.tam = c.isNull(4) ? 0 : c.getLong(4);
                    out.add(d);
                }
            }
        }
    }

    // ------------------------------------------------------------ revisar las carpetas
    boolean ocupado() {
        return ocupado.get();
    }

    void revisar(boolean aMano, Aviso aviso) {
        if (!ocupado.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                revisarYa(aMano, aviso);
            } catch (Exception e) {
                aviso.estado(fase("error", "No se han podido revisar las carpetas: " + e.getMessage()));
            } finally {
                ocupado.set(false);
            }
        }, "gm-revisar").start();
    }

    private void revisarYa(boolean aMano, Aviso aviso) {
        JSONArray cs = carpetas();
        if (cs.length() == 0) {
            if (aMano) aviso.estado(fase("sin_carpetas", ""));
            return;
        }
        if (servidor().isEmpty()) return;
        if (aMano) aviso.estado(fase("buscando", ""));
        long desde = p.getLong("desde", 0);
        List<Doc> nuevas = new ArrayList<>();
        String primera = "";
        for (int i = 0; i < cs.length(); i++) {
            JSONObject c = cs.optJSONObject(i);
            if (c == null) continue;
            String uri = c.optString("uri");
            long marca = Math.max(desde, p.getLong("marca:" + uri, 0));
            int antes = nuevas.size();
            for (Doc d : listar(Uri.parse(uri))) {
                if (d.mod > marca) {
                    d.carpeta = uri;
                    nuevas.add(d);
                }
            }
            if (primera.isEmpty() && nuevas.size() > antes) primera = c.optString("nombre");
        }
        p.edit().putLong("ultima", System.currentTimeMillis()).apply();
        if (nuevas.isEmpty()) {
            p.edit().putInt("ultima_n", 0).apply();
            if (aMano) aviso.estado(fase("nada", ""));
            return;
        }
        Collections.sort(nuevas, (a, b) -> Long.compare(a.mod, b.mod));
        if (nuevas.size() > MAX_POR_VEZ) nuevas = new ArrayList<>(nuevas.subList(0, MAX_POR_VEZ));
        int total = nuevas.size();
        int[] suma = new int[4];  // nuevas, colocadas, por_colocar, repetidas
        aviso.estado(progreso(0, total, primera));
        for (int i = 0; i < total; i += TANDA) {
            List<Doc> tanda = nuevas.subList(i, Math.min(total, i + TANDA));
            JSONObject r = subirDocs(tanda);
            if (r == null) {
                aviso.estado(fase("error", "Gamemory no responde: se probará la próxima vez."));
                p.edit().putInt("ultima_n", suma[0] + suma[3]).apply();
                return;
            }
            sumar(suma, r);
            for (Doc d : tanda) {
                String k = "marca:" + d.carpeta;
                if (d.mod > p.getLong(k, 0)) p.edit().putLong(k, d.mod).apply();
            }
            aviso.estado(progreso(Math.min(total, i + TANDA), total, primera));
        }
        p.edit().putInt("ultima_n", suma[0] + suma[3]).apply();
        aviso.estado(hecho(suma, primera));
    }

    // ------------------------------------------------------------ compartir
    void compartir(List<Uri> uris, Aviso aviso, Runnable alTerminar) {
        new Thread(() -> {
            int[] suma = new int[4];
            try {
                aviso.estado(progreso(0, uris.size(), ""));
                for (int i = 0; i < uris.size(); i += TANDA) {
                    List<Doc> tanda = new ArrayList<>();
                    for (Uri u : uris.subList(i, Math.min(uris.size(), i + TANDA))) {
                        Doc d = new Doc();
                        d.uri = u;
                        d.nombre = nombreDe(u);
                        d.mime = ctx.getContentResolver().getType(u);
                        d.mod = 0;
                        tanda.add(d);
                    }
                    JSONObject r = subirDocs(tanda);
                    if (r == null) {
                        aviso.estado(fase("error", "Gamemory no responde. Vuelve a compartirlas en un rato."));
                        return;
                    }
                    sumar(suma, r);
                    aviso.estado(progreso(Math.min(uris.size(), i + TANDA), uris.size(), ""));
                }
                aviso.estado(hecho(suma, ""));
            } finally {
                p.edit().putInt("compartidas_por_colocar", suma[2]).apply();
                if (alTerminar != null) alTerminar.run();
            }
        }, "gm-compartir").start();
    }

    private String nombreDe(Uri u) {
        try (Cursor c = ctx.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception e) {
            // sin nombre
        }
        String s = u.getLastPathSegment();
        return s == null ? "foto.jpg" : s;
    }

    // ------------------------------------------------------------ subir
    private JSONObject subirDocs(List<Doc> docs) {
        List<byte[]> datos = new ArrayList<>();
        List<String> nombres = new ArrayList<>(), mimes = new ArrayList<>(), fechas = new ArrayList<>();
        for (Doc d : docs) {
            byte[] b;
            try {
                b = leer(d.uri);
            } catch (Exception e) {
                continue;  // no se puede leer: se salta
            }
            String fecha = fechaDe(b, d.mod);
            String mime = d.mime == null ? "image/jpeg" : d.mime;
            byte[] r = reducir(b, mime);
            String nombre = d.nombre == null ? "foto.jpg" : d.nombre;
            if (r != b) {
                mime = "image/jpeg";
                nombre = nombre.replaceAll("\\.[A-Za-z0-9]+$", "") + ".jpg";
            }
            datos.add(r);
            nombres.add(nombre);
            mimes.add(mime);
            fechas.add(fecha);
        }
        if (datos.isEmpty()) {
            try {
                return new JSONObject().put("nuevas", 0);
            } catch (JSONException e) {
                return new JSONObject();
            }
        }
        return enviar(datos, nombres, mimes, fechas);
    }

    private byte[] leer(Uri u) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        try (InputStream in = cr.openInputStream(u)) {
            if (in == null) throw new IOException("sin datos");
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    /** La hora de la foto («2026:11:15 13:24:46», hora local) o la del archivo (ms). */
    private static String fechaDe(byte[] b, long mod) {
        try {
            ExifInterface ei = new ExifInterface(new ByteArrayInputStream(b));
            String t = ei.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (t == null || t.trim().isEmpty()) t = ei.getAttribute(ExifInterface.TAG_DATETIME);
            if (t != null && !t.trim().isEmpty()) return t.trim();
        } catch (Exception e) {
            // sin EXIF
        }
        return mod > 0 ? String.valueOf(mod) : "";
    }

    /** Las grandes (más de 2,5 MB) se reducen a 1920 px en JPEG; las demás, tal cual. */
    private static byte[] reducir(byte[] b, String mime) {
        if (b.length <= 2_500_000 || !(mime.contains("jpeg") || mime.contains("jpg") || mime.contains("png") || mime.contains("webp"))) return b;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(b, 0, b.length, o);
            int mayor = Math.max(o.outWidth, o.outHeight);
            if (mayor <= 0) return b;
            int s = 1;
            while (mayor / (s * 2) >= LADO) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length, o2);
            if (bm == null) return b;
            float k = Math.min(1f, (float) LADO / Math.max(bm.getWidth(), bm.getHeight()));
            Matrix m = new Matrix();
            m.postScale(k, k);
            int giro = 0;
            try {
                int ori = new ExifInterface(new ByteArrayInputStream(b)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (ori == ExifInterface.ORIENTATION_ROTATE_90) giro = 90;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_180) giro = 180;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_270) giro = 270;
            } catch (Exception e) {
                // sin orientación
            }
            if (giro != 0) m.postRotate(giro);
            Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            r.compress(Bitmap.CompressFormat.JPEG, 88, out);
            byte[] res = out.toByteArray();
            return res.length < b.length ? res : b;
        } catch (OutOfMemoryError | RuntimeException e) {
            return b;
        }
    }

    private JSONObject enviar(List<byte[]> datos, List<String> nombres, List<String> mimes, List<String> fechas) {
        String srv = servidor();
        if (srv.isEmpty()) return null;
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(srv + "/fotos/subir").openConnection();
            String limite = "----gamemory" + System.nanoTime();
            con.setDoOutput(true);
            con.setRequestMethod("POST");
            con.setConnectTimeout(20000);
            con.setReadTimeout(180000);
            con.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + limite);
            con.setRequestProperty("User-Agent", "GamemoryApp/" + BuildConfig.VERSION_NAME);
            try {
                String ck = CookieManager.getInstance().getCookie(srv);
                if (ck != null) con.setRequestProperty("Cookie", ck);
            } catch (Exception e) {
                // sin cookies
            }
            try (OutputStream o = new BufferedOutputStream(con.getOutputStream())) {
                for (int i = 0; i < datos.size(); i++) {
                    String nom = nombres.get(i).replace("\"", "");
                    escribir(o, "--" + limite + "\r\nContent-Disposition: form-data; name=\"archivos\"; filename=\"" + nom + "\"\r\nContent-Type: " + mimes.get(i) + "\r\n\r\n");
                    o.write(datos.get(i));
                    escribir(o, "\r\n");
                }
                for (String f : fechas) campo(o, limite, "fechas", f);
                campo(o, limite, "sesion", "1");
                escribir(o, "--" + limite + "--\r\n");
            }
            int code = con.getResponseCode();
            InputStream in = code < 400 ? con.getInputStream() : con.getErrorStream();
            String txt = in == null ? "" : texto(in);
            if (code >= 400) return null;
            return new JSONObject(txt);
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static void campo(OutputStream o, String limite, String nombre, String valor) throws IOException {
        escribir(o, "--" + limite + "\r\nContent-Disposition: form-data; name=\"" + nombre + "\"\r\n\r\n" + valor + "\r\n");
    }

    private static void escribir(OutputStream o, String s) throws IOException {
        o.write(s.getBytes(StandardCharsets.UTF_8));
    }

    static String texto(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toString("UTF-8");
    }

    // ------------------------------------------------------------ avisos a la web (gmApp.estado)
    private static void sumar(int[] s, JSONObject r) {
        s[0] += r.optInt("nuevas");
        s[1] += r.optInt("colocadas");
        s[2] += r.optInt("por_colocar");
        s[3] += r.optInt("repetidas");
    }

    static JSONObject fase(String f, String texto) {
        JSONObject o = new JSONObject();
        try {
            o.put("fase", f);
            o.put("texto", texto);
        } catch (JSONException e) {
            // nada
        }
        return o;
    }

    private static JSONObject progreso(int n, int total, String carpeta) {
        JSONObject o = fase("subiendo", "");
        try {
            o.put("n", n);
            o.put("total", total);
            o.put("carpeta", carpeta);
        } catch (JSONException e) {
            // nada
        }
        return o;
    }

    private static JSONObject hecho(int[] s, String carpeta) {
        JSONObject o = fase("hecho", "");
        try {
            o.put("nuevas", s[0]);
            o.put("colocadas", s[1]);
            o.put("por_colocar", s[2]);
            o.put("repetidas", s[3]);
            o.put("carpeta", carpeta);
        } catch (JSONException e) {
            // nada
        }
        return o;
    }
}
