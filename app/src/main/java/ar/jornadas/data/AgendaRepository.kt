package ar.jornadas.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

data class EstadoDescarga(val cargando: Boolean = false, val error: Boolean = false)

class AgendaRepository(
    private val api: ProgramaApi,
    private val dao: CarteleraDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    // Ambas notificaciones disparan una lectura transaccional del estado completo.
    val programa = combine(dao.observar(), dao.observarDescarga()) { _, _ ->
        dao.leerPrograma()
    }.distinctUntilChanged()
    private val _descarga = MutableStateFlow(EstadoDescarga())
    val descarga = _descarga.asStateFlow()
    private val automaticaIniciada = AtomicBoolean(false)
    private val peticion = Mutex()

    fun iniciar() {
        if (automaticaIniciada.compareAndSet(false, true)) recargar()
    }

    fun recargar(): Job? {
        if (!peticion.tryLock()) return null
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                _descarga.value = EstadoDescarga(cargando = true)
                val fichas = api.consultar().map {
                    Charla(it.id, it.title, it.track, it.day, it.time, it.room, it.speaker, it.summary)
                }
                dao.reconciliar(fichas)
                _descarga.value = EstadoDescarga()
            } catch (e: CancellationException) {
                _descarga.value = EstadoDescarga()
                throw e
            } catch (e: Exception) {
                _descarga.value = EstadoDescarga(error = true)
            } finally {
                peticion.unlock()
            }
        }
    }

    suspend fun cambiarFavorita(id: String) = dao.alternar(id)
}
