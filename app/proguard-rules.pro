# Ed25519 library uses reflection-free pure Java; keep provider registration intact.
-keep class net.i2p.crypto.eddsa.** { *; }
# Room entities and DAOs are kept by the Room consumer rules.
-keepattributes Signature, InnerClasses, EnclosingMethod
# EdDSAEngine checks for the JDK-internal X509Key only when handed a desktop
# JCA key. Android never does that, so the class is absent and safe to ignore.
-dontwarn sun.security.x509.X509Key
