package com.openjarvis.murena

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RemoteFailure(val status: Int, val retryable: Boolean = status == 429 || status >= 500) : IOException(
    when (status) {
        400 -> "HTTP 400 : paramètres ou modèle refusés par le fournisseur."
        401, 403 -> "HTTP $status : identifiant, autorisation ou contenu refusé. Vérifiez le profil."
        404 -> "HTTP 404 : modèle ou chemin d'API introuvable."
        413 -> "HTTP 413 : requête trop volumineuse."
        429 -> "HTTP 429 : quota ou fréquence limite atteinte."
        else -> "HTTP $status : le serveur n'a pas accepté la requête."
    }
)

/** Does not log bodies, URLs, headers, tokens, or underlying network exception messages. */
object SafeHttp {
    private val shared = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).build()
    val jsonType = "application/json; charset=utf-8".toMediaType()
    fun validateUrl(value: String, allowLocalHttp: Boolean): HttpUrl {
        val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("URL d'API invalide.")
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && url.query == null) {
            "Placez les identifiants dans les champs chiffrés, pas dans l'URL. Les fragments et paramètres d'URL ne sont pas acceptés."
        }
        require(url.isHttps || (url.scheme == "http" && allowLocalHttp)) { "HTTPS est requis. HTTP doit être autorisé explicitement pour un serveur local." }
        return url
    }
    fun isLocal(address: InetAddress): Boolean = address.isLoopbackAddress || address.isSiteLocalAddress ||
        (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)

    private fun client(profile: ConnectionProfile): OkHttpClient = shared.newBuilder()
        .callTimeout(profile.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(profile.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(profile.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .dns(Dns { host ->
            val addresses = Dns.SYSTEM.lookup(host)
            if (!validateUrl(profile.url, profile.allowLocalHttp).isHttps && !addresses.all(::isLocal)) {
                throw UnknownHostException("HTTP is restricted to explicitly enabled local networks")
            }
            addresses
        }).build()

    fun request(profile: ConnectionProfile, path: String = "", method: String = "GET", body: RequestBody? = null,
                extraHeaders: Map<String, String> = emptyMap()): Request {
        val base = validateUrl(profile.url, profile.allowLocalHttp)
        require(!path.startsWith("http") && !path.contains("..")) { "Chemin d'API invalide." }
        val target = if (path.isEmpty()) base else (profile.url.trimEnd('/') + "/" + path.trimStart('/')).toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Chemin d'API invalide.")
        require(target.host == base.host && target.port == base.port && target.scheme == base.scheme)
        return Request.Builder().url(target).apply {
            if (profile.secret.isNotBlank()) {
                when (profile.kind) {
                    "anthropic" -> header("x-api-key", profile.secret)
                    "gemini" -> header("x-goog-api-key", profile.secret)
                    else -> header("Authorization", "Bearer ${profile.secret}")
                }
            }
            if (profile.kind == "anthropic") header("anthropic-version", "2023-06-01")
            profile.headers.forEach { (name, value) -> header(name, value) }
            extraHeaders.forEach { (name, value) -> header(name, value) }
            method(method, body)
        }.build()
    }

    suspend fun <T> execute(profile: ConnectionProfile, request: Request, parser: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client(profile).newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(safeError(e))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!it.isSuccessful) throw RemoteFailure(it.code)
                        parser(it)
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(safeError(e))
                }
            }
        })
    }
    suspend fun json(profile: ConnectionProfile, path: String = "", method: String = "GET", payload: JSONObject? = null,
                     extraHeaders: Map<String, String> = emptyMap()): JSONObject = execute(profile,
        request(profile, path, method, payload?.toString()?.toRequestBody(jsonType), extraHeaders)) {
        val bytes = limitedBytes(it, 2_000_000)
        if (bytes.isEmpty()) JSONObject() else JSONObject(bytes.toString(Charsets.UTF_8))
    }
    fun limitedBytes(response: Response, maxBytes: Int): ByteArray {
        val body = response.body ?: throw IOException("Réponse vide.")
        require(body.contentLength() <= maxBytes) { "Réponse trop volumineuse." }
        val source = body.source()
        source.request(maxBytes.toLong() + 1)
        require(source.buffer.size <= maxBytes) { "Réponse trop volumineuse." }
        return source.readByteArray()
    }
    /** Dispatch complete SSE events only. Partial JSON is never executable. */
    fun readSse(response: Response, receive: (String, String) -> Unit) {
        val reader = response.body?.charStream()?.buffered() ?: throw IOException("Flux vide.")
        var event = "message"
        val data = StringBuilder()
        var total = 0
        fun dispatch() {
            if (data.isNotEmpty()) receive(event, data.toString().trimEnd('\n'))
            data.setLength(0); event = "message"
        }
        while (true) {
            val line = reader.readLine() ?: break
            total += line.length
            require(total <= 8_000_000 && line.length <= 1_000_000) { "Flux trop volumineux." }
            when {
                line.isEmpty() -> dispatch()
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart()).append('\n')
            }
        }
        dispatch()
    }
    fun safeError(error: Throwable): Exception = when (error) {
        is CancellationException -> error
        is RemoteFailure -> error
        is SocketTimeoutException -> IOException("Le délai de réponse est dépassé. Aucune action n'est annoncée comme réussie.")
        is UnknownHostException -> IOException("Serveur inaccessible ou adresse HTTP hors réseau local.")
        is org.json.JSONException -> IOException("Le fournisseur a renvoyé une réponse non conforme.")
        is javax.net.ssl.SSLException -> IOException("Certificat TLS refusé. La vérification de sécurité reste activée.")
        else -> IOException("Connexion ou réponse invalide. Vérifiez l'URL, le modèle et les paramètres du profil.")
    }
}
