import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Chave de release fora do Git: keystore.properties na raiz com storeFile, storePassword, keyAlias, keyPassword.
// Sem ele, o release sai assinado com a chave de debug (serve para testar, não para publicar).
val chave = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "br.ps2ponte"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.wraphs.pocketps2server"
        // Android 5.0: o mínimo com USB Host completo e seletor de pastas (SAF). A ancoragem por cabo
        // depende da ROM; o app serve em qualquer rede que alcance o PS2.
        minSdk = 21
        targetSdk = 34
        versionCode = 3
        versionName = "1.0.1"
    }

    signingConfigs {
        if (chave != null) create("release") {
            storeFile = rootProject.file(chave.getProperty("storeFile"))
            storePassword = chave.getProperty("storePassword")
            keyAlias = chave.getProperty("keyAlias")
            keyPassword = chave.getProperty("keyPassword")
            // Android 5/6 só conhecem a assinatura v1
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.util/java.time/streams das versões novas do Java também no Android 5–7
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        checkDependencies = true
    }
}

dependencies {
    implementation(project(":core"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
