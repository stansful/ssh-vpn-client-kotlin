# JSch has algorithm implementations loaded by configured class names.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# BouncyCastle classes are reached through JSch EdDSA support and provider internals.
-dontwarn org.bouncycastle.**

# Tink registries/keyset managers are sensitive to aggressive class stripping.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class libXray.** { *; }
-keep class libxray.** { *; }
