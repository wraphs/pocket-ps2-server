package br.ps2ponte

import android.app.Application
import br.ps2ponte.core.Idioma
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // O log do núcleo segue o idioma do celular (a tela já segue pelos recursos values/ e values-pt/)
        Idioma.ingles = Locale.getDefault().language != "pt"
        Estado.iniciar(this)
    }
}
