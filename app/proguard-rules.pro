# Reglas de R8 (solo se usa al compilar la versión Google Play; la familia va sin ofuscar).

# VLC: la parte nativa (libvlcjni) llama a estas clases y campos por su nombre.
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# Decodificador FFmpeg de Media3 (JNI).
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# Los enums de la app se guardan por nombre (Prefs, favoritos, historial): no renombrar sus valores,
# o al actualizar la app no se reconocerían los datos guardados.
-keepclassmembers enum com.ale.cineiptv.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Trazas de error legibles en Play Console (con el archivo de desofuscación).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
