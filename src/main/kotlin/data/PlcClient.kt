package data

import helper.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max


object PlcClient : Closeable {
    // ---- reconnect/backoff ----
    private const val RECONNECT_BASE_MS = 250L
    private const val RECONNECT_MAX_MS  = 5_000L
    private const val CONNECT_COOLDOWN_ON_FAIL_MS = 50L // чтобы не ддосить стек при быстром падении

    @Volatile private var nextConnectAllowedAtMs: Long = 0L

    private fun nowMs(): Long = System.currentTimeMillis()

    private fun computeBackoffMs(failStreak: Int): Long {
        // exp backoff: base * 2^(n-1) с cap, + jitter 0..100ms
        val exp = RECONNECT_BASE_MS * (1L shl (failStreak.coerceIn(0, 6))) // до *64
        val capped = exp.coerceAtMost(RECONNECT_MAX_MS)
        val jitter = (0..100).random().toLong()
        return capped + jitter
    }
    @Volatile private var failStreak: Int = 0

    suspend fun exchangeRobust(
        requestFrame: ByteArray,
        timeoutMs: Int = 3000,
        maxRetries: Int = Int.MAX_VALUE
    ): ByteArray? {
        var attempt = 0
        while (true) {
            try {
                val resp = exchange(requestFrame, timeoutMs) // внутри ioMutex
                // если ответ null = timeout чтения. решаем ниже по политике timeout'ов.
                if (resp != null) {
                    failStreak = 0
                }
                return resp
            } catch (e: IOException) {
                // IO -> нужно реконнектиться
                attempt++
                failStreak = (failStreak + 1).coerceAtMost(20)
                val delayMs = computeBackoffMs(failStreak)

                // ограничение по попыткам (если нужно)
                if (attempt >= maxRetries) throw e

                // пауза ВНЕ mutex (exchange уже вышел)
                withContext(Dispatchers.IO) {
                    Thread.sleep(delayMs)
                }
            }
        }
    }

    private const val MAX_CONSECUTIVE_TIMEOUTS = 3
    @Volatile private var consecutiveTimeouts: Int = 0

    // ---- metrics ----
    data class Stats(
        val host: String?,
        val port: Int,
        val exchangeOk: Long,
        val exchangeFail: Long,
        val timeouts: Long,
        val ioErrors: Long,
        val lastSuccessAtMs: Long,
        val lastFailureAtMs: Long,
        val lastRttMs: Long,
        val lastError: String?
    )

    private val exchangeOk = AtomicLong(0)
    private val exchangeFail = AtomicLong(0)
    private val timeouts = AtomicLong(0)
    private val ioErrors = AtomicLong(0)

    @Volatile private var lastSuccessAtMs: Long = 0L
    @Volatile private var lastFailureAtMs: Long = 0L
    @Volatile private var lastRttMs: Long = 0L
    @Volatile private var lastError: String? = null

    fun getStats(): Stats = Stats(
        host = lastHost,
        port = lastPort,
        exchangeOk = exchangeOk.get(),
        exchangeFail = exchangeFail.get(),
        timeouts = timeouts.get(),
        ioErrors = ioErrors.get(),
        lastSuccessAtMs = lastSuccessAtMs,
        lastFailureAtMs = lastFailureAtMs,
        lastRttMs = lastRttMs,
        lastError = lastError
    )

    private fun noteOk(rttMs: Long) {
        exchangeOk.incrementAndGet()
        lastSuccessAtMs = System.currentTimeMillis()
        lastRttMs = max(0L, rttMs)
        lastError = null
    }

    private fun noteTimeout() {
        timeouts.incrementAndGet()
        exchangeFail.incrementAndGet()
        lastFailureAtMs = System.currentTimeMillis()
        lastError = "timeout"
    }

    private fun noteIoError(msg: String) {
        ioErrors.incrementAndGet()
        exchangeFail.incrementAndGet()
        lastFailureAtMs = System.currentTimeMillis()
        lastError = msg
    }

    private const val TAG = "PlcClient"

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var lastHost: String? = null

    @Volatile
    private var lastPort: Int = 0

    // Глобальный мьютекс, чтобы send+read не пересекались из разных корутин
    private val ioMutex = Mutex()

