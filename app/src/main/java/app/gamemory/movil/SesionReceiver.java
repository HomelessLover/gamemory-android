package app.gamemory.movil;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * v1.1: los botones de la notificación de la sesión activa que no abren la
 * app («Pausa» / «Seguir»), quitarla deslizando, y volver a programar la
 * revisión periódica al reiniciar el móvil o actualizar la app.
 */
public class SesionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            Sesion.programar(c);
            return;
        }
        String id = i.getStringExtra("id");
        if (Sesion.BORRADA.equals(a)) {
            Sesion.prefs(c).edit().putString("sesion_quitada", id == null ? "" : id).apply();
            return;
        }
        if (!Sesion.PAUSAR.equals(a) && !Sesion.SEGUIR.equals(a)) return;
        final PendingResult pr = goAsync();
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject cuerpo = new JSONObject();
                cuerpo.put("id", id);
                JSONObject d = Sesion.pedir(app, "POST", Sesion.PAUSAR.equals(a) ? "/api/sesion-activa/pausar" : "/api/sesion-activa/reanudar", cuerpo);
                if (d == null) d = Sesion.pedir(app, "GET", "/api/sesion-activa", null);
                if (d != null) Sesion.mostrar(app, d);
                else aviso(app, "No se ha podido hablar con Gamemory. Ábrela y vuelve a probar.");
            } catch (Exception e) {
                aviso(app, "Sin conexión con Gamemory.");
            } finally {
                pr.finish();
            }
        }, "gm-sesion-boton").start();
    }

    private static void aviso(Context c, String t) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> Toast.makeText(c, t, Toast.LENGTH_LONG).show());
    }
}
