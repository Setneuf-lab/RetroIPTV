import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ale.cineiptv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ale.retroiptv"
        minSdk = 26          // Android 8.0
        targetSdk = 35
        // GitHub pone el número de versión automáticamente en cada compilación
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.2.0"

        // Datos del panel web (Firebase). Se rellenan en gradle.properties.
        val fbProject = (project.findProperty("cine.firebaseProjectId") as String?).orEmpty().trim()
        val fbKey = (project.findProperty("cine.firebaseApiKey") as String?).orEmpty().trim()
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$fbProject\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"$fbKey\"")

        // Solo procesadores ARM (todos los Fire TV, TV Box y móviles): la app pesa mucho menos
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }

        // Repositorio de GitHub donde se publican las versiones (usuario/proyecto)
        val updateRepo = (project.findProperty("cine.updateRepo") as String?).orEmpty().trim()
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    // Firma de la app. Todas las versiones deben firmarse con la MISMA llave,
    // si no, Android no deja actualizar. La llave NO se sube a GitHub:
    //  - En tu PC: app/keystore/cineiptv.jks (+ contraseña en keystore.properties)
    //  - En GitHub: se guarda en "Secrets" y se pasa por variables de entorno
    signingConfigs {
        create("release") {
            val localProps = Properties().apply {
                val f = file("keystore/keystore.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "keystore/cineiptv.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: localProps.getProperty("password")
            keyAlias = System.getenv("KEY_ALIAS") ?: localProps.getProperty("alias", "cineiptv")
            keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: localProps.getProperty("password")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Librerías nativas comprimidas dentro de la APK (VLC y FFmpeg ocupan mucho)
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // VLC y FFmpeg traen la misma librería de C++: se usa una sola copia
            pickFirsts += "**/libc++_shared.so"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // AndroidX base
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")

    // Red + corrutinas
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Reproductor Media3 (ExoPlayer) con soporte HLS (1.6.1: correcciones de estabilidad)
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    // Conexiones de vídeo con OkHttp (más robustas con redirecciones y reconexiones)
    implementation("androidx.media3:media3-datasource-okhttp:1.6.1")
    // Decodificador de audio por software (AC3, E-AC3/Dolby, MP2, DTS…): muchos canales IPTV
    // usan estos formatos y los móviles no los decodifican → vídeo sin sonido.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.6.1+2")
    // Segundo motor de reproducción (VLC): se usa solo cuando el principal no puede con un canal
    implementation("org.videolan.android:libvlc-all:3.6.2")

    // Logos de canales / carátulas
    implementation("io.coil-kt:coil:2.7.0")
}
