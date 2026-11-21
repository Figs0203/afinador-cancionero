package io.github.figs0203.afinador

import io.github.figs0203.afinador.songbook.SongImporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * The playlist page of a very big list can be several megabytes, and Ultimate
 * Guitar sometimes serves it very slowly. These tests cover the two guarantees
 * the import screen relies on: the download reports how much has arrived, and it
 * cannot wait forever.
 */
class SongImporterFetchTest {

    private lateinit var server: ServerSocket
    private val clients = Collections.synchronizedList(mutableListOf<Socket>())

    @Volatile
    private var handler: (Socket) -> Unit = {}

    @Before
    fun startServer() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "test-http") {
            runCatching {
                while (!server.isClosed) {
                    val client = server.accept()
                    clients.add(client)
                    thread(isDaemon = true) { runCatching { handler(client) } }
                }
            }
        }
    }

    @After
    fun stopServer() {
        runCatching { server.close() }
        synchronized(clients) {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
    }

    private fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"

    /**
     * A raw socket that closes without reading the request makes Windows answer
     * with a reset instead of a clean end of stream, so the request line and its
     * headers are consumed first.
     */
    private fun readRequest(client: Socket) {
        val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
        while (true) {
            val line = reader.readLine()
            if (line.isNullOrEmpty()) return
        }
    }

    private fun writeHeaders(
        client: Socket,
        contentLength: Long?,
        chunked: Boolean
    ) {
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=UTF-8\r\n")
            if (contentLength != null) append("Content-Length: $contentLength\r\n")
            if (chunked) append("Transfer-Encoding: chunked\r\n")
            append("Connection: close\r\n\r\n")
        }
        client.getOutputStream().apply {
            write(headers.toByteArray())
            flush()
        }
    }

    @Test
    fun reportsHowMuchOfThePageHasArrived() {
        val body = "<html><body>" + "a".repeat(200_000) + "</body></html>"
        val bytes = body.toByteArray(Charsets.UTF_8)
        handler = { client ->
            readRequest(client)
            val out = client.getOutputStream()
            writeHeaders(client, bytes.size.toLong(), chunked = false)
            out.write(bytes)
            out.flush()
            client.close()
        }

        val reports = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val fetched = SongImporter.fetch(
            url("/page"),
            attempts = 1,
            onProgress = { downloaded, total -> reports.add(downloaded to total) }
        )

        assertEquals(body, fetched)
        assertTrue("debe informar del avance", reports.isNotEmpty())
        assertEquals(bytes.size.toLong(), reports.last().second)
        assertEquals(bytes.size.toLong(), reports.last().first)
    }

    @Test
    fun givesUpWhenThePageNeverFinishes() {
        // Longitud desconocida y un byte cada 50 ms: sin límite total la lectura
        // podría seguir toda la noche aunque cada lectura llegue a tiempo.
        handler = { client ->
            readRequest(client)
            val out = client.getOutputStream()
            writeHeaders(client, contentLength = null, chunked = true)
            repeat(400) {
                out.write("1\r\nx\r\n".toByteArray())
                out.flush()
                Thread.sleep(50)
            }
            client.close()
        }

        val startedAt = System.currentTimeMillis()
        val error = assertThrows(SongImporter.TimeoutException::class.java) {
            SongImporter.fetch(url("/slow"), attempts = 3, deadlineMs = 700)
        }
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue("debe rendirse aunque la conexión siga viva", elapsed < 10_000)
        assertTrue(error.message!!.contains("tardó"))
    }
}
