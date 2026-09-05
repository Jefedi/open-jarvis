#!/usr/bin/env python3
from pathlib import Path
root = Path(__file__).resolve().parents[1]
def replace(path, old, new):
    p = root / path
    value = p.read_text()
    if old in value: p.write_text(value.replace(old, new))
base = 'app/src/main/java/com/openjarvis/'
replace(base + 'accessibility/JarvisAccessibilityService.kt', 'AccessibilityEvent.TYPE_ALL_MASK', 'AccessibilityEvent.TYPES_ALL_MASK')
replace(base + 'murena/AudioController.kt', 'recorder.read(buffer, 0, buffer.size())', 'recorder.read(buffer, 0, buffer.size)')
replace(base + 'murena/SafeHttp.kt', '''        .dns(Dns { host ->
            val addresses = Dns.SYSTEM.lookup(host)
            if (!validateUrl(profile.url, profile.allowLocalHttp).isHttps && !addresses.all(::isLocal)) {
                throw UnknownHostException("HTTP is restricted to explicitly enabled local networks")
            }
            addresses
        }).build()''', '''        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = Dns.SYSTEM.lookup(hostname)
                if (!validateUrl(profile.url, profile.allowLocalHttp).isHttps && !addresses.all(::isLocal)) {
                    throw UnknownHostException("HTTP is restricted to explicitly enabled local networks")
                }
                return addresses
            }
        }).build()''')
replace(base + 'murena/AudioController.kt', '''        stopSpeech()
        if (!foreground)''', '''        val previousSpeech = outputJob
        stopSpeech()
        if (!foreground)''')
replace(base + 'murena/AudioController.kt', '''        inputJob = scope.launch {
            previousInput?.join()
            try {''', '''        inputJob = scope.launch {
            previousInput?.join()
            previousSpeech?.join()
            try {''')
replace(base + 'murena/SystemVoiceEngine.kt', '''    override fun onGetLanguage(): Array<String> = arrayOf("fra", "FRA", "")''', '''    override fun onGetFeaturesForLanguage(lang: String, country: String, variant: String): MutableSet<String> =
        mutableSetOf(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)
    override fun onGetLanguage(): Array<String> = arrayOf("fra", "FRA", "")''')
print('Integrated compiler fixes applied if needed.')
