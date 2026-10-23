package cl.milalaapp.avisos;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** La única pantalla: conectar con MilalaApp, dar el permiso, elegir las apps y ver lo último que se mandó. */
public class MainActivity extends Activity {

    private static final int FONDO = 0xFFF3F6F4, TARJETA = 0xFFFFFFFF, TEXTO = 0xFF13201B, SUAVE = 0xFF5B6A64,
            LINEA = 0xFFDCE4E0, MARCA = 0xFF0E8F6F, MARCA_SUAVE = 0xFFDDF3EC, BIEN = 0xFF16A34A, OJO = 0xFFD97706, MAL = 0xFFDC2626;

    /** Apps que parecen de bancos, tarjetas o billeteras (para ofrecerlas primero). */
    private static final Pattern BANCARIA = Pattern.compile(
            "banco|bank|mach|tenpo|mercado ?pago|bci|santander|scotia|ita[uú]|falabella|ripley|cmr|bice|security|coopeuch|"
                    + "estado|wallet|billetera|tarjeta|cuenta ?rut|prex|fpay|global ?66|chek|tapp|consorcio|cencosud|lider|"
                    + "walletnfcrel|cl\\.bci|cl\\.santander|cl\\.bancochile|cl\\.bancoestado",
            Pattern.CASE_INSENSITIVE);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout cuerpo;
    private boolean conectando;

