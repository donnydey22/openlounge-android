// :olcall — Open Lounge calling engine (tgcalls <-> olcall Go/Pion server) as an
// Android library module. Plugs into the OL Android app repo
// (pj-openlounge-android, see android/README.md) as `include(":olcall")`.
//
// Native part: src/main/cpp/CMakeLists.txt compiles the JNI bridge and the OL
// audio device module (audio/android, audio/core in this package) and links the
// prebuilt tgcalls+WebRTC static library + org.olwrtc Java jar produced by
// android/native/build-tgcalls-android-sdk.sh. Point OLCALL_TGCALLS_SDK (gradle
// property or env) at that SDK directory.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val tgSdk: String = (findProperty("OLCALL_TGCALLS_SDK") as String?)
    ?: System.getenv("OLCALL_TGCALLS_SDK")
    ?: rootProject.file("third_party/olcall-tgcalls-android-sdk").path

android {
    namespace = "com.donnydey.openlounge.olcall"
    compileSdk = 36
    // Must match the NDK the prebuilt SDK was built with (SDK.json "ndk").
    ndkVersion = (findProperty("OLCALL_NDK_VERSION") as String?) ?: "27.2.12479018"

    defaultConfig {
        // Build floor = the API the OL audio layer is compiled against
        // (audio/android/build-native.sh API=27; AAudio calls above 27 are
        // weak-linked and guarded). The real minimum Android version is whatever
        // the oldest phone in matrix/devices.json actually passes on.
        minSdk = 27
        consumerProguardFiles("consumer-rules.pro")
        // Phones only (arm). The prebuilt SDK is built for arm64-v8a + armeabi-v7a;
        // add x86_64 here only after building it (ABIS=... build-tgcalls-android-sdk.sh).
        ndk { abiFilters += ((findProperty("OLCALL_ABIS") as String?) ?: "arm64-v8a,armeabi-v7a").split(",") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DOLCALL_TGCALLS_SDK=$tgSdk", "-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++20")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets["main"].java.srcDirs(
        "src/main/java",
        // Shared with the earlier compile-checked sources; single copy, not forked.
        "../../audio/android/kotlin",
    )

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Shared relay-list golden fixture (Go olcall/tgrtc is the reference; iOS uses the same file).
    sourceSets["test"].resources.srcDirs("../../olcall/integration/testdata")

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // Telegram's WebRTC Java package is RELOCATED org.webrtc -> org.olwrtc
    // (android/native/relocate-webrtc.py) so it can live in the same APK as the
    // app's existing org.webrtc engine (BSD m152) without duplicate classes or
    // JNI symbol clashes. Point OLCALL_TGCALLS_SDK at the relocated SDK.
    // org.olwrtc Java classes MUST come from the same WebRTC source as the
    // native library (JNI is generated per revision), hence the SDK jar, not Maven.
    api(files("$tgSdk/java/libwebrtc-java.jar"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
