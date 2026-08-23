package com.example.smartremote

import android.app.Application
import com.example.smartremote.util.LanguageManager

/**
 * *** NOVO - v0.9.3 (correção: idioma não sobrevivia a reabrir o app) ***
 *
 * Único propósito desta classe: reaplicar o idioma salvo em
 * [LanguageManager.applyPersistedLanguage] o mais cedo possível no ciclo
 * de vida do processo - `Application.onCreate()` roda ANTES de qualquer
 * Activity, garantindo que `AppCompatDelegate` já esteja com o idioma
 * certo antes da primeira tela ser inflada.
 *
 * Se o projeto crescer e precisar de mais inicialização em nível de
 * Application no futuro, este é o lugar - mas por enquanto é só isso.
 */
class SmartRemoteApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        LanguageManager.applyPersistedLanguage(this)
    }
}
