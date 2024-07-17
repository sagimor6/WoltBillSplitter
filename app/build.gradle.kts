import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "io.github.sagimor6.woltbillsplitter"
    compileSdk = 34
    buildToolsVersion = "35.0.0-rc2"

    dependenciesInfo {
        includeInApk = false
    }

    signingConfigs {
        if (System.getenv("KEYSTORE_FILE_PATH") != null) {
            create("release") {
                storeFile = rootProject.file(System.getenv("KEYSTORE_FILE_PATH"))
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEYSTORE_KEY_ALIAS")
                keyPassword = System.getenv("KEYSTORE_KEY_PASSWORD")
            }
        } else if (rootProject.file("keystore.properties").exists()) {
            val keystoreProperties = Properties()
            keystoreProperties.load(rootProject.file("keystore.properties").inputStream())

            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        } // else just don't sign
    }

    defaultConfig {
        applicationId = "io.github.sagimor6.woltbillsplitter"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            //isMinifyEnabled = false
            isMinifyEnabled = true
            isShrinkResources = true
            //isDebuggable = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    implementation(libs.workRuntime)
    implementation(libs.gson)
}

gradle.projectsEvaluated {
    tasks.getByName("assembleRelease") {
        doLast {
            if (android.signingConfigs.findByName("release") == null) {
                return@doLast
            }

            val jsonObject = JsonObject()
            jsonObject.addProperty("min_update_ver", "0.0.0")
            jsonObject.addProperty("ver", android.defaultConfig.versionName)
            val updaterInfo = jsonObject.toString()
            val base64UpdaterInfo = Base64.getEncoder().withoutPadding().encode(updaterInfo.toByteArray(StandardCharsets.UTF_8))

            val relSignConf = android.signingConfigs.getByName("release")
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(relSignConf.storeFile?.inputStream(), relSignConf.storePassword?.toCharArray())
            val privKey = keyStore.getKey(relSignConf.keyAlias, relSignConf.keyPassword?.toCharArray()) as? PrivateKey
            val sig = Signature.getInstance("Sha512WithRSA")
            sig.initSign(privKey)
            sig.update(0)
            sig.update(0)
            sig.update("INFO_AND_APK_SIG".toByteArray(StandardCharsets.US_ASCII))
            sig.update(0)
            sig.update(base64UpdaterInfo)
            sig.update(0)
            sig.update(file("build/outputs/apk/release/app-release.apk").readBytes())
            val sigBytes = sig.sign()

            var githubUpdaterInfo = String(base64UpdaterInfo, StandardCharsets.US_ASCII)
            githubUpdaterInfo = githubUpdaterInfo.replace('+', '=')
            githubUpdaterInfo = "INFO FOR UPDATER:::$githubUpdaterInfo\n"

            file("build/outputs/apk/release/app-release.apk.sig").writeBytes(sigBytes)
            file("build/outputs/apk/release/updater_info.txt").writeBytes(base64UpdaterInfo)
            file("build/outputs/apk/release/updater_info.github.txt").writeBytes(githubUpdaterInfo.toByteArray(StandardCharsets.US_ASCII))
        }
    }
}
