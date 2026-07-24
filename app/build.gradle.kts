plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.smartremote"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    buildFeatures {
        viewBinding = true
    }

    defaultConfig {
        applicationId = "com.example.smartremote"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    // Necessária para a lista de TVs encontradas na DeviceDiscoveryActivity.
    // Adicionada por coordenada direta pois não tenho acesso ao catálogo
    // libs.versions.toml do projeto - se preferir, mova para o catálogo
    // como alias "androidx-recyclerview".
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // WebSocket para conexão com Smart TVs (Samsung Tizen nesta fase; demais
    // fabricantes reutilizarão o mesmo cliente OkHttp em fases futuras).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}