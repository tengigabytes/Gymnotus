# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 tengigabytes and Gymnotus contributors

# The fast-mode setup (adb/SelfAdb.kt) runs through libraries that find classes by name at run time: JCA
# providers, the TLS stack, and the JNI side of the SPAKE2 pairing code. R8 cannot see those uses, and a class it
# removes would only be missed in the middle of a pairing, so these libraries are kept whole.
-keep class io.github.muntashirakon.** { *; }
-keep class android.sun.** { *; }
-keep class org.conscrypt.** { *; }
-keep class org.bouncycastle.** { *; }

# They refer to optional platform and desktop classes that do not exist on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn android.sun.**
-dontwarn javax.naming.**
