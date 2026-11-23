package app.gamemory.movil;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** v1.1: cada ~15 min, ¿hay sesión activa? (para la notificación). */
public class SesionJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            Sesion.refrescarYa(getApplicationContext());
            jobFinished(params, false);
        }, "gm-sesion-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
