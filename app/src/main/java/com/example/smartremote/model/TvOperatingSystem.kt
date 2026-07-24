package com.example.smartremote.model

/**
 * Sistema operacional da Smart TV, quando possível identificar durante a
 * descoberta. Usado futuramente pelo TvManager para escolher o TvController
 * correto (SamsungController, LGController, AndroidTVController, etc).
 */
enum class TvOperatingSystem {
    TIZEN,        // Samsung
    WEBOS,        // LG
    ANDROID_TV,   // Sony, TCL, Philips, Hisense (legado)
    GOOGLE_TV,    // Sony, TCL, Philips, Hisense (atual)
    ROKU_OS,      // Roku / algumas TCL
    FIRE_OS,      // Amazon Fire TV
    VIDAA,        // Hisense
    UNKNOWN
}
