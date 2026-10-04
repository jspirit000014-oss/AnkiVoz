package com.anki.voz

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Servicio de accesibilidad limitado a AnkiDroid:
 *  - declarado solo para los paquetes de Cfg.ANKI_PACKAGES (ver XML)
 *  - además, cada acción comprueba que la ventana activa sea AnkiDroid
 */
class AnkiAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AnkiAccessibilityService? = null

        fun isAnkiActive(): Boolean {
            val pkg = instance?.activePackage() ?: return false
            return pkg in Cfg.ANKI_PACKAGES
        }

        private val L_SHOW = setOf("mostrar respuesta", "mostrar la respuesta", "show answer")
        private val L_AGAIN = setOf("otra vez", "de nuevo", "again")
        private val L_HARD = setOf("dificil", "hard")
        private val L_GOOD = setOf("bien", "good")
        private val L_EASY = setOf("facil", "easy")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no se procesan eventos */ }
    override fun onInterrupt() {}

    // ───────────────────────── API usada por ListenService ─────────────────────────

    private fun activePackage(): String? {
        val root = rootInActiveWindow ?: return null
        val p = root.packageName?.toString()
        recycle(root)
        return p
    }

    fun perform(act: Act): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val pkg = root.packageName?.toString() ?: return false
            if (pkg !in Cfg.ANKI_PACKAGES) return false
            return when (act) {
                Act.SHOW -> clickFlip(root)
                Act.AGAIN -> clickEase(root, 1, "again_button", L_AGAIN)
                Act.HARD -> clickEase(root, 2, "hard_button", L_HARD)
                Act.GOOD -> clickEase(root, 3, "good_button", L_GOOD)
                Act.EASY -> clickEase(root, 4, "easy_button", L_EASY)
                else -> false
            }
        } finally {
            recycle(root)
        }
    }

    /** Texto de la tarjeta (contenido del WebView) para leerlo con TTS */
    fun readCardText(): String? {
        val root = rootInActiveWindow ?: return null
        try {
            val pkg = root.packageName?.toString() ?: return null
            if (pkg !in Cfg.ANKI_PACKAGES) return null
            val sb = StringBuilder()
            collectWebText(root, false, sb, 0)
            val t = sb.toString().trim()
            return if (t.isEmpty()) null else t.take(1500)
        } finally {
            recycle(root)
        }
    }

    // ───────────────────────── Diagnóstico ─────────────────────────

    /** Vuelca el árbol de vistas de AnkiDroid (sin entrar al WebView) tras un retraso */
    fun dumpLater(delayMs: Long = 6000) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val n = DumpStore.count + 1
            DumpStore.count = n
            DumpStore.text.value = DumpStore.text.value + "\n--- volcado $n ---\n" + dumpTree()
        }, delayMs)
    }

    private fun dumpTree(): String {
        val root = rootInActiveWindow ?: return "Sin ventana activa visible para el servicio (¿AnkiDroid en pantalla?)"
        if (root.packageName?.toString() !in Cfg.ANKI_PACKAGES) {
            recycle(root)
            return "Ignorado: la ventana activa no es AnkiDroid"
        }
        val sb = StringBuilder("paquete=${root.packageName}\n")
        dumpNode(root, 0, sb)
        recycle(root)
        return sb.toString().take(30000)
    }

    private fun dumpNode(n: AccessibilityNodeInfo?, d: Int, sb: StringBuilder) {
        if (n == null || d > 30 || sb.length > 30000) return
        sb.append("  ".repeat(d))
            .append(n.className?.toString()?.substringAfterLast('.'))
            .append(" id=").append(n.viewIdResourceName?.substringAfter(":id/"))
            .append(" t=").append(n.text)
            .append(" d=").append(n.contentDescription)
            .append(if (n.isClickable) " [click]" else "")
            .append(if (!n.isVisibleToUser) " [oculto]" else "")
            .append('\n')
        if (n.className?.toString() == "android.webkit.WebView") return
        for (i in 0 until n.childCount) dumpNode(n.getChild(i), d + 1, sb)
    }

    // ───────────────────────── Internos ─────────────────────────

    private fun clickFlip(root: AccessibilityNodeInfo): Boolean {
        // nuevo reviewer: show_answer_button | reviewer clásico: flashcard_layout_flip
        for (id in listOf("show_answer_button", "flashcard_layout_flip")) {
            byId(root, id)?.let { if (clickUp(it)) return true }
        }
        return clickByText(root, L_SHOW)
    }

    private fun clickEase(root: AccessibilityNodeInfo, n: Int, newId: String, labels: Set<String>): Boolean {
        // 0) nuevo reviewer: IDs con nombre propio (good_button confirmado; los demás siguen el mismo patrón, sin verificar)
        byId(root, newId)?.let { if (clickUp(it)) return true }
        // 1) por texto del botón (correcto también con 3 botones: Otra vez/Bien/Fácil)
        if (clickByText(root, labels)) return true
        // 2) reviewer clásico por ID, solo si están los 4 botones
        val ids = (1..4).map { byId(root, "flashcard_layout_ease$it") }
        if (ids.all { it != null }) return clickUp(ids[n - 1]!!)
        return false
    }

    private fun byId(root: AccessibilityNodeInfo, name: String): AccessibilityNodeInfo? {
        val full = "${root.packageName}:id/$name"
        return root.findAccessibilityNodeInfosByViewId(full)?.firstOrNull { it.isVisibleToUser }
    }

    private fun clickByText(root: AccessibilityNodeInfo, labels: Set<String>): Boolean {
        val node = find(root, 0) { n ->
            norm(n.text) in labels || norm(n.contentDescription) in labels
        } ?: return false
        return clickUp(node)
    }

    private fun find(
        n: AccessibilityNodeInfo?,
        depth: Int,
        pred: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (n == null || depth > 40) return null
        // no entrar al WebView: el contenido de la tarjeta podría contener "Bien", etc.
        if (n.className?.toString() == "android.webkit.WebView") return null
        if (n.isVisibleToUser && pred(n)) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val r = find(c, depth + 1, pred)
            if (r != null) return r
        }
        return null
    }

    private fun clickUp(start: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = start
        var i = 0
        while (n != null && i < 6) {
            if (n.isClickable && n.isEnabled) {
                return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            n = n.parent
            i++
        }
        return false
    }

    private fun collectWebText(n: AccessibilityNodeInfo?, inWeb: Boolean, sb: StringBuilder, depth: Int) {
        if (n == null || depth > 60 || sb.length > 3000) return
        val web = inWeb || n.className?.toString() == "android.webkit.WebView"
        if (web) {
            val t = n.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) sb.append(t).append(". ")
        }
        for (i in 0 until n.childCount) collectWebText(n.getChild(i), web, sb, depth + 1)
    }

    @Suppress("DEPRECATION")
    private fun recycle(n: AccessibilityNodeInfo?) {
        try { n?.recycle() } catch (_: Throwable) {}
    }
}