    @Override
    protected void onCreate(Bundle guardado) {
        super.onCreate(guardado);
        getWindow().setStatusBarColor(MARCA);
        ScrollView scroll = new ScrollView(this);
        // Verde detrás de la barra de arriba (la hora y la batería se ven en blanco).
        scroll.setBackgroundColor(MARCA);
        scroll.setFillViewport(true);
        cuerpo = new LinearLayout(this);
        cuerpo.setOrientation(LinearLayout.VERTICAL);
        cuerpo.setBackgroundColor(FONDO);
        scroll.addView(cuerpo, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Android 15 dibuja la app detrás de la barra de arriba: se deja el espacio.
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                v.setPadding(0, in.getSystemWindowInsetTop(), 0, in.getSystemWindowInsetBottom());
                return in;
            }
        });
        setContentView(scroll);
        if (!Ajustes.appsElegidas(this)) elegirAppsPorDefecto();
        // El enlace se atiende una vez (no al girar la pantalla ni al volver desde «Recientes»).
        if (guardado == null && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) recibirEnlace(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        recibirEnlace(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        pintar();
        if (permisoActivo()) {
            // Si Android apagó el lector (pasa en algunos teléfonos), se le pide que lo vuelva a conectar.
            if (!LectorAvisos.conectado) NotificationListenerService.requestRebind(new ComponentName(this, LectorAvisos.class));
            Envio.vaciarLuego(this);
        }
    }

    // ---------- Pantalla ----------

    private void pintar() {
        cuerpo.removeAllViews();
        LinearLayout cab = new LinearLayout(this);
        cab.setOrientation(LinearLayout.VERTICAL);
        cab.setBackgroundColor(MARCA);
        cab.setPadding(dp(20), dp(22), dp(20), dp(24));
        cab.addView(texto("MilalaApp Avisos", 26, Color.WHITE, true));
        TextView sub = texto("Las compras que te avisa tu banco en este teléfono se anotan solas en MilalaApp.", 15, 0xE6FFFFFF, false);
        sub.setPadding(0, dp(6), 0, 0);
        cab.addView(sub);
        cuerpo.addView(cab);

        boolean conectado = Ajustes.token(this) != null && !Ajustes.desconectado(this);
        boolean permiso = permisoActivo();
        boolean todoListo = conectado && permiso && !Ajustes.apps(this).isEmpty();
        if (todoListo) {
            LinearLayout ok = tarjeta();
            ok.setBackground(fondo(MARCA_SUAVE, MARCA_SUAVE));
            ok.addView(texto("✓ Funcionando", 18, MARCA, true));
            ok.addView(parrafo("Cada aviso de compra de " + nombresElegidos() + " llega solo a MilalaApp. No tienes que abrir esta app."));
        }

        pasoConectar(conectado);
        pasoPermiso(permiso);
        pasoApps();
        pasoBateria();
        if (conectado) pasoPrueba();
        pasoHistorial();

        TextView pie = texto("Solo se leen los avisos de las apps marcadas. Los demás (WhatsApp, correos, fotos…) nunca salen del teléfono. "
                + "Versión " + BuildConfig.VERSION_NAME + ".", 13, SUAVE, false);
        pie.setPadding(dp(20), dp(8), dp(20), dp(28));
        cuerpo.addView(pie);
    }

    private void pasoConectar(boolean conectado) {
        LinearLayout t = tarjeta();
        t.addView(titulo("1. Conectar con MilalaApp", conectado));
        if (conectado) {
            String quien = Ajustes.persona(this);
            String hogar = Ajustes.hogar(this);
            String tok = Ajustes.token(this);
            t.addView(parrafo("Conectado" + (quien != null ? " a " + quien : "") + (hogar != null ? " · " + hogar : "")
                    + "\nDirección ····" + tok.substring(Math.max(0, tok.length() - 4)) + "@milalaapp.cl"));
            Button cambiar = boton("Cambiar o desconectar", false);
            cambiar.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("¿Desconectar este teléfono?")
                            .setMessage("Los avisos dejarán de llegar a MilalaApp hasta que vuelvas a conectar.")
                            .setPositiveButton("Desconectar", (d, w) -> { Ajustes.desconectar(MainActivity.this); pintar(); })
                            .setNegativeButton("Cancelar", null)
                            .show();
                }
            });
            t.addView(cambiar);
            return;
        }
        if (Ajustes.desconectado(this)) {
            TextView aviso = parrafo("MilalaApp ya no reconoce la dirección de este teléfono (se cambió en Ajustes). Vuelve a conectar: los avisos que llegaron mientras tanto están guardados.");
            aviso.setTextColor(OJO);
            t.addView(aviso);
        }
        t.addView(parrafo("En este teléfono, abre MilalaApp › Ajustes › Compras solas y toca «Conectar este teléfono».\n\nO copia tu dirección de MilalaApp y pégala aquí:"));
        final EditText campo = new EditText(this);
        campo.setHint("tu dirección @milalaapp.cl");
        campo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        campo.setSingleLine(true);
        t.addView(campo);
        Button b = boton("Conectar", true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { intentarConectar(campo.getText().toString()); }
        });
        t.addView(b);
    }

    private void pasoPermiso(boolean permiso) {
        LinearLayout t = tarjeta();
        t.addView(titulo("2. Permiso para leer avisos", permiso));
        if (permiso) {
            t.addView(parrafo("Listo. Android le permite a esta app ver los avisos que llegan."));
            return;
        }
        t.addView(parrafo("Toca el botón, busca «MilalaApp Avisos» y actívalo."));
        Button b = boton("Dar el permiso", true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { abrirPermiso(); }
        });
        t.addView(b);
        TextView ojo = parrafo("¿El interruptor sale gris o dice «Ajuste restringido»? Es porque la app no viene del Play Store. "
                + "Toca «Abrir información de la app», luego ⋮ (arriba a la derecha) › «Permitir ajustes restringidos», y vuelve a «Dar el permiso».");
        ojo.setTextColor(SUAVE);
        t.addView(ojo);
        Button info = boton("Abrir información de la app", false);
        info.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                abrir(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }
        });
        t.addView(info);
    }

    private void pasoApps() {
        LinearLayout t = tarjeta();
        Set<String> elegidas = Ajustes.apps(this);
        t.addView(titulo("3. Qué apps leer", !elegidas.isEmpty()));
        t.addView(parrafo("Marca la app de tu banco (ej: MACH) y la Billetera de Google si pagas con el teléfono. Si un pago llega por las dos, se anota una sola vez."));
        List<String[]> lista = appsParaElegir(elegidas);
        if (lista.isEmpty()) t.addView(parrafo("No encontré apps de bancos en este teléfono."));
        for (final String[] app : lista) {
            final CheckBox cb = new CheckBox(this);
            cb.setText(app[1]);
            cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            cb.setTextColor(TEXTO);
            cb.setChecked(elegidas.contains(app[0]));
            cb.setPadding(dp(4), dp(6), 0, dp(6));
            cb.setOnCheckedChangeListener((v, marcado) -> {
                Set<String> s = Ajustes.apps(MainActivity.this);
                if (marcado) s.add(app[0]); else s.remove(app[0]);
                Ajustes.guardarApps(MainActivity.this, s);
            });
            t.addView(cb);
        }
        TextView nota = parrafo("¿No aparece tu banco? Cuando te mande un aviso, vuelve aquí: aparecerá en la lista.");
        nota.setTextColor(SUAVE);
        t.addView(nota);
    }

    private void pasoBateria() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean libre = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        LinearLayout t = tarjeta();
        t.addView(titulo("4. Que Android no la duerma", libre));
        if (libre) {
            t.addView(parrafo("Listo. La app puede mandar los avisos aunque no la abras en días."));
            return;
        }
        t.addView(parrafo("Para ahorrar batería, Android a veces duerme las apps que no abres. Esta casi no gasta: solo trabaja cuando llega un aviso."));
        Button b = boton("Permitir que funcione siempre", true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!abrir(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())))) {
                    abrir(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                }
            }
        });
        t.addView(b);
    }

    private void pasoPrueba() {
        LinearLayout t = tarjeta();
        t.addView(titulo("Probar", false));
        t.addView(parrafo("Manda una prueba: debe aparecer en MilalaApp › Ajustes › Compras solas › «Lo último que llegó»."));
        final Button b = boton("Mandar una prueba", false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                b.setEnabled(false);
                b.setText("Mandando…");
                final String tok = Ajustes.token(MainActivity.this);
                new Thread(() -> {
                    final Envio.Resultado r = Envio.prueba(tok);
                    ui.post(() -> {
                        b.setEnabled(true);
                        b.setText("Mandar una prueba");
                        if (r.ok) aviso("Llegó ✓ Míralo en MilalaApp.");
                        else if (r.estado == 401) { Ajustes.marcarDesconectado(MainActivity.this); pintar(); aviso(r.mensaje); }
                        else aviso("No llegó: " + r.mensaje);
                    });
                }).start();
            }
        });
        t.addView(b);
    }

    private void pasoHistorial() {
        String[] h = Ajustes.historial(this);
        int pendientes = Ajustes.enCola(this);
        if (h.length == 0 && pendientes == 0) return;
        LinearLayout t = tarjeta();
        t.addView(titulo("Lo último que mandó", false));
        if (pendientes > 0) {
            TextView p = parrafo(pendientes + (pendientes == 1 ? " aviso espera" : " avisos esperan") + " internet para mandarse (se mandan solos).");
            p.setTextColor(OJO);
            t.addView(p);
        }
        for (String linea : h) {
            TextView l = texto(linea, 14, TEXTO, false);
            l.setPadding(0, dp(8), 0, dp(8));
            t.addView(l);
        }
    }

    // ---------- Conectar ----------

    private void recibirEnlace(Intent i) {
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction()) || i.getData() == null) return;
        Uri u = i.getData();
        if (!"milalaavisos".equals(u.getScheme()) || !u.isHierarchical()) return;
        String t = u.getQueryParameter("t");
        setIntent(new Intent(this, MainActivity.class));
        if (t == null) return;
        // Un enlace nunca cambia una conexión que ya existe: para eso está «Cambiar o desconectar».
        String actual = Ajustes.token(this);
        String nuevo = Ajustes.tokenDe(t);
        if (actual != null && !Ajustes.desconectado(this) && nuevo != null && !nuevo.equals(actual)) {
            aviso("Este teléfono ya está conectado. Para cambiarlo, toca primero «Cambiar o desconectar».");
            return;
        }
        intentarConectar(t);
    }

    private void intentarConectar(String texto) {
        final String token = Ajustes.tokenDe(texto);
        if (token == null) {
            aviso("Eso no parece una dirección de MilalaApp. Cópiala de MilalaApp › Ajustes › Compras solas.");
            return;
        }
        if (conectando) return;
        conectando = true;
        aviso("Revisando la dirección…");
        new Thread(() -> {
            final Envio.Resultado r = Envio.verificar(token);
            ui.post(() -> {
                conectando = false;
                if (isFinishing() || isDestroyed()) return;
                if (!r.ok) {
                    aviso(r.estado == 0 ? "Sin conexión. Prueba de nuevo con internet." : r.mensaje);
                    return;
                }
                final String persona = r.json != null && !r.json.isNull("persona") ? r.json.optString("persona") : null;
                final String hogar = r.json != null && !r.json.isNull("hogar") ? r.json.optString("hogar") : null;
                // Se muestra a quién se conecta: si alguien mandó un enlace ajeno, se nota antes de aceptar.
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("¿Conectar este teléfono?")
                        .setMessage("Los avisos de compra de las apps que marques se anotarán en MilalaApp de "
                                + (persona != null ? persona : "esta persona") + (hogar != null ? " (" + hogar + ")" : "") + ".\n\n"
                                + "Dirección ····" + token.substring(token.length() - 4) + "@milalaapp.cl: revisa que termine igual que la tuya en MilalaApp.")
                        .setPositiveButton("Conectar", (d, w) -> {
                            Ajustes.conectar(MainActivity.this, token, persona, hogar);
                            Envio.vaciarLuego(MainActivity.this);
                            pintar();
                            aviso("Conectado ✓");
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
            });
        }).start();
    }

    // ---------- Permisos ----------

    private boolean permisoActivo() {
        ComponentName lector = new ComponentName(this, LectorAvisos.class);
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) return nm.isNotificationListenerAccessGranted(lector);
        }
        String lista = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (lista == null) return false;
        String yo = lector.flattenToString();
        for (String s : lista.split(":")) if (yo.equals(s)) return true;
        return false;
    }

    private void abrirPermiso() {
        if (Build.VERSION.SDK_INT >= 30) {
            Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, new ComponentName(this, LectorAvisos.class).flattenToString());
            if (abrir(i)) return;
        }
        abrir(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    private boolean abrir(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---------- Apps ----------

    /** La primera vez quedan marcadas MACH y la Billetera de Google, si están instaladas. */
    private void elegirAppsPorDefecto() {
        Set<String> s = new HashSet<>();
        for (String[] app : appsInstaladas()) {
            String nombre = app[1].toLowerCase(Locale.ROOT);
            if (app[0].equals(Ajustes.BILLETERA) || nombre.startsWith("mach") || app[0].toLowerCase(Locale.ROOT).contains(".mach")) s.add(app[0]);
        }
        Ajustes.guardarApps(this, s);
    }

    private List<String[]> appsInstaladas() {
        List<String[]> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        Intent lanzador = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> vistos = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(lanzador, 0)) {
            String paquete = ri.activityInfo.packageName;
            if (paquete.equals(getPackageName()) || !vistos.add(paquete)) continue;
            CharSequence l = ri.loadLabel(pm);
            out.add(new String[] {paquete, l != null ? l.toString() : paquete});
        }
        return out;
    }

    private List<String[]> appsParaElegir(Set<String> elegidas) {
        Set<String> vistas = Ajustes.vistas(this);
        final Set<String> marcadas = elegidas;
        List<String[]> out = new ArrayList<>();
        Set<String> puestas = new HashSet<>();
        for (String[] app : appsInstaladas()) {
            boolean bancaria = BANCARIA.matcher(app[1]).find() || BANCARIA.matcher(app[0]).find();
            if (bancaria || elegidas.contains(app[0]) || vistas.contains(app[0])) {
                out.add(app);
                puestas.add(app[0]);
            }
        }
        // Apps elegidas o que avisaron, aunque no tengan ícono en el inicio
        Set<String> extra = new HashSet<>(elegidas);
        extra.addAll(vistas);
        for (String paquete : extra) {
            if (puestas.contains(paquete) || paquete.equals(getPackageName())) continue;
            String nombre = LectorAvisos.nombreApp(this, paquete);
            if (!nombre.equals(paquete) || elegidas.contains(paquete)) out.add(new String[] {paquete, nombre});
        }
        Collections.sort(out, new Comparator<String[]>() {
            @Override public int compare(String[] a, String[] b) {
                int ma = marcadas.contains(a[0]) ? 0 : 1, mb = marcadas.contains(b[0]) ? 0 : 1;
                if (ma != mb) return ma - mb;
                return a[1].compareToIgnoreCase(b[1]);
            }
        });
        return out;
    }

    private String nombresElegidos() {
        List<String> n = new ArrayList<>();
        for (String p : Ajustes.apps(this)) n.add(LectorAvisos.nombreApp(this, p));
        Collections.sort(n);
        if (n.isEmpty()) return "tus apps";
        if (n.size() == 1) return n.get(0);
        return String.join(", ", n.subList(0, n.size() - 1)) + " y " + n.get(n.size() - 1);
    }

    // ---------- Piezas de la pantalla ----------

    private LinearLayout tarjeta() {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(18), dp(16), dp(18), dp(16));
        t.setBackground(fondo(TARJETA, LINEA));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(14), dp(16), 0);
        cuerpo.addView(t, lp);
        return t;
    }

    private GradientDrawable fondo(int color, int borde) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1), borde);
        return g;
    }

    private TextView titulo(String s, boolean listo) {
        TextView t = texto((listo ? "✓  " : "") + s, 18, listo ? BIEN : TEXTO, true);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private TextView parrafo(String s) {
        TextView t = texto(s, 15, TEXTO, false);
        t.setPadding(0, dp(4), 0, dp(8));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private TextView texto(String s, int sp, int color, boolean negrita) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (negrita) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button boton(String s, boolean principal) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTextColor(principal ? Color.WHITE : MARCA);
        GradientDrawable g = new GradientDrawable();
        g.setColor(principal ? MARCA : TARJETA);
        g.setCornerRadius(dp(14));
        g.setStroke(dp(principal ? 0 : 1), principal ? MARCA : LINEA);
        b.setBackground(g);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(48));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void aviso(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
