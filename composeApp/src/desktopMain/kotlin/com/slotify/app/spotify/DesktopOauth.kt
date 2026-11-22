// Written fresh for Slotify. The PKCE loopback half replaces Parachord's
// Android Custom-Tab deep-link callback
// (MIT License), © Jason Herskowitz — https://github.com/Parachord/parachord-mobile
// app/.../auth/OAuthManager.kt (handleSpotifyCallback state verification).

package com.slotify.app.spotify

import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

private const val OK_PAGE =
    "<!doctype html><html><head><meta charset=\"utf-8\"><title>Slotify</title></head>" +
        "<body style=\"font-family:sans-serif;text-align:center;margin-top:4em\">" +
        "<h2>Connected to Spotify</h2><p>You can close this tab and return to Slotify.</p>" +
        "</body></html>"

private const val ERROR_PAGE =
    "<!doctype html><html><head><meta charset=\"utf-8\"><title>Slotify</title></head>" +
        "<body style=\"font-family:sans-serif;text-align:center;margin-top:4em\">" +
        "<h2>Authorization failed</h2><p>You can close this tab and return to Slotify.</p>" +
        "</body></html>"

private fun parseQueryParams(query: String?): Map<String, String> =
    query.orEmpty()
        .split('&')
        .filter { it.isNotEmpty() }
        .associate { pair ->
            val idx = pair.indexOf('=')
            val key = if (idx >= 0) pair.substring(0, idx) else pair
            val value = if (idx >= 0) pair.substring(idx + 1) else ""
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }

/**
 * Desktop half of the PKCE flow: starts a tiny loopback HTTP server on
 * `127.0.0.1:<port>` from [redirectUri] BEFORE opening the system browser at
 * [authorizeUrl], waits for Spotify's `GET /callback?code=...&state=...`,
 * verifies `state` against the one embedded in [authorizeUrl] (CSRF
 * protection), responds a small "you can close this tab" page, shuts the
 * server down, and returns the authorization `code`.
 *
 * Fixed app-wide redirect: [SPOTIFY_REDIRECT_URI].
 *
 * @throws IOException on timeout ([timeoutMs]), an `error` callback param
 *   (e.g. user denied), or a `state` mismatch.
 */
suspend fun desktopAwaitPkceCode(
    authorizeUrl: String,
    redirectUri: String,
    timeoutMs: Long = 180_000,
): String {
    val redirect = URI(redirectUri)
    val port = redirect.port
    require(port > 0) { "redirectUri must carry an explicit port: $redirectUri" }
    val path = redirect.path?.takeIf { it.isNotEmpty() } ?: "/callback"
    val expectedState = parseQueryParams(URI(authorizeUrl).rawQuery)["state"]
        ?: error("authorizeUrl is missing the state parameter")

    val code = CompletableDeferred<String>()
    val server = try {
        HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    } catch (e: java.net.BindException) {
        throw IOException(
            "Port $port is already in use — is another Slotify instance still running? " +
                "Close it and try again. (${e.message})",
        )
    }
    server.createContext(path) { exchange ->
        try {
            val params = parseQueryParams(exchange.requestURI.rawQuery)
            val oauthError = params["error"]
            val state = params["state"]
            val returnedCode = params["code"]
            when {
                oauthError != null -> {
                    respond(exchange, 200, ERROR_PAGE)
                    code.completeExceptionally(IOException("Spotify authorization failed: $oauthError"))
                }
                state != expectedState -> {
                    respond(exchange, 400, ERROR_PAGE)
                    code.completeExceptionally(IOException("OAuth state mismatch — possible CSRF; rejecting callback"))
                }
                returnedCode == null -> {
                    // Unrelated probe (favicon, port scan) — reject but keep waiting.
                    respond(exchange, 400, ERROR_PAGE)
                }
                else -> {
                    respond(exchange, 200, OK_PAGE)
                    code.complete(returnedCode)
                }
            }
        } catch (e: Exception) {
            runCatching { respond(exchange, 500, ERROR_PAGE) }
            code.completeExceptionally(e)
        }
    }
    try {
        server.start()
        // Server is accepting connections before the browser opens — Spotify's
        // redirect cannot race an unbound socket.
        //
        // ALWAYS print the URL: if auto-open silently does nothing (default
        // browser quirks), the user can paste it from the terminal and the
        // still-listening loopback server will catch the redirect either way.
        println("Slotify: Spotify sign-in — opening your browser…")
        println("If no window appears, paste this URL into a browser manually:")
        println(authorizeUrl)
        println()
        try {
            Desktop.getDesktop().browse(URI(authorizeUrl))
        } catch (e: Exception) {
            println("Automatic browser launch failed (${e.message}) — please use the URL above.")
        }
        return try {
            withTimeout(timeoutMs) { code.await() }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Timed out after ${timeoutMs}ms waiting for the Spotify OAuth callback on $redirectUri")
        }
    } finally {
        runCatching { server.stop(0) }
    }
}

private fun respond(
    exchange: com.sun.net.httpserver.HttpExchange,
    status: Int,
    html: String,
) {
    val bytes = html.toByteArray(Charsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}
