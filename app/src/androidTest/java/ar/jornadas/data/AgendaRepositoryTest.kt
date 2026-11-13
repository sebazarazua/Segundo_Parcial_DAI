package ar.jornadas.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ar.jornadas.lab.ServicioLocal
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class AgendaRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: Archivo
    private lateinit var scope: CoroutineScope

    @Before fun preparar() {
        db = Room.inMemoryDatabaseBuilder(context, Archivo::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun cerrar() {
        scope.cancel()
        db.close()
    }

    private fun edicion(numero: Int): List<FichaRemota> =
        context.assets.open("programa-$numero.json").bufferedReader().use {
            Gson().fromJson(it, object : TypeToken<List<FichaRemota>>() {}.type)
        }

    private class Api : ProgramaApi {
        var llamadas = 0
        var responder: suspend () -> List<FichaRemota> = { emptyList() }
        override suspend fun consultar(): List<FichaRemota> {
            llamadas++
            return responder()
        }
    }

    private suspend fun AgendaRepository.actualizar() {
        checkNotNull(recargar()).join()
    }

    @Test fun errorInicialReintentoYErrorConDatos() = runBlocking {
        val api = Api()
        val repo = AgendaRepository(api, db.cartelera(), scope)
        api.responder = { throw IOException("sin conexión") }
        repo.actualizar()
        assertTrue(repo.descarga.value.error)
        assertFalse(repo.programa.first().descargado)
        api.responder = { edicion(1) }
        repo.actualizar()
        repo.cambiarFavorita("11")
        val previo = repo.programa.first()
        assertEquals(12, previo.charlas.size)
        assertTrue(previo.descargado)
        api.responder = { throw IOException("sin conexión") }
        repo.actualizar()
        assertEquals(previo, repo.programa.first())
        assertTrue(repo.descarga.value.error)
        assertFalse(repo.descarga.value.cargando)
        api.responder = { edicion(2) }
        repo.actualizar()
        assertFalse(repo.descarga.value.error)
        assertEquals(4, api.llamadas)
    }

    @Test fun reconciliarActualizacionesRetirosYReincorporaciones() = runBlocking {
        val api = Api()
        val repo = AgendaRepository(api, db.cartelera(), scope)
        api.responder = { edicion(1) }
        repo.actualizar()
        repo.cambiarFavorita("11")
        repo.cambiarFavorita("23")
        api.responder = { edicion(2) }
        repeat(2) { repo.actualizar() }
        val actualizada = repo.programa.first().charlas.single { it.id == "11" }
        assertTrue(actualizada.favorita)
        assertEquals("Compose en producción avanzada", actualizada.titulo)
        assertEquals("Sala Puerto", actualizada.sala)
        api.responder = { edicion(3) }
        repo.actualizar()
        val tercera = repo.programa.first().charlas
        assertFalse(tercera.any { it.id == "11" })
        assertTrue(tercera.any { it.id == "90" })
        assertEquals(listOf("23"), tercera.filter { it.favorita }.map { it.id })
        api.responder = { edicion(1) }
        repo.actualizar()
        val regreso = repo.programa.first().charlas
        assertFalse(regreso.single { it.id == "11" }.favorita)
        assertTrue(regreso.single { it.id == "23" }.favorita)
        assertFalse(regreso.any { it.id == "90" })
    }

    @Test fun cambiosDuranteUnaPeticionSeAplicanAlEstadoActual() = runBlocking {
        val api = Api()
        val repo = AgendaRepository(api, db.cartelera(), scope)
        api.responder = { edicion(1) }
        repo.actualizar()
        repo.cambiarFavorita("11")
        val inicio = CompletableDeferred<Unit>()
        val respuesta = CompletableDeferred<List<FichaRemota>>()
        api.responder = { inicio.complete(Unit); respuesta.await() }
        val trabajo = checkNotNull(repo.recargar())
        inicio.await()
        repo.cambiarFavorita("11")
        repo.cambiarFavorita("23")
        respuesta.complete(edicion(2))
        trabajo.join()
        val charlas = repo.programa.first().charlas
        assertFalse(charlas.single { it.id == "11" }.favorita)
        assertTrue(charlas.single { it.id == "23" }.favorita)
        assertEquals("Compose en producción avanzada", charlas.single { it.id == "11" }.titulo)
    }

    @Test fun unaAutomaticaPorRepositorioYExclusionHastaFinalizarOCancelar() = runBlocking {
        val api = Api()
        val repo = AgendaRepository(api, db.cartelera(), scope)
        val respuesta = CompletableDeferred<List<FichaRemota>>()
        api.responder = { respuesta.await() }
        repo.iniciar()
        repeat(5) { repo.iniciar(); assertNull(repo.recargar()) }
        assertEquals(1, api.llamadas)
        respuesta.complete(edicion(1))
        withTimeout(5000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
        repo.iniciar()
        assertEquals(1, api.llamadas)
        api.responder = { throw IOException() }
        repo.actualizar()
        assertTrue(repo.descarga.value.error)
        val pendiente = CompletableDeferred<List<FichaRemota>>()
        api.responder = { pendiente.await() }
        val cancelada = checkNotNull(repo.recargar())
        cancelada.cancelAndJoin()
        assertFalse(repo.descarga.value.cargando)
        api.responder = { edicion(2) }
        repo.actualizar()
        assertFalse(repo.descarga.value.error)
        assertEquals(4, api.llamadas)
    }

    @Test fun observacionNoExponeUnaInstantaneaParcial() = runBlocking {
        val api = Api()
        val repo = AgendaRepository(api, db.cartelera(), scope)
        val estados = mutableListOf<ProgramaGuardado>()
        val notificaciones = Channel<ProgramaGuardado>(Channel.UNLIMITED)
        val observador = launch(start = CoroutineStart.UNDISPATCHED) {
            repo.programa.collect { estados.add(it); notificaciones.send(it) }
        }
        assertEquals(ProgramaGuardado(false, emptyList()), withTimeout(5000) { notificaciones.receive() })
        api.responder = { edicion(1) }
        repo.actualizar()
        withTimeout(5000) {
            while (notificaciones.receive().charlas.size != 12) { }
        }
        api.responder = { edicion(4) }
        repo.actualizar()
        withTimeout(5000) {
            while (notificaciones.receive() != ProgramaGuardado(true, emptyList())) { }
        }
        observador.cancelAndJoin()
        assertTrue(estados.isNotEmpty())
        assertTrue(estados.all {
            (!it.descargado && it.charlas.isEmpty()) ||
                (it.descargado && (it.charlas.isEmpty() || it.charlas.size == 12))
        })
    }

    @Test fun eventosSimultaneosAceptanUnaSolaPeticion() = runBlocking {
        val api = Api()
        val repo = AgendaRepository(api, db.cartelera(), scope)
        val salida = CompletableDeferred<Unit>()
        val respuesta = CompletableDeferred<List<FichaRemota>>()
        api.responder = { respuesta.await() }
        val trabajos = (1..20).map {
            scope.async { salida.await(); repo.recargar() }
        }
        salida.complete(Unit)
        val aceptadas = trabajos.awaitAll().filterNotNull()
        assertEquals(1, aceptadas.size)
        assertEquals(1, api.llamadas)
        respuesta.complete(edicion(1))
        aceptadas.single().join()
        assertEquals(12, repo.programa.first().charlas.size)
    }

    @Test fun vacioValidoPersisteAlReabrirSinServicio() = runBlocking {
        val nombre = "vacio-prueba.db"
        context.deleteDatabase(nombre)
        fun abrir() = Room.databaseBuilder(context, Archivo::class.java, nombre).build()
        var disco = abrir()
        try {
            val api = Api()
            api.responder = { edicion(1) }
            var repo = AgendaRepository(api, disco.cartelera(), scope)
            repo.actualizar()
            repo.cambiarFavorita("11")
            api.responder = { edicion(4) }
            repo.actualizar()
            assertEquals(ProgramaGuardado(true, emptyList()), repo.programa.first())
            disco.close()
            disco = abrir()
            repo = AgendaRepository(api, disco.cartelera(), scope)
            api.responder = { throw IOException() }
            repo.actualizar()
            assertEquals(ProgramaGuardado(true, emptyList()), repo.programa.first())
            assertTrue(repo.descarga.value.error)
        } finally {
            disco.close()
            context.deleteDatabase(nombre)
        }
    }

    @Test fun migracionConservaDatosYReconoceUnaEdicionAnterior() = runBlocking {
        for (conDatos in listOf(false, true)) {
            val nombre = "migracion-prueba.db"
            context.deleteDatabase(nombre)
            val ruta = context.getDatabasePath(nombre)
            ruta.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(ruta, null).use { anterior ->
                anterior.execSQL("CREATE TABLE charlas (id TEXT NOT NULL PRIMARY KEY, titulo TEXT NOT NULL, tema TEXT NOT NULL, dia INTEGER NOT NULL, hora TEXT NOT NULL, sala TEXT NOT NULL, ponente TEXT NOT NULL, resumen TEXT NOT NULL, favorita INTEGER NOT NULL)")
                if (conDatos) anterior.execSQL("INSERT INTO charlas VALUES ('11', 'Anterior', 'Android', 1, '09:00', 'Auditorio', 'Paz', 'Detalle guardado', 1)")
                anterior.version = 1
            }
            val migrada = Room.databaseBuilder(context, Archivo::class.java, nombre)
                .addMigrations(Archivo.MIGRACION_1_2).build()
            try {
                val estado = migrada.cartelera().leerPrograma()
                assertEquals(conDatos, estado.descargado)
                assertEquals(if (conDatos) 1 else 0, estado.charlas.size)
                if (conDatos) {
                    assertTrue(estado.charlas.single().favorita)
                    assertEquals("Detalle guardado", estado.charlas.single().resumen)
                }
            } finally {
                migrada.close()
                context.deleteDatabase(nombre)
            }
        }
    }

    @Test fun servicioRealRespetaDemoraCapturaYMarcasConcurrentes() = runBlocking {
        val mesa = ServicioLocal(context)
        val disponible = mesa.disponible
        val revision = mesa.revision
        val larga = mesa.demoraLarga
        val cliente = OkHttpClient.Builder().addInterceptor(mesa).build()
        val api = Retrofit.Builder().baseUrl("https://programa.invalid/").client(cliente)
            .addConverterFactory(GsonConverterFactory.create()).build().create(ProgramaApi::class.java)
        val repo = AgendaRepository(api, db.cartelera(), scope)
        try {
            mesa.disponible = true
            mesa.revision = 1
            mesa.demoraLarga = false
            repo.actualizar()
            repo.cambiarFavorita("11")
            mesa.revision = 2
            mesa.demoraLarga = true
            val trabajo = checkNotNull(repo.recargar())
            withTimeout(5000) { mesa.llamadas.first { it == 2 } }
            delay(200) // La petición ya capturó la configuración antes de dormir.
            mesa.revision = 3
            mesa.disponible = false
            repo.cambiarFavorita("11")
            repo.cambiarFavorita("23")
            assertNull(repo.recargar())
            trabajo.join()
            val segunda = repo.programa.first()
            assertEquals("Compose en producción avanzada", segunda.charlas.single { it.id == "11" }.titulo)
            assertFalse(segunda.charlas.single { it.id == "11" }.favorita)
            assertTrue(segunda.charlas.single { it.id == "23" }.favorita)
            mesa.demoraLarga = false
            repo.actualizar()
            assertTrue(repo.descarga.value.error)
            assertEquals(segunda, repo.programa.first())
            assertEquals(3, mesa.llamadas.value)
        } finally {
            mesa.disponible = disponible
            mesa.revision = revision
            mesa.demoraLarga = larga
            cliente.dispatcher().executorService().shutdown()
            cliente.connectionPool().evictAll()
        }
    }
}
