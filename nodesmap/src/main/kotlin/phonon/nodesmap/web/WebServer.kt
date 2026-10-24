package phonon.nodesmap.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.logging.Logger
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream

/**
 * Minimal static + in-memory HTTP server.
 *
 * - Static files come from the web root (viewer, tiles, data/<layer>.json written by Nodes).
 * - "Dynamic" routes are byte arrays held in memory (players, map info) so the
 *   hottest polling endpoints never hit the disk.
 * - ETag / If-None-Match on everything: unchanged tiles and data cost a 304.
 * - Text responses are gzipped; compressed copies are cached per ETag.
 */
class WebServer(
    private val root: Path,
    private val bind: String,
    private val port: Int,
    private val threads: Int,
    private val logger: Logger,
) {
    private class Dynamic(val bytes: ByteArray, val gz: ByteArray?, val etag: String, val type: String)

    private val dynamic = ConcurrentHashMap<String, Dynamic>()
    private val gzCache = ConcurrentHashMap<Path, Pair<String, ByteArray>>()
    private var server: HttpServer? = null

    fun start() {
        val s = HttpServer.create(InetSocketAddress(bind, port), 64)
        s.executor = Executors.newFixedThreadPool(threads) { r ->
            Thread(r, "NodesMap-Web").apply { isDaemon = true }
        }
        s.createContext("/") { ex ->
            try {
                handle(ex)
            } catch (e: Exception) {
                try {
                    send(ex, 500, "text/plain", "error".toByteArray(), null, null)
                } catch (_: Exception) {}
            } finally {
                ex.close()
            }
        }
        s.start()
        server = s
        logger.info("[NodesMap] web map on http://$bind:$port/")
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    /** Publish/replace an in-memory route, e.g. "/data/players.json". */
    fun put(path: String, body: String, type: String = "application/json") {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val crc = CRC32().apply { update(bytes) }.value
        val gz = if (bytes.size > 1024) gzip(bytes) else null
        dynamic[path] = Dynamic(bytes, gz, "\"d${crc.toString(16)}-${bytes.size}\"", type)
    }

    private fun handle(ex: HttpExchange) {
        if (ex.requestMethod != "GET" && ex.requestMethod != "HEAD") {
            send(ex, 405, "text/plain", ByteArray(0), null, null)
            return
        }
        var path = URLDecoder.decode(ex.requestURI.rawPath, StandardCharsets.UTF_8)
        if (path == "/" || path.isEmpty()) path = "/index.html"

        dynamic[path]?.let { d ->
            if (notModified(ex, d.etag)) return
            val acceptGz = acceptsGzip(ex)
            send(ex, 200, d.type, if (acceptGz && d.gz != null) d.gz else d.bytes, d.etag, if (acceptGz && d.gz != null) "gzip" else null)
            return
        }

        val file = root.resolve(path.removePrefix("/")).normalize()
        if (!file.startsWith(root) || !Files.isRegularFile(file) || file.fileName.toString().endsWith(".tmp")) {
            send(ex, 404, "text/plain", "not found".toByteArray(), null, null)
            return
        }

        val size = Files.size(file)
        val mtime = Files.getLastModifiedTime(file).toMillis()
        val etag = "\"${mtime.toString(36)}-${size.toString(36)}\""
        if (notModified(ex, etag)) return

        val type = contentType(file)
        val compressible = type.startsWith("text/") || type.contains("json") || type.contains("javascript") || type.contains("svg")
        if (compressible && size > 1024 && acceptsGzip(ex)) {
            val cached = gzCache[file]
            val gz = if (cached != null && cached.first == etag) {
                cached.second
            } else {
                gzip(Files.readAllBytes(file)).also { gzCache[file] = etag to it }
            }
            send(ex, 200, type, gz, etag, "gzip")
        } else {
            send(ex, 200, type, Files.readAllBytes(file), etag, null)
        }
    }

    private fun notModified(ex: HttpExchange, etag: String): Boolean {
        val inm = ex.requestHeaders.getFirst("If-None-Match") ?: return false
        if (inm != etag) return false
        ex.responseHeaders.add("ETag", etag)
        ex.responseHeaders.add("Cache-Control", "no-cache")
        ex.sendResponseHeaders(304, -1)
        return true
    }

    private fun acceptsGzip(ex: HttpExchange): Boolean = ex.requestHeaders.getFirst("Accept-Encoding")?.contains("gzip") == true

    private fun send(ex: HttpExchange, code: Int, type: String, body: ByteArray, etag: String?, encoding: String?) {
        val h = ex.responseHeaders
        h.add("Content-Type", type)
        // always revalidate; the ETag makes that a cheap 304
        h.add("Cache-Control", "no-cache")
        h.add("Access-Control-Allow-Origin", "*")
        if (etag != null) h.add("ETag", etag)
        if (encoding != null) h.add("Content-Encoding", encoding)
        if (ex.requestMethod == "HEAD") {
            ex.sendResponseHeaders(code, -1)
            return
        }
        ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
    }

    private fun gzip(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(b) }
        return out.toByteArray()
    }

    private fun contentType(p: Path): String = when (p.fileName.toString().substringAfterLast('.').lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "png" -> "image/png"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }
}
