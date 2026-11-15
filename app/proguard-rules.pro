# Ed25519 library uses reflection-free pure Java; keep provider registration intact.
-keep class net.i2p.crypto.eddsa.** { *; }
# Room entities and DAOs are kept by the Room consumer rules.
-keepattributes Signature, InnerClasses, EnclosingMethod
