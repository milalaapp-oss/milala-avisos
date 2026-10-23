package cl.milalaapp.avisos;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lo que la app recuerda en el teléfono (nunca sale de aquí, salvo los avisos de las apps elegidas). */
final class Ajustes {
    private Ajustes() {}

    /** La función «correo» de MilalaApp en Supabase (la misma que recibe los correos). */
    static final String FUNCION = "https://jgqayzdccnpajxnzsffu.supabase.co/functions/v1/correo";
    /** Billetera de Google: avisa cada pago con el teléfono (comercio, monto y tarjeta). */
    static final String BILLETERA = "com.google.android.apps.walletnfcrel";

    private static final int MAX_COLA = 100;
    private static final int MAX_HISTORIAL = 15;
    private static final int MAX_VISTAS = 40;
    private static final int MAX_FIRMAS = 60;
    private static final Pattern TOKEN = Pattern.compile("([a-z0-9]{10,32})(?:@milalaapp\\.cl)?");

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("milala", Context.MODE_PRIVATE);
    }

    // ---------- La dirección (es la llave para mandar avisos a MilalaApp) ----------

    /** Acepta «k7p2…@milalaapp.cl» o solo las letras y números; devuelve null si no lo parece. */
    static String tokenDe(String texto) {
        if (texto == null) return null;
        String t = texto.trim().toLowerCase(Locale.ROOT);
        int mas = t.indexOf('+');
        int arroba = t.indexOf('@');
        if (mas > 0 && (arroba < 0 || mas < arroba)) t = t.substring(0, mas) + (arroba > 0 ? t.substring(arroba) : "");
        Matcher m = TOKEN.matcher(t);
        return m.matches() ? m.group(1) : null;
    }

    static String token(Context c) { return p(c).getString("token", null); }

    static void conectar(Context c, String token, String persona, String hogar) {
        p(c).edit().putString("token", token).putString("persona", persona).putString("hogar", hogar)
                .putBoolean("desconectado", false).apply();
    }

    static void desconectar(Context c) {
        p(c).edit().remove("token").remove("persona").remove("hogar").putBoolean("desconectado", false).apply();
    }

    static String persona(Context c) { return p(c).getString("persona", null); }
    static String hogar(Context c) { return p(c).getString("hogar", null); }

    /** MilalaApp ya no reconoce la dirección (se cambió en Ajustes): hay que volver a conectar. */
    static boolean desconectado(Context c) { return p(c).getBoolean("desconectado", false); }
    static void marcarDesconectado(Context c) { p(c).edit().putBoolean("desconectado", true).apply(); }

    // ---------- Qué apps se leen ----------

    static boolean appsElegidas(Context c) { return p(c).getBoolean("apps_elegidas", false); }

    static Set<String> apps(Context c) {
        return new HashSet<>(p(c).getStringSet("apps", new HashSet<String>()));
    }

    static void guardarApps(Context c, Set<String> apps) {
        p(c).edit().putStringSet("apps", new HashSet<>(apps)).putBoolean("apps_elegidas", true).apply();
    }

    /** Apps que han mandado avisos (solo el nombre del paquete, nunca el texto), para poder elegirlas. */
    static synchronized void anotarVista(Context c, String paquete) {
        Set<String> v = new HashSet<>(p(c).getStringSet("vistas", new HashSet<String>()));
        if (v.contains(paquete)) return;
        if (v.size() >= MAX_VISTAS) v.clear();
        v.add(paquete);
        p(c).edit().putStringSet("vistas", v).apply();
    }

    static Set<String> vistas(Context c) {
        return new HashSet<>(p(c).getStringSet("vistas", new HashSet<String>()));
    }

    // ---------- Un mismo aviso no se manda dos veces ----------

    /** true si este aviso exacto (misma app, misma hora y mismo texto) ya se tomó. */
    static synchronized boolean yaTomado(Context c, String firma) {
        JSONArray f = arreglo(c, "firmas");
        for (int i = 0; i < f.length(); i++) if (firma.equals(f.optString(i))) return true;
        JSONArray nueva = new JSONArray();
        int desde = Math.max(0, f.length() - (MAX_FIRMAS - 1));
        for (int i = desde; i < f.length(); i++) nueva.put(f.optString(i));
        nueva.put(firma);
        p(c).edit().putString("firmas", nueva.toString()).apply();
        return false;
    }

    // ---------- Avisos por mandar (si no hay internet, esperan aquí) ----------

    static synchronized void encolar(Context c, JSONObject aviso) {
        JSONArray cola = arreglo(c, "cola");
        JSONArray nueva = new JSONArray();
        int desde = Math.max(0, cola.length() - (MAX_COLA - 1));
        for (int i = desde; i < cola.length(); i++) nueva.put(cola.opt(i));
        nueva.put(aviso);
        p(c).edit().putString("cola", nueva.toString()).apply();
    }

    static synchronized JSONObject primeroDeCola(Context c) {
        JSONArray cola = arreglo(c, "cola");
        return cola.length() > 0 ? cola.optJSONObject(0) : null;
    }

    static synchronized void quitarPrimero(Context c) {
        JSONArray cola = arreglo(c, "cola");
        JSONArray nueva = new JSONArray();
        for (int i = 1; i < cola.length(); i++) nueva.put(cola.opt(i));
        p(c).edit().putString("cola", nueva.toString()).apply();
    }

    static synchronized void reemplazarPrimero(Context c, JSONObject aviso) {
        JSONArray cola = arreglo(c, "cola");
        if (cola.length() == 0) return;
        JSONArray nueva = new JSONArray();
        nueva.put(aviso);
        for (int i = 1; i < cola.length(); i++) nueva.put(cola.opt(i));
        p(c).edit().putString("cola", nueva.toString()).apply();
    }

    static synchronized int enCola(Context c) { return arreglo(c, "cola").length(); }

    // ---------- Lo último que se mandó (para mirar en la app) ----------

    static synchronized void anotarHistorial(Context c, String linea) {
        JSONArray h = arreglo(c, "historial");
        JSONArray nueva = new JSONArray();
        nueva.put(linea);
        for (int i = 0; i < h.length() && nueva.length() < MAX_HISTORIAL; i++) nueva.put(h.optString(i));
        p(c).edit().putString("historial", nueva.toString()).apply();
    }

    static synchronized String[] historial(Context c) {
        JSONArray h = arreglo(c, "historial");
        String[] out = new String[h.length()];
        for (int i = 0; i < h.length(); i++) out[i] = h.optString(i);
        return out;
    }

    private static JSONArray arreglo(Context c, String clave) {
        try {
            return new JSONArray(p(c).getString(clave, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }
}
