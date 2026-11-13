package ar.jornadas.ui

import android.app.Application
import androidx.lifecycle.*
import ar.jornadas.Servicios
import ar.jornadas.data.EstadoDescarga
import ar.jornadas.data.ProgramaGuardado
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class EstadoPrograma(
    val guardado: ProgramaGuardado? = null,
    val descarga: EstadoDescarga = EstadoDescarga()
) {
    val charlas get() = guardado?.charlas.orEmpty()
}

class ProgramaModel(app: Application, private val savedState: SavedStateHandle) : AndroidViewModel(app) {
    private val servicios = Servicios.obtener(app)
    val mesa = servicios.mesa
    private val repo = servicios.agenda
    val estado = combine(repo.programa, repo.descarga) { guardado, descarga ->
        EstadoPrograma(guardado, descarga)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, EstadoPrograma())
    val dia = savedState.getStateFlow("dia", 0)
    val tema = savedState.getStateFlow("tema", "Todos")

    fun elegirDia(dia: Int) { savedState["dia"] = dia }
    fun elegirTema(tema: String) { savedState["tema"] = tema }
    fun iniciar() { repo.iniciar() }
    fun recargar() { repo.recargar() }

    fun marcar(id: String) { viewModelScope.launch { repo.cambiarFavorita(id) } }
}
