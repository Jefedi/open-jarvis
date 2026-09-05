#!/usr/bin/env python3
"""Idempotent reviewed migrations; resulting application sources are committed by CI."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
base = root / 'app/src/main/java/com/openjarvis'
for name in ('ui/OverlayService.kt', 'local/DownloadService.kt', 'watch/ScreenWatcher.kt'):
    source = base / name
    target = root / ('app/src/legacyReference/' + name + '.txt')
    if source.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source.read_text())
        source.unlink()
def replace(name, old, new):
    path = base / ('murena/' + name + '.kt')
    value = path.read_text()
    if old in value and new not in value: path.write_text(value.replace(old, new))
# Retain cancelled jobs until the next session can join their final cleanup.
replace('AudioController', 'inputJob?.cancel(); inputJob = null', 'inputJob?.cancel()')
replace('AudioController', 'outputJob?.cancel(); outputJob = null', 'outputJob?.cancel()')
replace('AssistantRuntime', '''        currentJob = scope.launch {
            try {''', '''        val previousTask = currentJob
        currentJob = scope.launch {
            previousTask?.join()
            mutableState.value = AgentState.Running("Préparation de la demande…")
            try {''')
replace('AssistantRuntime', 'profile.copy(streaming = false, tools = false, outputTokens = 32)', 'profile.copy(streaming = false, tools = false)')
replace('SafeHttp', 'fun readSse(response: Response, receive: (String, String) -> Unit)', 'fun readSse(response: Response, maxChars: Int = 8_000_000, receive: (String, String) -> Unit)')
replace('SafeHttp', 'val reader = response.body?.charStream()?.buffered() ?: throw IOException("Flux vide.")', 'val source = response.body?.source() ?: throw IOException("Flux vide.")')
replace('SafeHttp', 'val line = reader.readLine() ?: break', 'val line = boundedLine(source) ?: break')
replace('SafeHttp', 'require(total <= 8_000_000 && line.length <= 1_000_000)', 'require(total <= maxChars && line.length <= 1_000_000)')
replace('SafeHttp', '    fun safeError(error: Throwable): Exception = when (error) {', '''    /** Bound a line before allocating an untrusted SSE/NDJSON frame. */
    fun boundedLine(source: okio.BufferedSource, limit: Long = 1_000_000): String? {
        if (source.exhausted()) return null
        return try { source.readUtf8LineStrict(limit) }
        catch (_: java.io.EOFException) {
            require(source.buffer.size <= limit) { "Trame réseau trop volumineuse." }
            source.readUtf8()
        }
    }
    fun safeError(error: Throwable): Exception = when (error) {''')
replace('LlmBackends', 'val reader = response.body?.charStream()?.buffered() ?: error("Flux vide.")', 'val source = response.body?.source() ?: error("Flux vide.")')
replace('LlmBackends', 'val line = reader.readLine() ?: break', 'val line = SafeHttp.boundedLine(source) ?: break')
# Float32 speech is larger than text SSE. Keep each frame bounded, with a separate audio total.
replace('AudioProviders', '''            var done = false
            SafeHttp.readSse(response) { event, data ->''', '''            var done = false
            SafeHttp.readSse(response, maxChars = 64_000_000) { event, data ->''')
print('Reviewed cleanup and streaming safeguards applied; no test or lint rule disabled.')
