import java.util.Properties

plugins {
    id("com.android.application")
}

val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "app.zipr"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.zipr"
        minSdk = 21
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

// Release APK named zipr-<versionName>.apk. VariantOutputImpl is AGP-internal; drop this block if an AGP update breaks it.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { out ->
            (out as com.android.build.api.variant.impl.VariantOutputImpl)
                .outputFileName.set(out.versionName.map { "zipr-$it.apk" })
        }
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}

// Builds libzipr.so into src/main/jniLibs. Skip with -PskipRust (e.g. when only touching Kotlin).
val buildRust = tasks.register<Exec>("buildRust") {
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/build-rust.sh")
}
if (!providers.gradleProperty("skipRust").isPresent) {
    tasks.named("preBuild") { dependsOn(buildRust) }
}
