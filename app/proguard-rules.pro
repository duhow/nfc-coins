# Keep the app entry point
-keep class net.duhowpi.nfccoins.MainActivity { *; }

# Keep BuildConfig fields used at runtime
-keepclassmembers class net.duhowpi.nfccoins.BuildConfig {
    public static final java.lang.String NFC_PSK;
}
