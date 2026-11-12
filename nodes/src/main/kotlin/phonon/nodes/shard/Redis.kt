/*
 * A very small Redis client (RESP2 over a plain socket), so Nodes needs no extra library for
 * sharding. One connection, used by one thread at a time (callers synchronize). Supports the few
 * commands the shard sync uses.
 */

package phonon.nodes.shard

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class RedisError(message: String) : IOException(message)

class RedisConnection(
    private val host: String,
    private val port: Int,
    private val password: String,
    private val database: Int,
    // 0 = block forever (used by the subscriber)
    private val readTimeoutMs: Int,
) : Closeable {
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: OutputStream? = null

    val connected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    fun connect() {
        close()
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 5000)
        s.soTimeout = readTimeoutMs
        socket = s
        input = BufferedInputStream(s.getInputStream(), 65536)
        output = s.getOutputStream()
        if (password.isNotEmpty()) {
            command("AUTH", password)
        }
        if (database != 0) {
            command("SELECT", database.toString())
        }
    }

    override fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        input = null
        output = null
    }

    fun command(vararg args: String): Any? = commandBytes(*args.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray())

    fun commandBytes(vararg args: ByteArray): Any? {
        send(*args)
        return read()
    }

    fun send(vararg args: ByteArray) {
        val out = output ?: throw IOException("not connected")
        val buf = ByteArrayOutputStream()
        buf.write("*${args.size}\r\n".toByteArray(Charsets.US_ASCII))
        for (arg in args) {
            buf.write("$${arg.size}\r\n".toByteArray(Charsets.US_ASCII))
            buf.write(arg)
            buf.write(CRLF)
        }
        out.write(buf.toByteArray())
        out.flush()
    }

    // a reply: String (status), Long, ByteArray, null, or List<Any?>
    fun read(): Any? {
        val line = readLine()
        if (line.isEmpty()) {
            throw IOException("empty reply")
        }
        val rest = line.substring(1)
        return when (line[0]) {
            '+' -> rest
            '-' -> throw RedisError(rest)
            ':' -> rest.toLong()
            '$' -> {
                val length = rest.toInt()
                if (length < 0) {
                    null
                } else {
                    val data = readExactly(length)
                    readExactly(2)
                    data
                }
            }
            '*' -> {
                val count = rest.toInt()
                if (count < 0) null else List(count) { read() }
            }
            else -> throw IOException("unknown reply type '${line[0]}'")
        }
    }

    private fun readLine(): String {
        val stream = input ?: throw IOException("not connected")
        val buf = ByteArrayOutputStream(64)
        while (true) {
            val b = stream.read()
            if (b < 0) {
                throw IOException("connection closed")
            }
            if (b == '\r'.code) {
                stream.read()
                return buf.toString("UTF-8")
            }
            buf.write(b)
        }
    }

    private fun readExactly(length: Int): ByteArray {
        val stream = input ?: throw IOException("not connected")
        val data = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = stream.read(data, done, length - done)
            if (n < 0) {
                throw IOException("connection closed")
            }
            done += n
        }
        return data
    }

    companion object {
        private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)
    }
}
