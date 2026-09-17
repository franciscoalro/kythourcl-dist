package com.RedeCanaisAF

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * LogBridge (v276): escutador de logs dentro do plugin.
 *
 * Expõe o logcat do próprio processo via HTTP local, para leitura remota
 * através de túnel (ngrok / Cloudflare Tunnel / `adb forward` / `ssh -R`).
 * Escuta SOMENTE em 127.0.0.1 — a exposição externa depende do túnel do usuário.
 *
 * Endpoints:
 *   GET /ping  -> "pong v276" (liveness)
 *   GET /logs  -> últimas linhas do logcat do processo filtradas pela TAG do plugin
 *
 * Sem permissão extra: desde o Android 4.1 (API 16) o app lê o próprio log
 * via `logcat` sem READ_LOGS. Sem alteração nas chamadas Log.* existentes.
 */
object LogBridge {
    private const val TAG = "RedeCanaisAF-Trace"
    const val PREFERRED_PORT = 17531
    private const val THREAD_NAME = "RCLogBridge"

    private var serverSocket: ServerSocket? = null

    @Volatile var port: Int = 0
        private set

    @Synchronized
    fun start() {
        if (serverSocket != null && !(serverSocket!!.isClosed)) return
        try {
            val s = try {
                ServerSocket(PREFERRED_PORT, 16, InetAddress.getByName("127.0.0.1"))
            } catch (_: Throwable) {
                // porta ocupada — cai para efêmera e publica a real em `port`
                ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            }
            serverSocket = s
            port = s.localPort
            Log.i(TAG, "[LOGBRIDGE] ativo em http://127.0.0.1:$port/logs")

            thread(isDaemon = true, name = "$THREAD_NAME-Accept") {
                while (!s.isClosed) {
                    try {
                        val client = s.accept()
                        thread(isDaemon = true, name = "$THREAD_NAME-Worker") {
                            handleClient(client)
                        }
                    } catch (e: Throwable) {
                        if (!s.isClosed) Log.w(TAG, "[LOGBRIDGE] accept err: ${e.message}")
                        break
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "[LOGBRIDGE] falha ao iniciar: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        port = 0
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { sock ->
                sock.soTimeout = 15000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                // consome headers restantes
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                }
                val path = requestLine.split(" ").getOrNull(1) ?: "/"
                val out = sock.getOutputStream()

                val (code, contentType, body) = when {
                    path.startsWith("/ping") -> Triple(200, "text/plain", "pong v276 port=$port")
                    path.startsWith("/logs") -> {
                        val lines = readParam(path, "n")?.toIntOrNull()?.coerceIn(1, 2000) ?: 300
                        Triple(200, "text/plain; charset=utf-8", dumpLogcat(lines))
                    }
                    else -> Triple(404, "text/plain", "unknown path (use /logs?n=300 ou /ping)")
                }
                val bodyBytes = body.toByteArray(Charsets.UTF_8)
                val head =
                    "HTTP/1.1 $code ${if (code == 200) "OK" else "Not Found"}\r\n" +
                        "Content-Type: $contentType\r\n" +
                        "Content-Length: ${bodyBytes.size}\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                out.write(bodyBytes)
                out.flush()
            }
        } catch (e: Throwable) {
            Log.d(TAG, "[LOGBRIDGE] conexão encerrada: ${e.message}")
        }
    }

    private fun readParam(path: String, key: String): String? {
        val q = path.substringAfter("?", "")
        if (q.isEmpty()) return null
        for (part in q.split("&")) {
            val kv = part.split("=", limit = 2)
            if (kv.size == 2 && kv[0] == key) return kv[1]
        }
        return null
    }

    /** Últimas N linhas do logcat do próprio processo, filtradas pela TAG do plugin. */
    private fun dumpLogcat(n: Int): String {
        return try {
            // -d = dump e sai; -t N = últimas N; -v time = timestamp legível
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", n.toString(), "-v", "time", "$TAG:I", "*:S")
            )
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            if (text.isBlank()) {
                // fallback sem filtro por tag (alguns builds filtram o próprio log)
                val p2 = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-d", "-t", n.toString(), "-v", "time")
                )
                val t2 = p2.inputStream.bufferedReader().readText()
                p2.waitFor()
                t2.ifBlank { "(log vazio — plugin sem atividade recente)" }
            } else text
        } catch (e: Throwable) {
            "(falha ao ler logcat: ${e.message})"
        }
    }
}
