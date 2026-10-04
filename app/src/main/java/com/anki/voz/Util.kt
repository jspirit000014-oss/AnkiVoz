package com.anki.voz

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.Normalizer

object Cfg {
    // Paquetes de AnkiDroid donde la app puede actuar.
    // Mantener igual que android:packageNames en res/xml/accessibility_service_config.xml
    val ANKI_PACKAGES = setOf("com.ichi2.anki", "com.ichi2.anki.debug")

    // Confianza mínima (0..1) de Vosk para ejecutar un comando
    const val MIN_CONF = 0.5
}

object DumpStore {
    val text = MutableStateFlow("")
    @Volatile var count = 0
}

enum class Act(val label: String) {
    SHOW("mostrar"),
    AGAIN("otra vez"),
    HARD("difícil"),
    GOOD("bien"),
    EASY("fácil"),
    READ("leer"),
    PAUSE("pausa"),
    RESUME("reanudar"),
    QUIT("salir")
}

private val MARKS = Regex("\\p{Mn}+")
private val SPACES = Regex("\\s+")

/** minúsculas, sin acentos, espacios colapsados */
fun norm(s: CharSequence?): String {
    if (s == null) return ""
    val d = Normalizer.normalize(s.toString().lowercase().trim(), Normalizer.Form.NFD)
    return MARKS.replace(d, "").replace(SPACES, " ").trim()
}

/** Carga assets/commands.json (mismo formato que el de PC) */
class Commands private constructor(
    private val map: Map<String, Act>,
    val grammarJson: String
) {
    fun match(text: String): Act? = map[norm(text)]

    companion object {
        private val KEYS = mapOf(
            "show" to Act.SHOW,
            "again" to Act.AGAIN,
            "difficult" to Act.HARD,
            "good" to Act.GOOD,
            "easy" to Act.EASY,
            "read" to Act.READ,
            "pause" to Act.PAUSE,
            "unpause" to Act.RESUME,
            "quit" to Act.QUIT
        )

        fun load(ctx: Context): Commands {
            val text = ctx.assets.open("commands.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(text)
            val map = HashMap<String, Act>()
            val grammar = LinkedHashSet<String>()
            for ((key, act) in KEYS) {
                val arr = root.optJSONObject(key)?.optJSONArray("related_words") ?: continue
                for (i in 0 until arr.length()) {
                    val raw = arr.getString(i).lowercase().trim()
                    if (raw.isEmpty()) continue
                    map[norm(raw)] = act
                    grammar.add(raw)
                    grammar.add(norm(raw))
                }
            }
            grammar.add("[unk]")
            return Commands(map, JSONArray(grammar.toList()).toString())
        }
    }
}

/** Copia assets/model a filesDir/model la primera vez (o usa getExternalFilesDir/model si existe) */
object ModelLoader {
    fun prepare(ctx: Context): File? {
        ctx.getExternalFilesDir(null)?.let {
            val d = File(it, "model")
            if (File(d, "am").isDirectory) return d
        }
        val inAssets = (ctx.assets.list("model") ?: emptyArray()).isNotEmpty()
        if (!inAssets) return null

        val dest = File(ctx.filesDir, "model")
        val marker = File(dest, ".ok")
        if (marker.exists()) return dest

        dest.deleteRecursively()
        copy(ctx.assets, "model", dest)
        marker.writeText("ok")
        return dest
    }

    private fun copy(am: AssetManager, path: String, dest: File) {
        val list = am.list(path) ?: emptyArray()
        if (list.isEmpty()) {
            try {
                dest.parentFile?.mkdirs()
                am.open(path).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            } catch (e: IOException) {
                dest.mkdirs() // directorio vacío
            }
        } else {
            dest.mkdirs()
            for (n in list) copy(am, "$path/$n", File(dest, n))
        }
    }
}
