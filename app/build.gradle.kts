import java.util.Properties

// La clave de firma está en keystore.properties (no se comparte ni se sube a ningún lado).
val keystoreProps = Properties()
val keystoreFile = rootProject.file("keystore.properties")
if (keystoreFile.exists()) {
    keystoreFile.inputStream().use { keystoreProps.load(it) }
}

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.expresolomagrande.gps"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.expresolomagrande.gps"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Sin ofuscar a propósito: Play Protect desconfía más de los APK ofuscados y sin firma propia.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Sin librerías externas: el APK queda pequeño y sin código de terceros.
dependencies {
}
