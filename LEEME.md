# Gamemory para Android

La app enseña la web de tu Gamemory (todo lo que cambia con `deploy.sh` llega
solo) y añade lo que la web no puede hacer desde Native Alpha:

- **Elegir varias fotos a la vez** al subir.
- **Compartir → Gamemory** desde la galería y las apps de Nintendo y PlayStation.
- **Al abrirla, sube lo nuevo** de las carpetas de fotos que elijas
  (Ajustes › App del móvil). Nada en segundo plano.
- **Se actualiza sola** desde las «releases» de GitHub.
- **Notificación de la sesión activa** (1.1): carátula, juego y cronómetro, con
  Pausa / Seguir, Hito y Terminar. Pregunta a `GET /api/sesion-activa` al
  abrirla, al volver, cuando la página de la sesión cambia y cada ~15 min
  (JobScheduler); Pausa y Seguir van a `POST /api/sesion-activa/pausar|reanudar`.

Java sin librerías (solo el SDK de Android), en `app/src/main/java/app/gamemory/movil/`:
`MainActivity.java` (WebView, selector de archivos, compartir, descargas,
actualizar y el puente `window.GamemoryApp`) y `Fotos.java` (carpetas y subida
a `POST /fotos/subir`); desde la 1.1, `Sesion.java` (la notificación),
`SesionReceiver.java` (sus botones, quitarla, reprogramar al reiniciar) y
`SesionJob.java` (la revisión periódica), con sus iconos en `res/drawable/`.
En la web: `app/static/js/app_movil.js`.

## Cómo se construye

1. `deploy.sh` manda esta carpeta (solo esta) al repositorio de GitHub que
   diga `.android-repo` (en la raíz de Gamemory), cuando ha cambiado.
2. GitHub Actions (`ci/app.yml`, que `deploy.sh` coloca en `.github/workflows/app.yml` del repositorio de la app) la compila y, con los secretos
   `ANDROID_KEYSTORE` y `ANDROID_KEYSTORE_PASSWORD`, la firma y publica una
   «release» `bN` (N = número de compilación) con el APK.
3. La app mira esa «release» al abrirse (cada 6 h como mucho) y ofrece «Actualizar».

La versión visible está en `version.txt`; las novedades que enseña al
actualizar, en `NOVEDADES.md`.

## Una sola vez (en el servidor)

- Repositorio público `gamemory-android` en GitHub, vacío.
- Llave de despliegue: `ssh-keygen -t ed25519 -f ~/.ssh/gamemory_android -N ""`,
  `Host github-gamemory` en `~/.ssh/config` y la `.pub` en Settings › Deploy
  keys con «Allow write access».
- Llave de firma (keystore) en los secretos del repositorio. **Guárdala**: sin
  ella, una versión nueva no se instala encima de la anterior.
- `.android-repo` en la raíz de Gamemory: `git@github-gamemory:USUARIO/gamemory-android.git`
