package cl.milalaapp.avisos;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Manda los avisos a MilalaApp, de a uno y en orden (así dos avisos del mismo pago no se cruzan). */
final class Envio {
    private Envio() {}

    private static final ExecutorService UNO = Executors.newSingleThreadExecutor();
    private static final Object CANDADO = new Object();

    /** Respuesta de MilalaApp. */
    static final class Resultado {
        final boolean ok;
        final int estado;
        final String resultado;
        final String mensaje;
        final JSONObject json;

        Resultado(boolean ok, int estado, String resultado, String mensaje, JSONObject json) {
            this.ok = ok;
            this.estado = estado;
            this.resultado = resultado;
            this.mensaje = mensaje;
            this.json = json;
        }

        /** Un error que no se arregla reintentando (aviso malo o dirección que ya no existe). */
        boolean definitivo() { return estado >= 400 && estado < 500 && estado != 408 && estado != 429; }
    }

    /** Guarda el aviso y lo manda apenas se pueda. */
    static void encolarYEnviar(Context ctx, String app, String nombre, String titulo, String texto, long cuando) {
        final Context c = ctx.getApplicationContext();
        try {
            JSONObject a = new JSONObject();
            a.put("app", app);
            a.put("appNombre", nombre);
            a.put("titulo", titulo);
            a.put("texto", texto);
            a.put("cuando", cuando);
            Ajustes.encolar(c, a);
        } catch (JSONException e) {
            return;
        }
        UNO.execute(new Runnable() {
            @Override public void run() {
                try {
                    vaciar(c, true);
                } catch (RuntimeException e) {
                    // Nunca botar la app por un envío: el aviso queda guardado para el próximo intento.
                }
            }
        });
    }

    /** Intenta mandar lo pendiente en segundo plano. */
    static void vaciarLuego(Context ctx) {
        final Context c = ctx.getApplicationContext();
        UNO.execute(new Runnable() {
            @Override public void run() {
                try {
                    vaciar(c, true);
                } catch (RuntimeException e) {
                    // Nunca botar la app por un envío: el aviso queda guardado para el próximo intento.
                }
            }
        });
    }

    /**
     * Manda todo lo pendiente. Devuelve true si quedó algo por mandar (sin internet).
     * «programar»: si falla, deja agendado un reintento (desde el propio reintento no hace falta).
     */
    static boolean vaciar(Context c, boolean programar) {
        synchronized (CANDADO) {
            while (true) {
                String token = Ajustes.token(c);
                if (token == null || Ajustes.desconectado(c)) return false;
                JSONObject a = Ajustes.primeroDeCola(c);
                if (a == null) return false;
                JSONObject cuerpo;
                try {
                    cuerpo = new JSONObject(a.toString());
                    cuerpo.put("token", token);
                    cuerpo.put("version", BuildConfig.VERSION_CODE);
                } catch (JSONException e) {
                    Ajustes.quitarPrimero(c);
                    continue;
                }
                Resultado r = post(cuerpo);
                String resumen = hora(a.optLong("cuando")) + " · " + a.optString("appNombre") + " · " + corto(a.optString("texto"), 70);
                if (r.ok) {
                    Ajustes.quitarPrimero(c);
                    Ajustes.anotarHistorial(c, resumen + "\n→ " + legible(r.resultado));
                } else if (r.estado == 401 && r.json != null && r.json.has("ok")) {
                    // La dirección se cambió en MilalaApp: los avisos esperan hasta que se vuelva a conectar.
                    Ajustes.marcarDesconectado(c);
                    Ajustes.anotarHistorial(c, resumen + "\n→ Falta volver a conectar con MilalaApp");
                    return false;
                } else if (r.definitivo()) {
                    Ajustes.quitarPrimero(c);
                    Ajustes.anotarHistorial(c, resumen + "\n→ No se pudo: " + r.mensaje);
                } else if (r.estado >= 500 && a.optInt("intentos", 0) >= 5) {
                    // MilalaApp respondió con error varias veces: no se bloquean los avisos que vienen detrás.
                    Ajustes.quitarPrimero(c);
                    Ajustes.anotarHistorial(c, resumen + "\n→ No se pudo: " + r.mensaje);
                } else {
                    if (r.estado >= 500) {
                        try {
                            a.put("intentos", a.optInt("intentos", 0) + 1);
                            Ajustes.reemplazarPrimero(c, a);
                        } catch (JSONException ignorar) {
                            // sigue igual
                        }
                    }
                    if (programar) Reintento.programar(c);
                    return true;
                }
            }
        }
    }