    fun setEndpoint(host: String, port: Int) {
        lastHost = host
        lastPort = port
        Log.d(TAG, "setEndpoint host=$host port=$port")
    }

    // Гарантирует, что есть "живой" сокет-объект. Реальную "живость" проверяем IO-операциями.
    private suspend fun ensureConnected(timeoutMs: Int) {
        withContext(Dispatchers.IO) {
            val now = nowMs()
            val allowedAt = nextConnectAllowedAtMs
            if (now < allowedAt) {
                val wait = allowedAt - now
                Log.w(TAG, "ensureConnected: cooldown $wait ms before next connect")
                throw IOException("Connect cooldown $wait ms")
            }

            val current = socket
            if (current != null && current.isConnected && !current.isClosed) {
                return@withContext
            }

            try { current?.close() } catch (_: Exception) {}

            val h = lastHost
            val p = lastPort
            if (h.isNullOrEmpty() || p == 0) {
                Log.e(TAG, "ensureConnected: host/port not set")
                throw IOException("Endpoint not set")
            }

            val s = Socket()
            try {
                Log.d(TAG, "Connecting to $h:$p timeoutMs=$timeoutMs")
                s.connect(InetSocketAddress(h, p), timeoutMs)

                s.tcpNoDelay = true
                s.keepAlive = true
                socket = s
                nextConnectAllowedAtMs = 0L
                Log.d(TAG, "Connected to $h:$p (persistent)")
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed to $h:$p", e)
                try { s.close() } catch (_: Exception) {}
                nextConnectAllowedAtMs = nowMs() + CONNECT_COOLDOWN_ON_FAIL_MS
                socket = null
                throw (e as? IOException) ?: IOException("Connect failed", e)
            }
        }
    }

