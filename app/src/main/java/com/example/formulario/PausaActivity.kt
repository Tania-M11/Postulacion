package com.example.formulario

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.formulario.databinding.DialogoPausaBinding

/**
 * Diálogo "Temporizador en pausa" implementado como Activity flotante (tema de diálogo).
 *
 * Un AlertDialog normal NO pausa la Activity: es solo otra ventana de la misma Activity.
 * En cambio, al abrir esta Activity encima, MainActivity pierde el primer plano pero sigue
 * visible detrás, así que Android llama a su onPause() y no a su onStop().
 */
class PausaActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = DialogoPausaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setFinishOnTouchOutside(false)

        val restanteMs = intent.getLongExtra(EXTRA_RESTANTE_MS, 0L)
        binding.tvTiempo.text = getString(R.string.dialogo_pausa_restante, formatearTiempo(restanteMs))

        binding.btnReanudar.setOnClickListener { reanudar(desde = "botón Reanudar") }
        // Atrás hace lo mismo: el diálogo solo tiene una salida.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = reanudar(desde = "botón Atrás")
        })
    }

    private fun reanudar(desde: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ORIGEN, desde))
        finish()
    }

    companion object {
        const val EXTRA_RESTANTE_MS = "restante_ms"
        const val EXTRA_ORIGEN = "origen"
    }
}
