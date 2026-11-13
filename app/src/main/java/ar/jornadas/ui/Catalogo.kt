package ar.jornadas.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ar.jornadas.data.Charla

@Composable
fun Catalogo(model: ProgramaModel, abrir: (String) -> Unit) {
    val dia by model.dia.collectAsStateWithLifecycle()
    val tema by model.tema.collectAsStateWithLifecycle()
    val estado by model.estado.collectAsStateWithLifecycle()
    val fichas = estado.charlas
    val visibles = fichas.filter { (dia == 0 || it.dia == dia) && (tema == "Todos" || it.tema == tema) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Selector(listOf("Ambos días", "Día 1", "Día 2"), dia, model::elegirDia)
                val temas = listOf("Todos", "Android", "Datos", "Calidad")
                Selector(temas, temas.indexOf(tema)) { model.elegirTema(temas[it]) }
                TextButton(onClick = model::recargar, enabled = !estado.descarga.cargando) { Text("Actualizar") }
                if (estado.descarga.cargando) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (estado.descarga.error) {
                    Text(if (estado.guardado?.descargado == true)
                        "No se pudo consultar el programa. Se están utilizando datos guardados."
                    else "No se pudo consultar el programa. No hay una descarga guardada.")
                    OutlinedButton(onClick = model::recargar, enabled = !estado.descarga.cargando) { Text("Reintentar") }
                }
                when {
                    estado.guardado == null -> Text("Leyendo programa guardado…")
                    estado.guardado?.descargado == true && fichas.isEmpty() -> Text("sin charlas programadas")
                    estado.guardado?.descargado == true && visibles.isEmpty() -> Text("No hay charlas para estos filtros.")
                    estado.guardado?.descargado != true && estado.descarga.cargando -> Text("Descargando programa…")
                }
            }
        }
        tarjetas(visibles, abrir)
    }
}

@Composable
private fun Selector(opciones: List<String>, indice: Int, elegir: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        opciones.forEachIndexed { i, texto ->
            FilterChip(selected = i == indice, onClick = { elegir(i) }, label = { Text(texto) })
        }
    }
}

@Composable
fun Grilla(fichas: List<Charla>, abrir: (String) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 12.dp)) {
        tarjetas(fichas, abrir)
    }
}

private fun LazyListScope.tarjetas(fichas: List<Charla>, abrir: (String) -> Unit) {
    items(fichas, key = { it.id }) { ficha ->
        ElevatedCard(onClick = { abrir(ficha.id) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Día ${ficha.dia} · ${ficha.hora} · ${ficha.sala}", style = MaterialTheme.typography.labelMedium)
                Text(ficha.titulo, style = MaterialTheme.typography.titleLarge)
                Text(if (ficha.favorita) "${ficha.tema} · En mi agenda" else ficha.tema)
            }
        }
    }
}
