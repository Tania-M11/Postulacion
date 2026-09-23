package com.example.formulario

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale

/** Formato mm:ss, redondeando hacia arriba para no mostrar 00:00 mientras aún queda tiempo. */
fun formatearTiempo(ms: Long): String {
    val segundos = (ms + 999) / 1000
    return String.format(Locale.ROOT, "%02d:%02d", segundos / 60, segundos % 60)
}

/**
 * Cuenta regresiva que la Activity guarda y restaura con un [Bundle].
 *
 * No es un ViewModel: muere con la Activity. Lo que sobrevive es el Bundle de
 * [guardar], con el tiempo restante, si estaba en marcha y el instante del
 * guardado. Ese instante se toma de [SystemClock.elapsedRealtime], que no cambia
 * si el usuario modifica la hora del teléfono y sigue avanzando con la pantalla
 * apagada. Así, al restaurar, se descuenta también el tiempo que la app pasó en
 * segundo plano o recreándose.
 *
 * La cuenta (datos) y los "tics" que refrescan la pantalla (vista) son cosas
 * separadas: [detenerActualizaciones] solo apaga los tics en onStop; la cuenta
 * sigue siendo correcta porque se calcula con marcas de tiempo, no sumando tics.
 */
class Temporizador(
    private val duracionMs: Long,
    private val alCambiar: (Temporizador) -> Unit,
    private val alTerminar: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var ultimaMarca = 0L
    private var actualizandoPantalla = false

    var restanteMs = duracionMs
        private set
    var enMarcha = false
        private set
    val terminado: Boolean
        get() = restanteMs == 0L

    private val tic = object : Runnable {
        override fun run() {
            descontar()
            programarTic()
        }
    }

    fun iniciar() {
        if (enMarcha || terminado) return
        enMarcha = true
        ultimaMarca = SystemClock.elapsedRealtime()
        programarTic()
        alCambiar(this)
    }

    fun pausar() {
        if (!enMarcha) return
        descontar()
        enMarcha = false
        handler.removeCallbacks(tic)
        alCambiar(this)
    }

    fun reiniciar() {
        handler.removeCallbacks(tic)
        enMarcha = false
        restanteMs = duracionMs
        alCambiar(this)
    }

    /** Se llama en onStart: vuelve a refrescar la pantalla y aplica el tiempo transcurrido. */
    fun reanudarActualizaciones() {
        actualizandoPantalla = true
        descontar()
        programarTic()
    }

    /** Se llama en onStop: no pausa la cuenta, solo deja de refrescar una pantalla que no se ve. */
    fun detenerActualizaciones() {
        actualizandoPantalla = false
        handler.removeCallbacks(tic)
    }

    fun guardar(): Bundle {
        descontar()
        return Bundle().apply {
            putLong(KEY_RESTANTE, restanteMs)
            putBoolean(KEY_EN_MARCHA, enMarcha)
            putLong(KEY_MARCA, ultimaMarca)
        }
    }

    fun restaurar(estado: Bundle?) {
        if (estado == null) return
        handler.removeCallbacks(tic)
        restanteMs = estado.getLong(KEY_RESTANTE, duracionMs).coerceIn(0L, duracionMs)
        enMarcha = estado.getBoolean(KEY_EN_MARCHA) && restanteMs > 0
        // Si el teléfono se reinició, elapsedRealtime volvió a cero y la marca guardada ya no sirve.
        val ahora = SystemClock.elapsedRealtime()
        ultimaMarca = estado.getLong(KEY_MARCA, ahora).takeIf { it <= ahora } ?: ahora
        alCambiar(this)
    }

    private fun programarTic() {
        handler.removeCallbacks(tic)
        if (enMarcha && actualizandoPantalla) handler.postDelayed(tic, INTERVALO_MS)
    }

    private fun descontar() {
        if (enMarcha) {
            val ahora = SystemClock.elapsedRealtime()
            restanteMs = (restanteMs - (ahora - ultimaMarca)).coerceAtLeast(0L)
            ultimaMarca = ahora
            if (restanteMs == 0L) {
                enMarcha = false
                handler.removeCallbacks(tic)
                alCambiar(this)
                alTerminar()
                return
            }
        }
        alCambiar(this)
    }

    companion object {
        private const val INTERVALO_MS = 250L
        const val KEY_RESTANTE = "temporizador_restante_ms"
        const val KEY_EN_MARCHA = "temporizador_en_marcha"
        private const val KEY_MARCA = "temporizador_marca_elapsed"
    }
}