    /**
     * - НЕ закрывает сокет после блока
     * - сериализует операции через ioMutex
     * - при любой IO-ошибке считает сокет мёртвым и закрывает
     */
    suspend fun <T> withConnection(
        timeoutMs: Int = 3000,
        block: suspend () -> T
    ): T {
        return ioMutex.withLock {
            ensureConnected(timeoutMs)
            try {
                block()
            } catch (e: IOException) {
                Log.e(TAG, "withConnection: IO error, dropping socket", e)
                disconnect()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "withConnection: unexpected error", e)
                throw e
            }
        }
    }

    private suspend fun send(bytes: ByteArray) {
        val head = bytes.take(16).joinToString(",") { (it.toInt() and 0xFF).toString(16) }
        Log.d(TAG, "send(${bytes.size} bytes) head=[$head]")
        withContext(Dispatchers.IO) {
            val s = socket
            if (s == null || s.isClosed) {
                Log.w(TAG, "send(): socket is null/closed, skipping send")
                return@withContext
            }
            try {
                val out = s.getOutputStream()
                out.write(bytes)
                out.flush()
            } catch (e: Exception) {
                Log.e(TAG, "send(): IO error", e)
                throw (e as? IOException) ?: IOException("send() failed", e)
            }
        }
    }

    /**
     * Читает кадр формата:
     *   [len_lo][len_hi][payload ]
     *
     * Правило:
     * - timeout -> null (соединение не рвём)
     * - EOF/обрыв/любая IO-проблема -> IOException (withConnection дропнет сокет)
     */
    private suspend fun readLenPrefixedFrame(timeoutMs: Int = 3000): ByteArray? {
        return withContext(Dispatchers.IO) {
            val s = socket
            if (s == null || s.isClosed) {
                Log.w(TAG, "readLenPrefixedFrame: socket is null/closed")
                return@withContext null
            }

            val input = s.getInputStream()

            try {
                s.soTimeout = timeoutMs
            } catch (e: Exception) {
                Log.e(TAG, "readLenPrefixedFrame: failed to set soTimeout", e)
            }

            val header = ByteArray(2)
            val headerOk = tryReadFully(input, header, 0, 2, "header")
            if (!headerOk) return@withContext null // только timeout

            val len = (header[0].toInt() and 0xFF) or
                    ((header[1].toInt() and 0xFF) shl 8)

            if (len <= 0) {
                // Это уже протокольная проблема. Можно оставить сокет, но чаще лучше дропать.
                Log.w(TAG, "readLenPrefixedFrame: invalid length=$len -> dropping socket")
                throw IOException("Invalid frame length=$len")
            }

            val payload = ByteArray(len)
            val payloadOk = tryReadFully(input, payload, 0, len, "payload")
            if (!payloadOk) return@withContext null // только timeout

            val result = ByteArray(2 + len)
            System.arraycopy(header, 0, result, 0, 2)
            System.arraycopy(payload, 0, result, 2, len)

            Log.d(TAG, "readLenPrefixedFrame: got frame len=$len")
            result
        }
    }

    /**
     * Возвращает:
     * - true  -> успешно прочитали len байт
     * - false -> только если timeout
     *
     * Любой EOF/обрыв/ошибка -> IOException (чтобы withConnection дропнул сокет)
     */
    private fun tryReadFully(
        input: InputStream,
        buf: ByteArray,
        off: Int,
        len: Int,
        label: String
    ): Boolean {
        var readTotal = 0
        while (readTotal < len) {
            val r: Int = try {
                input.read(buf, off + readTotal, len - readTotal)
            } catch (e: SocketTimeoutException) {
                if (readTotal == 0) {
                    Log.w(TAG, "tryReadFully($label): timeout (no data)")
                    return false // "чистый" timeout, ничего не съели
                }
                // timeout в середине кадра -> соединение уже в неопределённом состоянии
                Log.w(TAG, "tryReadFully($label): timeout mid-frame after $readTotal/$len -> drop socket")
                throw SocketTimeoutException("timeout mid-frame $label $readTotal/$len")
            }catch (e: IOException) {
                Log.e(TAG, "tryReadFully($label): IO error after $readTotal/$len", e)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "tryReadFully($label): unexpected error after $readTotal/$len", e)
                throw IOException("Unexpected read error ($label)", e)
            }

            if (r < 0) {
                Log.w(TAG, "tryReadFully($label): EOF after $readTotal/$len bytes")
                throw EOFException("EOF while reading $label ($readTotal/$len)")
            }
            if (r == 0) {
                // почти не бывает на InputStream, но пусть будет как ошибка
                throw IOException("Read 0 bytes while reading $label ($readTotal/$len)")
            }
            readTotal += r
        }
        return true
    }

    /**
     * Идеальный “атомарный” обмен запрос→ответ:
     * - работает под ioMutex через withConnection
     * - меряет RTT
     * - фиксирует метрики
     *
     * Возврат:
     * - ByteArray? (null только при timeout ожидания ответа)
     *
     * Правила:
     * - timeout чтения -> null (сокет НЕ рвём, как у тебя было)
     * - любые IO/протокольные проблемы -> IOException (withConnection дропнет сокет)
     */
    suspend fun exchange(
        requestFrame: ByteArray,
        timeoutMs: Int = 3000
    ): ByteArray? {
        return withConnection(timeoutMs) {
            val t0 = System.nanoTime()
            try {
                send(requestFrame)
                val resp = readLenPrefixedFrame(timeoutMs)
                if (resp == null) {
                    noteTimeout()
                    consecutiveTimeouts++
                    if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                        Log.w(TAG, "exchange: $consecutiveTimeouts timeouts подряд -> drop socket")
                        disconnect()
                        // ВАЖНО: кидаем IOException, чтобы exchangeRobust ушёл в реконнект/backoff
                        throw SocketTimeoutException("read timeout x$consecutiveTimeouts")
                    }
                    return@withConnection null
                } else {
                    consecutiveTimeouts = 0
                }
                val rttMs = (System.nanoTime() - t0) / 1_000_000L
                noteOk(rttMs)
                resp
            }   catch (e: IOException) {
                noteIoError(e.message ?: "io_error")
                throw e
            } catch (e: Exception) {
                noteIoError(e.message ?: "unexpected_error")
                throw (e as? IOException) ?: IOException("exchange failed", e)
            }
        }
    }


    fun disconnect() {
        val s = socket
        if (s != null) {
            try {
                Log.d(TAG, "disconnect(): closing socket")
                s.close()
            } catch (e: Exception) {
                Log.e(TAG, "disconnect(): error while closing socket", e)
            } finally {
                socket = null
            }
        } else {
            socket = null
        }
    }

    override fun close() {
        disconnect()
    }
}
