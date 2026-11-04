# Expreso Loma Grande: app Android del GPS

App que abre directamente el seguimiento GPS del panel (`panel.expresolomagrande.com`).
Es el mismo sistema web dentro de una app: mismo usuario, mismas pantallas, mismos datos.

- **Entrada rápida:** después del primer inicio de sesión ofrece guardar el acceso protegido con la
  huella (o el PIN/patrón del celular). El usuario y la contraseña quedan cifrados en el celular.
- **Actualizaciones:** al abrirse busca versiones nuevas en las *Releases* de este repositorio de GitHub
  y avisa. Se instala encima, sin desinstalar y sin perder la sesión guardada.

## Cómo publicar una versión nueva

1. En `app/build.gradle.kts` suba `versionCode` (por ejemplo 2 → 3) y `versionName` (por ejemplo "1.1.0" → "1.1.1").
2. Compile el APK firmado:

   ```
   gradlew.bat assembleRelease
   ```

   El archivo queda en `app/build/outputs/apk/release/app-release.apk`. Cámbiele el nombre,
   por ejemplo `ExpresoLomaGrande_v1.1.1.apk` (debe terminar en `.apk`).
3. En GitHub: **Releases → Draft a new release**.
   - Tag: `v1.1.1` (el mismo número que `versionName`, con la letra v delante).
   - Adjunte el APK.
   - **Publish release**.
4. La próxima vez que se abra la app en el celular aparece "Nueva versión disponible".

## Muy importante: la clave de firma

`gps-loma-grande.jks` y `keystore.properties` **no se suben a GitHub** (están en `.gitignore`).
Haga una copia de seguridad en un lugar privado (USB o Drive personal). Si se pierde, las
actualizaciones dejan de instalarse encima y habría que desinstalar la app y empezar de nuevo.

Cada APK nuevo debe firmarse con esa misma clave.

## Configuración

- Repositorio donde se buscan las versiones: `app/src/main/res/values/strings.xml` (`update_repo`).
- Dirección del panel: `HOST` en `MainActivity.java`.
