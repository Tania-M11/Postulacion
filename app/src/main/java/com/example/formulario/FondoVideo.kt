package com.example.formulario

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RawRes

/**
 * Video de fondo en bucle y sin sonido (el archivo ni siquiera tiene pista de audio).
 *
 * MediaPlayer no reproduce hacia atrás, así que el efecto de ida y vuelta ya viene en el
 * archivo: el recorrido hacia adelante seguido del mismo recorrido invertido (sin repetir los
 * cuadros de los extremos). Al repetirlo en bucle, avanza y retrocede indefinidamente.
 *
 * Es la "animación" que la Activity detiene en onPause y reanuda en onResume. No es un
 * ViewModel: muere con la Activity; lo que sobrevive es [posicionMs], que la Activity
 * guarda en el Bundle para que tras una rotación el video siga desde el mismo punto.
 */
class FondoVideo(
    private val context: Context,
    private val vista: TextureView,
    @param:RawRes private val video: Int,
) : TextureView.SurfaceTextureListener {

    private var reproductor: MediaPlayer? = null

    /** Mientras se prepara, MediaPlayer no admite pause() ni currentPosition. */
    private var preparado = false
    private var superficie: Surface? = null
    private var quiereReproducir = false
    private var anchoVideo = 0
    private var altoVideo = 0

    var posicionMs = 0
        private set

    val reproduciendo: Boolean
        get() = preparado && reproductor?.isPlaying == true

    init {
        vista.surfaceTextureListener = this
        vista.isOpaque = false // hasta el primer cuadro se ve la imagen de fondo que hay debajo
    }

    fun restaurar(posicion: Int) {
        posicionMs = posicion.coerceAtLeast(0)
    }

    /** onResume. Si la superficie aún no existe, arranca en cuanto esté disponible. */
    fun reproducir() {
        quiereReproducir = true
        val mp = reproductor
        if (mp == null) preparar() else if (preparado && !mp.isPlaying) mp.start()
    }

    /** onPause: detiene el video y recuerda en qué punto quedó. */
    fun pausar() {
        quiereReproducir = false
        val mp = reproductor ?: return
        if (!preparado) return
        if (mp.isPlaying) mp.pause()
        posicionMs = mp.currentPosition
    }

    /** onStop: libera el decodificador, que no se necesita mientras la pantalla no se ve. */
    fun liberar() {
        quiereReproducir = false
        reproductor?.let {
            if (preparado) posicionMs = it.currentPosition
            it.release()
        }
        reproductor = null
        preparado = false
    }

    private fun preparar() {
        val destino = superficie ?: return
        reproductor = MediaPlayer().apply {
            context.resources.openRawResourceFd(video).use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            setSurface(destino)
            isLooping = true
            setVolume(0f, 0f)
            setOnVideoSizeChangedListener { _, ancho, alto ->
                anchoVideo = ancho
                altoVideo = alto
                recortarAlCentro()
            }
            setOnPreparedListener { mp ->
                preparado = true
                mp.seekTo(posicionMs)
                if (quiereReproducir) mp.start()
            }
            prepareAsync()
        }
    }

    /** Igual que scaleType="centerCrop": llena la pantalla sin deformar, recortando lo que sobra. */
    private fun recortarAlCentro() {
        val ancho = vista.width.toFloat()
        val alto = vista.height.toFloat()
        if (anchoVideo == 0 || altoVideo == 0 || ancho == 0f || alto == 0f) return
        val escala = maxOf(ancho / anchoVideo, alto / altoVideo)
        vista.setTransform(
            Matrix().apply {
                setScale(anchoVideo * escala / ancho, altoVideo * escala / alto, ancho / 2, alto / 2)
            },
        )
    }

    override fun onSurfaceTextureAvailable(textura: SurfaceTexture, ancho: Int, alto: Int) {
        superficie = Surface(textura)
        if (quiereReproducir) preparar()
    }

    override fun onSurfaceTextureSizeChanged(textura: SurfaceTexture, ancho: Int, alto: Int) = recortarAlCentro()

    override fun onSurfaceTextureDestroyed(textura: SurfaceTexture): Boolean {
        liberar()
        superficie?.release()
        superficie = null
        return true
    }

    override fun onSurfaceTextureUpdated(textura: SurfaceTexture) = Unit
}
