# AnkiVoz

Control por voz del repaso de AnkiDroid (offline, Vosk español). Solo actúa dentro de AnkiDroid.

## Compilar (GitHub Actions)
1. Sube el proyecto a un repo (rama `main`).
2. El workflow descarga el modelo `vosk-model-small-es-0.42` y compila.
3. Descarga el APK desde Actions → Artifacts → AnkiVoz-debug.

## Primer uso
1. Instala el APK. Si Android bloquea la accesibilidad: Info de la app → ⋮ → Permitir ajustes restringidos.
2. Activa AnkiVoz en Ajustes → Accesibilidad.
3. Concede micrófono y notificaciones.
4. Pulsa Iniciar, abre AnkiDroid y empieza a repasar.

## Si tu AnkiDroid tiene otro paquete
Edita `Cfg.ANKI_PACKAGES` (Util.kt) y `android:packageNames`
(res/xml/accessibility_service_config.xml) y el bloque `<queries>` del manifest.
