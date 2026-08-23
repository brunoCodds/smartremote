import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// *** CORREÇÃO v0.8 - item 4 da auditoria: signingConfig de release ***
// Nenhuma keystore real é criada nem commitada aqui (e não deveria ser).
// O signingConfig abaixo só lê alias/senhas de `local.properties` (arquivo
// já ignorado pelo .gitignore do projeto) ou, alternativamente, de
// variáveis de ambiente - nunca hardcoded.
//
// Para gerar o primeiro build de release ASSINADO localmente, quem for
// rodar precisa:
//   1) Gerar uma keystore própria, por exemplo:
//        keytool -genkey -v -keystore smartremote-release.jks \
//          -keyalg RSA -keysize 2048 -validity 10000 -alias smartremote
//   2) Adicionar ao `local.properties` (NUNCA commitar este arquivo):
//        RELEASE_STORE_FILE=/caminho/absoluto/ou/relativo/para/smartremote-release.jks
//        RELEASE_STORE_PASSWORD=...
//        RELEASE_KEY_ALIAS=smartremote
//        RELEASE_KEY_PASSWORD=...
//      (ou exportar as mesmas 4 chaves como variáveis de ambiente, útil em CI)
//
// Enquanto essas propriedades não existirem, `hasReleaseSigningConfig` fica
// false e o buildType release simplesmente não recebe signingConfig (o
// build continua funcionando sem assinatura de release - só não gera um
// APK/AAB pronto para publicar).
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        FileInputStream(localPropertiesFile).use { load(it) }
    }
}

fun releaseSigningProp(propertyName: String): String? =
    localProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }
        ?: System.getenv(propertyName)?.takeIf { it.isNotBlank() }

val releaseStoreFilePath = releaseSigningProp("RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningProp("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningProp("RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningProp("RELEASE_KEY_PASSWORD")

val hasReleaseSigningConfig = releaseStoreFilePath != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "com.example.smartremote"

    // *** CORREÇÃO v0.8 - compileSdk além do que a AGP instalada testa ***
    // Antes: release(37) { minorApiLevel = 1 } = API "37.1". A AGP deste
    // projeto (9.2.1, ver gradle/libs.versions.toml) documenta suporte
    // oficialmente TESTADO só até a API 37.0 - o próprio log de build já
    // avisava disso. "37.1" é uma API ainda mais nova que o próprio teto
    // testado da ferramenta, o pior dos dois mundos.
    //
    // Cheguei a tentar baixar para 36 (alinhado ao targetSdk, mais maduro
    // que 37.0) - mas isso quebra a build de verdade: `androidx.core` e
    // `androidx.core-ktx` na versão 1.19.0 (já usada neste projeto antes
    // desta v0.8) declaram nos metadados do AAR que exigem compileSdk 37+
    // (erro real do Gradle: "checkDebugAarMetadata"). Ou seja, 36 não é
    // uma opção viável enquanto o projeto depender dessa versão do
    // androidx.core.
    //
    // Decisão final: 37.0 (sem o ".1" extra) - é exatamente o teto
    // oficialmente testado pela AGP 9.2.1, satisfaz o mínimo exigido pelo
    // androidx.core 1.19.0, e evita tanto o aviso original (37.1 acima do
    // teto) quanto a quebra de build (36 abaixo do mínimo exigido pela
    // dependência).
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    buildFeatures {
        viewBinding = true
    }

    defaultConfig {
        applicationId = "com.example.smartremote"
        minSdk = 26
        targetSdk = 36

        // *** CORREÇÃO v0.8 - item 2 da auditoria: inconsistência de versão ***
        // versionName "1.0"/versionCode 1 sugeriam um app já em 1.0 "de
        // verdade", o que não reflete a fase atual (correção/robustez -
        // ver histórico de commits v0.2 -> v0.7 deste repositório). Não há
        // nenhuma tag/release anterior no Git com um versionCode explícito,
        // então começamos aqui um esquema simples e monotônico
        // (versionCode = 8 para versionName "0.8"); versões futuras devem
        // seguir subindo os dois de forma coerente.
        versionCode = 8
        versionName = "0.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // *** CORREÇÃO v0.8 - item 8 da auditoria: R8 estava desligado
            // no release (enable = false), ou seja, builds de release
            // saíam sem minificação/ofuscação nenhuma. Ligado agora; ver
            // app/src/main/keepRules/rules.keep para as regras adicionadas
            // como proteção (enums via valueOf()/values(), usados por
            // DeviceStorage ao desserializar TvDevice de JSON com
            // org.json - não há reflexão nem nomes de campo obtidos via
            // reflection neste projeto, então o risco de quebra é baixo,
            // mas as regras de enum são uma proteção padrão e barata).
            // NOTA: com a AGP 9.2.1 deste projeto, `enable = true` só
            // funciona com `android.r8.gradual.support=true` setado em
            // gradle.properties (ver comentário lá) - esse DSL só vira
            // comportamento padrão/estável a partir da AGP 9.3.
            optimization {
                enable = true
            }
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
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
    // *** v0.8 - item 9 da auditoria: movidas para o catálogo
    // gradle/libs.versions.toml (aliases androidx-recyclerview / okhttp),
    // por consistência com as demais dependências do projeto, que já
    // usavam libs.* ***
    implementation(libs.androidx.recyclerview)
    implementation(libs.okhttp)
    // *** NOVO - v0.9.3, item 3 (menu lateral) - ver comentário em libs.versions.toml ***
    implementation(libs.androidx.drawerlayout)
    // *** v0.9, item 3 (Android TV) - ver comentário em libs.versions.toml ***
    implementation(libs.protobuf.javalite)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