    /** Comprueba la dirección y dice a quién pertenece (para mostrarlo antes de conectar). */
    static Resultado verificar(String token) {
        try {
            JSONObject b = new JSONObject();
            b.put("token", token);
            b.put("verificar", true);
            return post(b);
        } catch (JSONException e) {
            return new Resultado(false, 0, null, "Error interno", null);
        }
    }

    /** Deja un aviso de prueba en «Lo último que llegó» de MilalaApp. */
    static Resultado prueba(String token) {
        try {
            JSONObject b = new JSONObject();
            b.put("token", token);
            b.put("prueba", true);
            return post(b);
        } catch (JSONException e) {
            return new Resultado(false, 0, null, "Error interno", null);
        }
    }

    private static Resultado post(JSONObject cuerpo) {
        HttpURLConnection con = null;
        try {
            byte[] datos = cuerpo.toString().getBytes(StandardCharsets.UTF_8);
            con = (HttpURLConnection) new URL(Ajustes.FUNCION).openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(15000);
            con.setReadTimeout(90000);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            con.setRequestProperty("x-milala-aviso", "1");
            con.setFixedLengthStreamingMode(datos.length);
            try (OutputStream o = con.getOutputStream()) {
                o.write(datos);
            }
            int estado = con.getResponseCode();
            InputStream in = estado >= 400 ? con.getErrorStream() : con.getInputStream();
            String txt = in == null ? "" : leer(in);
            JSONObject j = null;
            try {
                j = new JSONObject(txt);
            } catch (JSONException ignorar) {
                // respuesta que no es JSON (ej: Supabase caído)
            }
            boolean ok = estado >= 200 && estado < 300 && j != null && j.optBoolean("ok", false);
            String resultado = j != null ? j.optString("resultado", null) : null;
            String mensaje = j != null ? j.optString("error", "Error " + estado) : "Error " + estado;
            return new Resultado(ok, estado, resultado, mensaje, j);
        } catch (IOException e) {
            return new Resultado(false, 0, null, "Sin conexión", null);
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String leer(InputStream in) throws IOException {
        try (InputStream x = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = x.read(buf)) > 0 && out.size() < 65536) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static String legible(String resultado) {
        if (resultado == null) return "Llegó";
        switch (resultado) {
            case "anotada": return "Anotada ✓";
            case "repetida": return "Ya estaba anotada (mismo pago) ✓";
            case "ya_estaba": return "Ya había llegado ✓";
            case "detalle_sumado": return "Se sumó a una compra ✓";
            case "ignorado": return "No era una compra";
            case "devolucion": return "Devolución o cashback: no es un gasto";
            case "ingreso": return "Plata que llegó: no es un gasto";
            case "pendiente": return "Transferencia: decide en MilalaApp si fue un gasto";
            case "sin_monto": return "No encontré el monto";
            case "limite": return "Demasiados avisos hoy";
            case "error": return "MilalaApp no pudo leerlo";
            default: return resultado;
        }
    }

    static String hora(long cuando) {
        return new SimpleDateFormat("d MMM HH:mm", new Locale("es", "CL")).format(new Date(cuando > 0 ? cuando : System.currentTimeMillis()));
    }

    static String corto(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > max ? t.substring(0, max - 1) + "…" : t;
    }
}
