package cl.milalaapp.avisos;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Si no había internet, Android despierta esto cuando vuelve la conexión y se mandan los avisos pendientes. */
public class Reintento extends JobService {
    private static final int ID = 4711;

    static void programar(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        JobInfo info = new JobInfo.Builder(ID, new ComponentName(c, Reintento.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(20_000)
                .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        try {
            js.schedule(info);
        } catch (RuntimeException e) {
            // Si Android no deja agendar, los avisos esperan a la próxima vez que llegue uno o se abra la app.
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context c = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                boolean quedan = Envio.vaciar(c, false);
                jobFinished(params, quedan);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
