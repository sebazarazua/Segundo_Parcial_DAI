package ar.jornadas.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import retrofit2.http.GET
import kotlinx.coroutines.flow.Flow

data class FichaRemota(val id: String, val title: String, val track: String, val day: Int,
    val time: String, val room: String, val speaker: String, val summary: String)

interface ProgramaApi {
    @GET("programa") suspend fun consultar(): List<FichaRemota>
}

@Entity(tableName = "charlas")
data class Charla(@PrimaryKey val id: String, val titulo: String, val tema: String, val dia: Int,
    val hora: String, val sala: String, val ponente: String, val resumen: String,
    val favorita: Boolean = false)

@Entity(tableName = "edicion")
data class Edicion(@PrimaryKey val id: Int = 1)

data class ProgramaGuardado(val descargado: Boolean, val charlas: List<Charla>)

@Dao
abstract class CarteleraDao {
    @Query("SELECT * FROM charlas ORDER BY dia, hora, sala")
    abstract fun observar(): Flow<List<Charla>>

    @Query("SELECT EXISTS(SELECT 1 FROM edicion WHERE id = 1)")
    abstract fun observarDescarga(): Flow<Boolean>

    @Query("SELECT * FROM charlas ORDER BY dia, hora, sala")
    abstract suspend fun listar(): List<Charla>

    @Query("SELECT EXISTS(SELECT 1 FROM edicion WHERE id = 1)")
    abstract suspend fun hayDescarga(): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun guardar(fichas: List<Charla>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun guardarEdicion(edicion: Edicion)

    @Query("DELETE FROM charlas WHERE id NOT IN (:vigentes)")
    protected abstract suspend fun retirar(vigentes: List<String>)

    @Transaction
    open suspend fun leerPrograma(): ProgramaGuardado = ProgramaGuardado(hayDescarga(), listar())

    @Transaction
    open suspend fun reconciliar(fichas: List<Charla>) {
        // Se leen las marcas al aplicar la respuesta, dentro de la misma transacción.
        val favoritas = listar().filter { it.favorita }.map { it.id }.toSet()
        retirar(fichas.map { it.id })
        guardar(fichas.map { it.copy(favorita = it.id in favoritas) })
        guardarEdicion(Edicion())
    }

    @Query("UPDATE charlas SET favorita = NOT favorita WHERE id = :id")
    abstract suspend fun alternar(id: String)
}

@Database(entities = [Charla::class, Edicion::class], version = 2, exportSchema = false)
abstract class Archivo : RoomDatabase() {
    abstract fun cartelera(): CarteleraDao

    companion object {
        val MIGRACION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS edicion (id INTEGER NOT NULL PRIMARY KEY)")
                db.execSQL("INSERT INTO edicion (id) SELECT 1 WHERE EXISTS(SELECT 1 FROM charlas)")
            }
        }
    }
}
