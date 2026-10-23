package cl.milalaapp.avisos;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Android le pasa a este servicio cada aviso que llega al teléfono (con el permiso «Acceso a notificaciones»).
 * Solo se toman los de las apps elegidas (ej: MACH y Billetera de Google); los demás se ignoran sin leer su texto.
 */
public class LectorAvisos extends NotificationListenerService {

    /** Si Android tiene conectado el lector ahora (si no, la pantalla le pide que lo reconecte). */
    static volatile boolean conectado;

    @Override
    public void onListenerConnected() {
        conectado = true;
        Envio.vaciarLuego(this);
    }

    @Override
    public void onListenerDisconnected() {
        conectado = false;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            tomar(this, sbn);
        } catch (RuntimeException e) {
            // Un aviso raro nunca debe apagar el lector.
        }
    }

    static void tomar(Context c, StatusBarNotification sbn) {
        if (sbn == null) return;
        String paquete = sbn.getPackageName();
        if (paquete == null || paquete.equals(c.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null) return;
        // El resumen de un grupo repite avisos que ya llegaron uno por uno; los avisos fijos no son compras.
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
        Ajustes.anotarVista(c, paquete);
        if (!Ajustes.apps(c).contains(paquete)) return;
        if (Ajustes.token(c) == null) return;

        Bundle x = n.extras;
        if (x == null) return;
        String titulo = texto(x.getCharSequence(Notification.EXTRA_TITLE));
        String cuerpo = texto(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (cuerpo.isEmpty()) cuerpo = texto(x.getCharSequence(Notification.EXTRA_TEXT));
        if (cuerpo.isEmpty()) {
            CharSequence[] lineas = x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lineas != null) {
                StringBuilder sb = new StringBuilder();
                for (CharSequence l : lineas) if (l != null) sb.append(l).append('\n');
                cuerpo = sb.toString().trim();
            }
        }
        if (titulo.isEmpty() && cuerpo.isEmpty()) return;

        // La hora del aviso: la que puso la app (no cambia si la app actualiza el mismo aviso).
        long ahora = System.currentTimeMillis();
        long cuando = n.when > 0 && Math.abs(ahora - n.when) < 86_400_000L ? n.when : sbn.getPostTime();
        String firma = paquete + "|" + sbn.getKey() + "|" + cuando + "|" + titulo + "|" + cuerpo;
        if (Ajustes.yaTomado(c, firma)) return;

        Envio.encolarYEnviar(c, paquete, nombreApp(c, paquete), recortar(titulo, 300), recortar(cuerpo, 3000), cuando);
    }

    static String nombreApp(Context c, String paquete) {
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(paquete, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            if (l != null && l.length() > 0) return l.toString();
        } catch (PackageManager.NameNotFoundException e) {
            // sin nombre: se usa el paquete
        }
        return paquete;
    }

    private static String texto(CharSequence s) {
        return s == null ? "" : s.toString().trim();
    }

    private static String recortar(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
