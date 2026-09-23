package com.example.formulario

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.util.Log
import android.util.Patterns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import com.example.formulario.databinding.ActivityMainBinding
import com.example.formulario.databinding.ItemSeccionResumenBinding
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Formulario de postulación en 5 pasos que conserva su estado temporal SIN ViewModel.
 *
 * Responsabilidad de cada callback del ciclo de vida:
 * - onPause: guarda un borrador rápido EN MEMORIA de la edición activa (campo con foco,
 *   cursor y texto), pausa el temporizador y detiene las animaciones (video de fondo y
 *   transiciones entre pasos). onResume deshace eso.
 * - onStop: actualiza el estado temporal completo (Bundle) incluyendo ese borrador, y libera
 *   el reproductor del video.
 * - onSaveInstanceState: entrega ese Bundle al sistema.
 * - onCreate / onRestoreInstanceState: reconstruyen la pantalla desde el Bundle.
 *
 * Todo es estado TEMPORAL: no hay SharedPreferences, archivos ni base de datos. El Bundle
 * sobrevive a la rotación, a "No conservar actividades" y a la muerte del proceso en segundo
 * plano; si el usuario cierra la app a propósito, Android lo descarta y se empieza de cero.
 *
 * Cada evento se escribe en Logcat con la etiqueta "CicloDeVida".
 * El guardado automático de las vistas se desactiva en [configurarPasos] para que lo que
 * se vea restaurado dependa únicamente de este código y no del framework.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var temporizador: Temporizador
    private lateinit var pasos: List<NestedScrollView>
    private lateinit var camposPorPaso: List<List<TextInputEditText>>
    private lateinit var campos: List<TextInputEditText>
    private lateinit var fondoVideo: FondoVideo

    private var pasoActual = 0
    private var enviado = false

    /**
     * Borrador rápido en memoria que toma onPause: campo con foco, cursor, texto y teclado.
     * onStop lo incluye en el estado temporal y onSaveInstanceState lo entrega en el Bundle.
     */
    private var edicionActiva: Bundle? = null

    /** El temporizador corría y lo detuvo onPause (no el usuario); onResume lo reanuda. */
    private var temporizadorPausadoPorOnPause = false

    /**
     * Scroll restaurado de cada paso. Un paso oculto (GONE) no tiene tamaño y descarta
     * scrollTo, así que el valor espera aquí hasta que ese paso se muestre.
     */
    private var scrollPendiente: IntArray? = null

    /** Instantánea tomada en onStop; en API < 28 onSaveInstanceState llega antes y la toma él. */
    private var estadoCapturado: Bundle? = null
    private var selectorFecha: DatePickerDialog? = null

    private val fuenteSemiBold by lazy { checkNotNull(ResourcesCompat.getFont(this, R.font.quicksand_semibold)) }

    private val formatoFecha = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).apply { isLenient = false }

    /** Resultado de PausaActivity; llega antes de onResume. */
    private val dialogoPausa = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultado ->
        val origen = resultado.data?.getStringExtra(PausaActivity.EXTRA_ORIGEN) ?: "sin respuesta"
        registrar("Diálogo cerrado", "$origen → temporizador reanudado")
        temporizador.iniciar()
    }

    // ---------------------------------------------------------------- Ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val guardado = savedInstanceState?.getBundle(KEY_ESTADO)

        // El companion object vive lo mismo que el proceso: si la marca no está, el proceso es nuevo.
        val mismoProceso = procesoEnMarcha
        procesoEnMarcha = true

        // Íconos claros en las barras del sistema: la ilustración queda detrás y es oscura.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fondoVideo = FondoVideo(this, binding.videoFondo, R.raw.fondo_animado)
        aplicarInsets()
        configurarEsmerilado()
        configurarPasos()
        configurarNavegacion()
        configurarFechas()
        configurarControles()

        temporizador = Temporizador(DURACION_MS, ::mostrarTemporizador, ::alAgotarseTiempo)
        if (guardado != null) {
            // El temporizador se restaura aquí (y no en onRestoreInstanceState) porque onStart
            // y onResume lo usan antes de que llegue onRestoreInstanceState.
            temporizadorPausadoPorOnPause = guardado.getBoolean(KEY_TEMPORIZADOR_PAUSADO_POR_ONPAUSE)
            temporizador.restaurar(guardado.getBundle(KEY_TEMPORIZADOR))
            fondoVideo.restaurar(guardado.getInt(KEY_VIDEO_POSICION))
            registrar(
                "onCreate",
                "recreada con Bundle · " + if (mismoProceso) {
                    "mismo proceso; se había destruido por: ${motivoUltimaDestruccion ?: "desconocido"}"
                } else {
                    "PROCESO NUEVO: Android eliminó el proceso en segundo plano y el Bundle sobrevivió"
                },
            )
        } else {
            // Sin Bundle: primera apertura o el usuario cerró la app. El estado temporal no existe.
            mostrarPaso(0, animar = false)
            temporizador.iniciar()
            registrar("onCreate", "inicio limpio: sin Bundle")
        }
        motivoUltimaDestruccion = null
    }

    override fun onStart() {
        super.onStart()
        estadoCapturado = null
        temporizador.reanudarActualizaciones()
        registrar("onStart", "la Activity vuelve a ser visible")
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val estado = savedInstanceState.getBundle(KEY_ESTADO) ?: return
        restaurarFormulario(estado)
        registrar("onRestoreInstanceState", describir(estado))
    }

    override fun onResume() {
        super.onResume()
        val acciones = mutableListOf<String>()

        if (temporizadorPausadoPorOnPause) {
            temporizadorPausadoPorOnPause = false
            temporizador.iniciar()
            acciones += "temporizador reanudado"
        }

        fondoVideo.reproducir()
        acciones += "video de fondo reanudado en ${formatearTiempo(fondoVideo.posicionMs.toLong())}"

        // Si la Activity no se destruyó (diálogo, botón Inicio), la edición activa sigue en memoria.
        edicionActiva?.let {
            restaurarEdicion(it)
            acciones += "edición activa devuelta a ${describirEdicion(it)}"
        }
        edicionActiva = null

        registrar("onResume", acciones.joinToString(" · ").ifEmpty { "en primer plano" })
    }

    /**
     * La Activity deja el primer plano (aunque siga visible). Debe ser rápido:
     * 1. guarda un borrador rápido en memoria del campo en edición activa,
     * 2. pausa el temporizador,
     * 3. detiene las animaciones en curso.
     */
    override fun onPause() {
        super.onPause()

        edicionActiva = capturarEdicionActiva()

        temporizadorPausadoPorOnPause = temporizador.enMarcha
        if (temporizadorPausadoPorOnPause) temporizador.pausar()

        val animaciones = detenerAnimaciones()

        registrar(
            "onPause",
            listOf(
                "borrador rápido en memoria: ${describirEdicion(edicionActiva)}",
                if (temporizadorPausadoPorOnPause) "temporizador pausado" else "temporizador ya estaba detenido",
                "animaciones detenidas: $animaciones",
            ).joinToString(" · "),
        )
    }

    /** Ya no es visible: actualiza el estado temporal completo, con el borrador de onPause incluido. */
    override fun onStop() {
        temporizador.detenerActualizaciones()
        fondoVideo.liberar()
        val estado = capturarEstado()
        estadoCapturado = estado
        registrar(
            "onStop",
            "ya no es visible · reproductor de video liberado · estado temporal actualizado: ${describir(estado)}",
        )
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val estado = estadoCapturado ?: capturarEstado()
        registrar(
            "onSaveInstanceState",
            "Bundle entregado al sistema: ${estado.size()} claves, ${tamano(estado)} · ${describir(estado)}",
        )
        outState.putBundle(KEY_ESTADO, estado)
    }

    override fun onDestroy() {
        selectorFecha?.dismiss()
        temporizador.detenerActualizaciones()
        fondoVideo.liberar()
        val motivo = when {
            isChangingConfigurations -> "cambio de configuración (rotación)"
            isFinishing -> "finish(): el usuario cerró la pantalla"
            else -> "el sistema la destruyó estando detenida («No conservar actividades»)"
        }
        motivoUltimaDestruccion = motivo
        registrar("onDestroy", motivo)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Guardado y restauración

    private fun capturarEdicionActiva(): Bundle? {
        val campo = currentFocus as? TextInputEditText ?: return null
        if (campo !in campos) return null
        return Bundle().apply {
            putString(KEY_FOCO, clave(campo))
            putInt(KEY_CURSOR_INICIO, campo.selectionStart)
            putInt(KEY_CURSOR_FIN, campo.selectionEnd)
            putString(KEY_TEXTO_EN_EDICION, campo.text?.toString().orEmpty())
            putBoolean(KEY_TECLADO, tecladoVisible())
        }
    }

    private fun capturarEstado() = Bundle().apply {
        putInt(KEY_PASO, pasoActual)
        putBoolean(KEY_ENVIADO, enviado)
        putBundle(KEY_CAMPOS, Bundle().apply {
            campos.forEach { putString(clave(it), it.text?.toString().orEmpty()) }
        })
        putBundle(KEY_EDICION, edicionActiva ?: capturarEdicionActiva())
        // Un paso que aún no se volvió a mostrar conserva el scroll pendiente de la restauración anterior.
        putIntArray(
            KEY_SCROLL,
            IntArray(pasos.size) { i -> scrollPendiente?.getOrNull(i)?.takeIf { it > 0 } ?: pasos[i].scrollY },
        )
        putBoolean(KEY_TEMPORIZADOR_PAUSADO_POR_ONPAUSE, temporizadorPausadoPorOnPause)
        putBundle(KEY_TEMPORIZADOR, temporizador.guardar())
        putInt(KEY_VIDEO_POSICION, fondoVideo.posicionMs)
    }

    private fun restaurarFormulario(estado: Bundle) {
        enviado = estado.getBoolean(KEY_ENVIADO)
        estado.getBundle(KEY_CAMPOS)?.let { textos ->
            campos.forEach { it.setText(textos.getString(clave(it)).orEmpty()) }
        }
        // mostrarPaso aplica el scroll pendiente del paso visible; el resto espera a mostrarse.
        scrollPendiente = estado.getIntArray(KEY_SCROLL)
        mostrarPaso(estado.getInt(KEY_PASO), animar = false)

        estado.getBundle(KEY_EDICION)?.let(::restaurarEdicion)

        Snackbar.make(
            binding.root,
            getString(
                R.string.estado_restaurado,
                pasoActual + 1,
                TOTAL_PASOS,
                campos.count { !it.text.isNullOrBlank() }.let {
                    resources.getQuantityString(R.plurals.campos_con_datos, it, it)
                },
                formatearTiempo(temporizador.restanteMs),
            ),
            Snackbar.LENGTH_LONG,
        ).setAnchorView(binding.barraNavegacion).show()
    }

    private fun restaurarEdicion(edicion: Bundle) {
        val claveFoco = edicion.getString(KEY_FOCO) ?: return
        // Solo se puede enfocar un campo del paso visible.
        val campo = camposPorPaso[pasoActual].firstOrNull { clave(it) == claveFoco } ?: return

        edicion.getString(KEY_TEXTO_EN_EDICION)?.let { texto ->
            if (campo.text?.toString() != texto) campo.setText(texto)
        }
        val largo = campo.length()
        campo.requestFocus()
        campo.setSelection(
            edicion.getInt(KEY_CURSOR_INICIO).coerceIn(0, largo),
            edicion.getInt(KEY_CURSOR_FIN).coerceIn(0, largo),
        )
        if (edicion.getBoolean(KEY_TECLADO)) {
            campo.post {
                WindowCompat.getInsetsController(window, campo).show(WindowInsetsCompat.Type.ime())
            }
        }
    }

    /** Tamaño del Bundle serializado, el mismo formato que usa el sistema para guardarlo. */
    private fun tamano(bundle: Bundle): String {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            String.format(Locale.ROOT, "%.1f KB", parcel.dataSize() / 1024f)
        } finally {
            parcel.recycle()
        }
    }

    private fun tecladoVisible() =
        ViewCompat.getRootWindowInsets(binding.root)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    /** Nombre del id en XML (p. ej. "etCorreo"); es estable aunque el proceso muera. */
    private fun clave(campo: TextInputEditText) = resources.getResourceEntryName(campo.id)

    /** Pausa el video de fondo y termina de golpe las transiciones en curso; describe lo que hizo. */
    private fun detenerAnimaciones(): String {
        val estabaReproduciendo = fondoVideo.reproduciendo
        fondoVideo.pausar()
        val video = if (estabaReproduciendo) {
            "video de fondo pausado en ${formatearTiempo(fondoVideo.posicionMs.toLong())}"
        } else {
            "video de fondo ya estaba detenido"
        }
        var detenidas = 0
        pasos.forEach { paso ->
            if (paso.alpha < 1f || paso.translationX != 0f) detenidas++
            paso.animate().cancel()
            paso.alpha = 1f
            paso.translationX = 0f
        }
        binding.progreso.setProgressCompat(pasoActual + 1, false)
        return "$video, transiciones cortadas: $detenidas"
    }

    // ---------------------------------------------------------------- Configuración de la UI

    private fun aplicarInsets() {
        // El fondo ocupa toda la pantalla; solo el contenido se aparta de barras, recortes y teclado.
        ViewCompat.setOnApplyWindowInsetsListener(binding.contenido) { vista, insets ->
            val barras = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val teclado = insets.getInsets(WindowInsetsCompat.Type.ime())
            vista.updatePadding(
                left = barras.left,
                top = barras.top,
                right = barras.right,
                bottom = maxOf(barras.bottom, teclado.bottom),
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * Vidrio esmerilado del panel de pasos: detrás de los campos va la ilustración desenfocada
     * y alineada con el fondo, así el texto se lee sobre un color parejo y no sobre los
     * contornos de las montañas.
     */
    private fun configurarEsmerilado() = with(binding) {
        val desenfoqueNativo = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        // Una imagen muy reducida y luego ampliada ya se ve borrosa; desde Android 12 además
        // se aplica un desenfoque real con RenderEffect.
        val opciones = BitmapFactory.Options().apply { inSampleSize = if (desenfoqueNativo) 4 else 16 }
        val reducida = BitmapFactory.decodeResource(resources, R.drawable.fondo, opciones) ?: return@with
        ivFondoEsmerilado.setImageBitmap(reducida)
        if (desenfoqueNativo) {
            val radio = RADIO_DESENFOQUE_DP * resources.displayMetrics.density
            ivFondoEsmerilado.setRenderEffect(RenderEffect.createBlurEffect(radio, radio, Shader.TileMode.CLAMP))
        }
        // El fondo y el panel cambian de tamaño o posición al rotar o al abrir el teclado.
        val alinear = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> alinearEsmerilado(reducida) }
        ivFondo.addOnLayoutChangeListener(alinear)
        panelPasos.addOnLayoutChangeListener(alinear)
    }

    /** Coloca la copia desenfocada exactamente donde está la ilustración de fondo. */
    private fun alinearEsmerilado(reducida: Bitmap) = with(binding) {
        val original = ivFondo.drawable ?: return@with
        val posFondo = IntArray(2).also(ivFondo::getLocationInWindow)
        val posPanel = IntArray(2).also(panelPasos::getLocationInWindow)
        ivFondoEsmerilado.imageMatrix = Matrix(ivFondo.imageMatrix).apply {
            preScale(
                original.intrinsicWidth.toFloat() / reducida.width,
                original.intrinsicHeight.toFloat() / reducida.height,
            )
            postTranslate((posFondo[0] - posPanel[0]).toFloat(), (posFondo[1] - posPanel[1]).toFloat())
        }
    }

    private fun configurarPasos() = with(binding) {
        pasos = listOf(paso1.root, paso2.root, paso3.root, paso4.root, paso5.root)
        // El contenido que se desplaza no se sale de las esquinas redondeadas del panel de vidrio.
        panelPasos.clipToOutline = true
        camposPorPaso = listOf(
            listOf(paso1.etNombre, paso1.etCorreo, paso1.etTelefono, paso1.etCiudad, paso1.etFechaNacimiento),
            listOf(
                paso2.etCargoAspira, paso2.etProfesion, paso2.etAniosExperiencia,
                paso2.etHabilidades, paso2.etPerfilProfesional,
            ),
            listOf(paso3.etEmpresa, paso3.etCargoEmpresa, paso3.etFechaInicio, paso3.etFechaFin, paso3.etFunciones),
            listOf(paso4.etMotivoEmpresa, paso4.etAporte, paso4.etObjetivos),
            emptyList(),
        )
        campos = camposPorPaso.flatten()

        // Sin esto, EditText y ScrollView se guardarían solos y no se vería el mecanismo manual.
        pasos.forEach { it.isSaveEnabled = false }
        campos.forEach { campo ->
            campo.isSaveEnabled = false
            contenedor(campo).isSaveEnabled = false
            campo.doAfterTextChanged { contenedor(campo).error = null }
        }

        paso5.seccionPersonal.configurarSeccion(R.string.titulo_paso_personal, paso = 0)
        paso5.seccionProfesional.configurarSeccion(R.string.titulo_paso_profesional, paso = 1)
        paso5.seccionExperiencia.configurarSeccion(R.string.titulo_paso_experiencia, paso = 2)
        paso5.seccionMotivacion.configurarSeccion(R.string.titulo_paso_motivacion, paso = 3)
        paso5.btnNuevaPostulacion.setOnClickListener { nuevaPostulacion() }
    }

    private fun ItemSeccionResumenBinding.configurarSeccion(@StringRes titulo: Int, paso: Int) {
        tvTituloSeccion.setText(titulo)
        btnEditar.setOnClickListener { irAPaso(paso) }
    }

    private fun configurarNavegacion() {
        binding.btnAnterior.setOnClickListener { irAPaso(pasoActual - 1) }
        binding.btnSiguiente.setOnClickListener {
            if (pasoActual == ULTIMO_PASO) {
                enviarPostulacion()
            } else {
                val invalido = validarPaso(pasoActual)
                if (invalido == null) irAPaso(pasoActual + 1) else invalido.requestFocus()
            }
        }
    }

    /** Switch del temporizador. */
    private fun configurarControles() = with(binding) {
        // Se usa click (no checked-change) para reaccionar solo al usuario y no a los cambios del código.
        swTemporizador.setOnClickListener {
            temporizadorPausadoPorOnPause = false
            if (swTemporizador.isChecked) {
                if (temporizador.terminado) temporizador.reiniciar()
                temporizador.iniciar()
            } else {
                temporizador.pausar()
                abrirDialogoPausa()
            }
        }
    }

    private fun abrirDialogoPausa() {
        registrar("Switch del temporizador", "apagado por el usuario → se abre PausaActivity")
        dialogoPausa.launch(
            Intent(this, PausaActivity::class.java)
                .putExtra(PausaActivity.EXTRA_RESTANTE_MS, temporizador.restanteMs),
        )
    }

    private fun configurarFechas() = with(binding) {
        paso1.tilFechaNacimiento.setEndIconOnClickListener { abrirSelectorFecha(paso1.etFechaNacimiento) }
        paso3.tilFechaInicio.setEndIconOnClickListener { abrirSelectorFecha(paso3.etFechaInicio) }
        paso3.tilFechaFin.setEndIconOnClickListener { abrirSelectorFecha(paso3.etFechaFin) }

        listOf(paso1.etFechaNacimiento, paso3.etFechaInicio, paso3.etFechaFin).forEach { campo ->
            campo.addTextChangedListener(formatearFechaAutomaticamente())
        }
    }

    /**
     * TextWatcher que inserta "/" automáticamente: después de 2 dígitos (día) y después de 2 más (mes).
     * Limita el largo a 10 caracteres. Si el texto no corresponde a un formato de fecha válido,
     * deja de formatear (p. ej. si el usuario escribe letras).
     */
    private fun formatearFechaAutomaticamente(): TextWatcher = object : TextWatcher {
        private var isFormatting = false

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(editable: Editable?) {
            if (isFormatting || editable == null) return

            val texto = editable.toString()

            // Extraer solo los dígitos y reconstruir con "/"
            val digitos = texto.filter { Character.isDigit(it) }

            if (digitos.isEmpty()) return

            isFormatting = true

            val formateado = StringBuilder()
            for (i in digitos.indices) {
                if (i == 2 || i == 4) formateado.append("/")
                formateado.append(digitos[i])
                if (formateado.length >= 10) break
            }

            val nuevoTexto = formateado.toString()
            if (texto != nuevoTexto) {
                editable.replace(0, texto.length, nuevoTexto)
            }

            isFormatting = false
        }
    }

    private fun abrirSelectorFecha(campo: TextInputEditText) {
        val calendario = Calendar.getInstance()
        leerFecha(campo.text?.toString().orEmpty())?.let { calendario.time = it }
        selectorFecha?.dismiss()
        selectorFecha = DatePickerDialog(
            this,
            R.style.Theme_Formulario_Calendario,
            { _, anio, mes, dia ->
                campo.setText(String.format(Locale.ROOT, "%02d/%02d/%04d", dia, mes + 1, anio))
                campo.setSelection(campo.length())
            },
            calendario.get(Calendar.YEAR),
            calendario.get(Calendar.MONTH),
            calendario.get(Calendar.DAY_OF_MONTH),
        ).apply {
            datePicker.maxDate = System.currentTimeMillis()
            show()
        }
    }

    // ---------------------------------------------------------------- Navegación entre pasos

    private fun irAPaso(indice: Int) {
        // Quita el foco del campo actual para no dejar el teclado abierto sobre otro paso.
        binding.contenido.requestFocus()
        WindowCompat.getInsetsController(window, binding.contenido).hide(WindowInsetsCompat.Type.ime())
        mostrarPaso(indice, animar = true)
    }

    private fun mostrarPaso(indice: Int, animar: Boolean) {
        val anterior = pasoActual
        pasoActual = indice.coerceIn(0, ULTIMO_PASO)
        pasos.forEachIndexed { i, paso -> paso.isVisible = i == pasoActual }

        scrollPendiente?.let { pendientes ->
            val y = pendientes.getOrNull(pasoActual) ?: 0
            if (y > 0) {
                pendientes[pasoActual] = 0
                // Se aplica después del layout, cuando el paso ya tiene tamaño.
                pasos[pasoActual].let { paso -> paso.post { paso.scrollTo(0, y) } }
            }
        }

        if (animar && anterior != pasoActual) {
            val direccion = if (pasoActual > anterior) 1 else -1
            pasos[pasoActual].apply {
                alpha = 0f
                translationX = direccion * DESPLAZAMIENTO_TRANSICION_DP * resources.displayMetrics.density
                animate().alpha(1f).translationX(0f).setDuration(DURACION_TRANSICION_MS).start()
            }
        }

        binding.tvPaso.text = getString(R.string.paso_de, pasoActual + 1, TOTAL_PASOS)
        binding.tvNombrePaso.setText(TITULOS[pasoActual])
        binding.progreso.setProgressCompat(pasoActual + 1, animar)

        val esUltimo = pasoActual == ULTIMO_PASO
        binding.btnSiguiente.setText(if (esUltimo) R.string.enviar else R.string.siguiente)
        binding.btnSiguiente.setIconResource(if (esUltimo) R.drawable.ic_send else R.drawable.ic_arrow_forward)
        if (esUltimo) actualizarResumen()
        actualizarEstadoEnvio()
    }

    // ---------------------------------------------------------------- Validación

    /** Marca los errores del paso y devuelve el primer campo inválido, o null si todo está bien. */
    private fun validarPaso(indice: Int): TextInputEditText? {
        var primerInvalido: TextInputEditText? = null
        camposPorPaso[indice].forEach { campo ->
            val error = errorDe(campo)
            contenedor(campo).error = error?.let(::getString)
            if (error != null && primerInvalido == null) primerInvalido = campo
        }
        return primerInvalido
    }

    @StringRes
    private fun errorDe(campo: TextInputEditText): Int? {
        val texto = campo.text?.toString().orEmpty().trim()
        val p = binding
        if (campo == p.paso3.etFechaFin) {
            return if (texto.isEmpty()) null else errorFechaPasada(texto) ?: errorFechaFin(texto)
        }
        if (texto.isEmpty()) return R.string.error_requerido
        return when (campo) {
            p.paso1.etCorreo ->
                R.string.error_correo.takeUnless { Patterns.EMAIL_ADDRESS.matcher(texto).matches() }
            p.paso1.etTelefono -> R.string.error_telefono.takeUnless { REGEX_TELEFONO.matches(texto) }
            p.paso1.etFechaNacimiento, p.paso3.etFechaInicio -> errorFechaPasada(texto)
            p.paso2.etAniosExperiencia -> R.string.error_anios.takeUnless { texto.toIntOrNull() in 0..60 }
            else -> null
        }
    }

    private fun errorFechaPasada(texto: String): Int? {
        val fecha = leerFecha(texto) ?: return R.string.error_fecha
        return R.string.error_fecha_futura.takeIf { fecha.after(Date()) }
    }

    private fun errorFechaFin(texto: String): Int? {
        val inicio = leerFecha(binding.paso3.etFechaInicio.text?.toString().orEmpty()) ?: return null
        val fin = leerFecha(texto) ?: return R.string.error_fecha
        return R.string.error_fecha_fin.takeIf { fin.before(inicio) }
    }

    private fun leerFecha(texto: String): Date? {
        if (!REGEX_FECHA.matches(texto.trim())) return null
        return try {
            formatoFecha.parse(texto.trim())
        } catch (_: ParseException) {
            null
        }
    }

    private fun contenedor(campo: TextInputEditText): TextInputLayout =
        generateSequence(campo.parent) { it.parent }.filterIsInstance<TextInputLayout>().first()

    // ---------------------------------------------------------------- Revisión y envío

    private fun actualizarResumen() = with(binding) {
        paso5.seccionPersonal.tvContenido.text = resumen(
            R.string.campo_nombre to paso1.etNombre,
            R.string.campo_correo to paso1.etCorreo,
            R.string.campo_telefono to paso1.etTelefono,
            R.string.campo_ciudad to paso1.etCiudad,
            R.string.campo_fecha_nacimiento to paso1.etFechaNacimiento,
        )
        paso5.seccionProfesional.tvContenido.text = resumen(
            R.string.campo_cargo_aspira to paso2.etCargoAspira,
            R.string.campo_profesion to paso2.etProfesion,
            R.string.campo_anios to paso2.etAniosExperiencia,
            R.string.campo_habilidades to paso2.etHabilidades,
            R.string.campo_perfil to paso2.etPerfilProfesional,
        )
        paso5.seccionExperiencia.tvContenido.text = resumen(
            R.string.campo_empresa to paso3.etEmpresa,
            R.string.campo_cargo_empresa to paso3.etCargoEmpresa,
            R.string.campo_fecha_inicio to paso3.etFechaInicio,
            R.string.campo_fecha_fin to paso3.etFechaFin,
            R.string.campo_funciones to paso3.etFunciones,
        )
        paso5.seccionMotivacion.tvContenido.text = resumen(
            R.string.campo_motivo to paso4.etMotivoEmpresa,
            R.string.campo_aporte to paso4.etAporte,
            R.string.campo_objetivos to paso4.etObjetivos,
        )
    }

    private fun resumen(vararg filas: Pair<Int, TextInputEditText>) = buildSpannedString {
        filas.forEachIndexed { i, (etiqueta, campo) ->
            if (i > 0) append("\n\n")
            inSpans(FuenteSpan(fuenteSemiBold)) { append(getString(etiqueta)) }
            append("\n")
            val valor = campo.text?.toString()?.trim().orEmpty()
            append(
                when {
                    valor.isNotEmpty() -> valor
                    campo == binding.paso3.etFechaFin -> getString(R.string.actualidad)
                    else -> getString(R.string.sin_dato)
                },
            )
        }
    }

    private fun enviarPostulacion() {
        for (indice in 0 until ULTIMO_PASO) {
            val invalido = validarPaso(indice) ?: continue
            irAPaso(indice)
            invalido.requestFocus()
            Snackbar.make(binding.root, getString(R.string.aviso_revisar_paso, indice + 1), Snackbar.LENGTH_LONG)
                .setAnchorView(binding.barraNavegacion)
                .show()
            return
        }
        // Simulación: no hay servidor, solo se marca como enviada y se detiene el temporizador.
        enviado = true
        temporizador.pausar()
        actualizarEstadoEnvio()
        binding.paso5.root.smoothScrollTo(0, 0)
        registrar("Envío simulado", "postulación completa")
    }

    private fun nuevaPostulacion() {
        enviado = false
        temporizadorPausadoPorOnPause = false
        campos.forEach {
            it.text?.clear()
            contenedor(it).error = null
        }
        pasos.forEach { it.scrollTo(0, 0) }
        scrollPendiente = null
        temporizador.reiniciar()
        temporizador.iniciar()
        irAPaso(0)
    }

    private fun actualizarEstadoEnvio() = with(binding) {
        paso5.cardEnviado.isVisible = enviado
        btnAnterior.isEnabled = pasoActual > 0 && !enviado
        btnSiguiente.isEnabled = !enviado
        listOf(paso5.seccionPersonal, paso5.seccionProfesional, paso5.seccionExperiencia, paso5.seccionMotivacion)
            .forEach { it.btnEditar.isVisible = !enviado }
        if (::temporizador.isInitialized) mostrarTemporizador(temporizador)
    }

    // ---------------------------------------------------------------- Temporizador

    private fun mostrarTemporizador(t: Temporizador) = with(binding) {
        tvTemporizador.text = formatearTiempo(t.restanteMs)
        tvTemporizador.setTextColor(
            MaterialColors.getColor(
                tvTemporizador,
                if (t.restanteMs <= AVISO_MS) androidx.appcompat.R.attr.colorError
                else com.google.android.material.R.attr.colorOnSurface,
            ),
        )
        tvEtiquetaTemporizador.setText(
            when {
                enviado -> R.string.temporizador_detenido
                t.terminado -> R.string.tiempo_agotado
                t.enMarcha -> R.string.tiempo_restante
                temporizadorPausadoPorOnPause -> R.string.temporizador_pausa_onpause
                else -> R.string.temporizador_pausado
            },
        )
        swTemporizador.isChecked = t.enMarcha
        swTemporizador.isEnabled = !enviado
    }

    private fun alAgotarseTiempo() {
        Snackbar.make(binding.root, R.string.aviso_tiempo_agotado, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.barraNavegacion)
            .setAction(R.string.reiniciar) {
                temporizador.reiniciar()
                temporizador.iniciar()
            }
            .show()
    }

    // ---------------------------------------------------------------- Registro del ciclo de vida

    /** Cada evento del ciclo de vida queda en Logcat (filtro: CicloDeVida). */
    private fun registrar(evento: String, detalle: String = "") {
        Log.i(TAG, if (detalle.isEmpty()) evento else "$evento → $detalle")
    }

    private fun describirEdicion(edicion: Bundle?): String {
        edicion ?: return "ninguna (sin campo enfocado)"
        val texto = edicion.getString(KEY_TEXTO_EN_EDICION).orEmpty()
        return "${edicion.getString(KEY_FOCO)} cursor " +
            "${edicion.getInt(KEY_CURSOR_INICIO)}..${edicion.getInt(KEY_CURSOR_FIN)} (${texto.length} car.)"
    }

    private fun describir(estado: Bundle): String {
        val llenos = estado.getBundle(KEY_CAMPOS)
            ?.let { textos -> textos.keySet().count { !textos.getString(it).isNullOrBlank() } } ?: 0
        val tiempo = estado.getBundle(KEY_TEMPORIZADOR)?.let {
            formatearTiempo(it.getLong(Temporizador.KEY_RESTANTE)) +
                if (it.getBoolean(Temporizador.KEY_EN_MARCHA)) " en marcha" else " en pausa"
        } ?: "—"
        return "paso ${estado.getInt(KEY_PASO) + 1}, $llenos campos con datos, " +
            "edición: ${describirEdicion(estado.getBundle(KEY_EDICION))}, temporizador $tiempo, " +
            "video en ${formatearTiempo(estado.getInt(KEY_VIDEO_POSICION).toLong())}"
    }

    private companion object {
        const val TAG = "CicloDeVida"

        /** Vive mientras viva el proceso; al recrearse sirve para saber si el proceso murió. */
        var procesoEnMarcha = false
        var motivoUltimaDestruccion: String? = null

        const val TOTAL_PASOS = 5
        const val ULTIMO_PASO = TOTAL_PASOS - 1
        val TITULOS = intArrayOf(
            R.string.titulo_paso_personal,
            R.string.titulo_paso_profesional,
            R.string.titulo_paso_experiencia,
            R.string.titulo_paso_motivacion,
            R.string.titulo_paso_revision,
        )
        const val DURACION_TRANSICION_MS = 250L
        const val DESPLAZAMIENTO_TRANSICION_DP = 32
        const val RADIO_DESENFOQUE_DP = 18

        const val DURACION_MS = 10 * 60 * 1000L
        const val AVISO_MS = 60 * 1000L


        val REGEX_TELEFONO = Regex("""^\+?[0-9 ]{7,15}$""")
        val REGEX_FECHA = Regex("""^\d{2}/\d{2}/\d{4}$""")

        // Claves del Bundle
        const val KEY_ESTADO = "estado_formulario"
        const val KEY_PASO = "paso_actual"
        const val KEY_ENVIADO = "enviado"
        const val KEY_CAMPOS = "campos"
        const val KEY_EDICION = "edicion_activa"
        const val KEY_FOCO = "foco_campo"
        const val KEY_CURSOR_INICIO = "cursor_inicio"
        const val KEY_CURSOR_FIN = "cursor_fin"
        const val KEY_TEXTO_EN_EDICION = "texto_en_edicion"
        const val KEY_TECLADO = "teclado_visible"
        const val KEY_SCROLL = "scroll_por_paso"
        const val KEY_TEMPORIZADOR = "temporizador"
        const val KEY_TEMPORIZADOR_PAUSADO_POR_ONPAUSE = "temporizador_pausado_por_onpause"
        const val KEY_VIDEO_POSICION = "video_posicion_ms"
    }
}
