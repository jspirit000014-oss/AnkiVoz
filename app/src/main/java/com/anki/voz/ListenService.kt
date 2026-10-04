package com.anki.voz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.util.Locale
import kotlin.math.min

/**
 * Servicio en primer plano (micrófono) con Vosk offline.
 * El micrófono SOLO está activo mientras AnkiDroid es la app en pantalla.
 */
class ListenService : Service() {

    companion object {
        const val ACTION_STOP = "com.anki.voz.STOP"
        private const val CH = "anki_voz"
        private const val NID = 42

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _status = MutableStateFlow("Detenido")
        val status: StateFlow<String> = _status.asStateFlow()
    }

    @Volatile private var alive = false
    @Volatile private var paused = false
    @Volatile private var speaking = false
    private var worker: Thread? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            goForeground("Iniciando…")
        } catch (e: Throwable) {
            _status.value = "No se pudo iniciar: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        if (alive) return START_NOT_STICKY
        alive = true
        paused = false
        _running.value = true

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag("es")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { speaking = true }
                    override fun onDone(id: String?) { speaking = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { speaking = false }
                })
                ttsReady = true
            }
        }

        worker = Thread({ loop() }, "anki-voz").also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        alive = false
        worker?.interrupt()
        tts?.stop()
        tts?.shutdown()
        tts = null
        _running.value = false
        _status.value = "Detenido"
        super.onDestroy()
    }

    // ───────────────────────── Bucle de reconocimiento ─────────────────────────

    private fun loop() {
        var model: Model? = null
        var rec: Recognizer? = null
        var audio: AudioRecord? = null
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)

            setStatus("Cargando modelo de voz…")
            val dir = ModelLoader.prepare(this)
            if (dir == null) {
                setStatus("Falta el modelo de voz (assets/model)")
                stopSelf()
                return
            }
            val cmds = Commands.load(this)
            model = Model(dir.absolutePath)
            rec = Recognizer(model, 16000f, cmds.grammarJson)
            rec.setWords(true)

            val minBuf = AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            audio = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, 8192)
            )
            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                setStatus("No se pudo abrir el micrófono")
                stopSelf()
                return
            }

            val buf = ShortArray(2048)
            var lastCheck = 0L
            var active = false
            var lastMsg = ""

            while (alive) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastCheck > 400) {
                    active = AnkiAccessibilityService.isAnkiActive()
                    lastCheck = now
                }

                if (!active || speaking) {
                    if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        audio.stop()
                        rec.reset()
                    }
                    val msg = when {
                        AnkiAccessibilityService.instance == null -> "Activa el servicio de accesibilidad"
                        speaking -> "Leyendo…"
                        else -> "En espera: abre AnkiDroid"
                    }
                    if (msg != lastMsg) { lastMsg = msg; setStatus(msg) }
                    Thread.sleep(250)
                    continue
                }

                if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    audio.startRecording()
                    lastMsg = if (paused) "Pausado — di «reanudar»" else "Escuchando en AnkiDroid"
                    setStatus(lastMsg)
                }

                val n = audio.read(buf, 0, buf.size)
                if (n < 0) { Thread.sleep(100); continue }
                if (n == 0) continue
                if (rec.acceptWaveForm(buf, n)) handle(rec.result, cmds)
            }
        } catch (e: InterruptedException) {
            // detenido
        } catch (e: Throwable) {
            setStatus("Error: ${e.message}")
            stopSelf()
        } finally {
            try { audio?.stop() } catch (_: Throwable) {}
            try { audio?.release() } catch (_: Throwable) {}
            try { rec?.close() } catch (_: Throwable) {}
            try { model?.close() } catch (_: Throwable) {}
        }
    }

    private fun handle(json: String, cmds: Commands) {
        val o = JSONObject(json)
        val text = o.optString("text").trim()
        if (text.isEmpty() || text == "[unk]") return

        var minConf = 1.0
        val words = o.optJSONArray("result")
        if (words != null) {
            for (i in 0 until words.length()) {
                minConf = min(minConf, words.getJSONObject(i).optDouble("conf", 1.0))
            }
        }

        val act = cmds.match(text)
        if (act == null) {
            setStatus("Oído: «$text» (no es un comando)")
            return
        }
        if (minConf < Cfg.MIN_CONF) {
            setStatus("Descartado «$text» (confianza %.2f)".format(minConf))
            return
        }
        execute(act, text)
    }

    private fun execute(act: Act, heard: String) {
        if (paused && act != Act.RESUME && act != Act.QUIT) return
        when (act) {
            Act.PAUSE -> { paused = true; setStatus("Pausado — di «reanudar»") }
            Act.RESUME -> { paused = false; setStatus("Escuchando en AnkiDroid") }
            Act.QUIT -> { setStatus("Apagando…"); stopSelf() }
            Act.READ -> {
                val t = AnkiAccessibilityService.instance?.readCardText()
                if (t != null && ttsReady) {
                    speaking = true
                    tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "card")
                    setStatus("Leyendo la tarjeta…")
                } else {
                    setStatus("«$heard»: no se pudo leer la tarjeta")
                }
            }
            else -> {
                val ok = AnkiAccessibilityService.instance?.perform(act) == true
                setStatus("«$heard» → ${act.label}" + if (ok) "" else " (botón no encontrado)")
            }
        }
    }

    // ───────────────────────── Notificación ─────────────────────────

    private fun setStatus(text: String) {
        _status.value = text
        if (alive) {
            try {
                getSystemService(NotificationManager::class.java).notify(NID, buildNotif(text))
            } catch (_: Throwable) {}
        }
    }

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "AnkiVoz", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotif(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NID, n)
        }
    }

    private fun buildNotif(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, ListenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Detener", stop
        ).build()
        return Notification.Builder(this, CH)
            .setContentTitle("AnkiVoz")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(action)
            .build()
    }
}
