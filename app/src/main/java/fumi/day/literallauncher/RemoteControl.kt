package fumi.day.literallauncher

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal const val REMOTE_CONTROL_PORT = 8765

internal sealed interface RemoteMediaCommand {
    data object Start : RemoteMediaCommand
    data object Play : RemoteMediaCommand
    data object Pause : RemoteMediaCommand
    data object Previous : RemoteMediaCommand
    data object Next : RemoteMediaCommand
    data object Stop : RemoteMediaCommand
    data class SetImageInterval(val seconds: Int) : RemoteMediaCommand
}

internal data class RemoteMediaStatus(
    val playlistReady: Boolean = false,
    val active: Boolean = false,
    val paused: Boolean = false,
    val currentTitle: String = "",
    val currentPosition: Int = 0,
    val itemCount: Int = 0,
    val imageIntervalSeconds: Int = 5,
)

internal data class RemoteServerState(
    val running: Boolean = false,
    val addresses: List<String> = emptyList(),
    val pin: String,
    val errorMessage: String? = null,
) {
    val primaryUrl: String?
        get() = addresses.firstOrNull()?.let { "http://$it:$REMOTE_CONTROL_PORT" }
}

internal class RemoteControlCoordinator(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val pin = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')

    private val _commands = MutableSharedFlow<RemoteMediaCommand>(
        extraBufferCapacity = 32,
    )
    val commands: SharedFlow<RemoteMediaCommand> = _commands.asSharedFlow()

    private val _mediaStatus = MutableStateFlow(RemoteMediaStatus())
    val mediaStatus: StateFlow<RemoteMediaStatus> = _mediaStatus.asStateFlow()

    private val _serverState = MutableStateFlow(RemoteServerState(pin = pin))
    val serverState: StateFlow<RemoteServerState> = _serverState.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var addressJob: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return

        serverJob = scope.launch {
            try {
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", REMOTE_CONTROL_PORT))
                }
                serverSocket = socket
                _serverState.value = RemoteServerState(
                    running = true,
                    addresses = findPrivateIpv4Addresses(),
                    pin = pin,
                )

                addressJob = scope.launch {
                    while (currentCoroutineContext().isActive) {
                        val addresses = findPrivateIpv4Addresses()
                        val current = _serverState.value
                        if (current.addresses != addresses) {
                            _serverState.value = current.copy(addresses = addresses)
                        }
                        delay(2_000L)
                    }
                }

                while (currentCoroutineContext().isActive) {
                    val client = socket.accept()
                    scope.launch { handleClient(client) }
                }
            } catch (error: Exception) {
                if (started.get()) {
                    _serverState.value = RemoteServerState(
                        running = false,
                        addresses = emptyList(),
                        pin = pin,
                        errorMessage = error.message?.takeIf { it.isNotBlank() }
                            ?: "Unable to start the remote control server.",
                    )
                }
            }
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        addressJob?.cancel()
        serverJob?.cancel()
        scope.cancel()
        _serverState.value = RemoteServerState(pin = pin)
    }

    fun updateMediaStatus(status: RemoteMediaStatus) {
        _mediaStatus.value = status
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            runCatching {
                if (!client.inetAddress.isSiteLocalAddress &&
                    !client.inetAddress.isLoopbackAddress
                ) {
                    writeResponse(
                        client,
                        403,
                        "Forbidden",
                        "text/plain; charset=utf-8",
                        "Local network access only",
                    )
                    return
                }

                client.soTimeout = 5_000
                val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                val requestLine = reader.readLine() ?: return
                val requestParts = requestLine.split(' ')
                if (requestParts.size < 2) {
                    writeResponse(client, 400, "Bad Request", "text/plain; charset=utf-8", "Bad request")
                    return
                }

                val method = requestParts[0].uppercase(Locale.US)
                val requestTarget = requestParts[1]
                val path = runCatching { URI(requestTarget).path }.getOrNull() ?: "/"
                val headers = mutableMapOf<String, String>()

                while (true) {
                    val headerLine = reader.readLine() ?: break
                    if (headerLine.isEmpty()) break
                    val separator = headerLine.indexOf(':')
                    if (separator > 0) {
                        headers[headerLine.substring(0, separator).lowercase(Locale.US)] =
                            headerLine.substring(separator + 1).trim()
                    }
                }

                when {
                    method == "GET" && path == "/" -> {
                        writeResponse(
                            client,
                            200,
                            "OK",
                            "text/html; charset=utf-8",
                            REMOTE_CONTROL_HTML,
                        )
                    }

                    method == "GET" && path == "/api/status" -> {
                        if (!isAuthorized(headers)) {
                            writeJson(client, 401, "Unauthorized", errorJson("Incorrect PIN."))
                        } else {
                            writeJson(client, 200, "OK", mediaStatusJson())
                        }
                    }

                    method == "POST" && path.startsWith("/api/command/") -> {
                        if (!isAuthorized(headers)) {
                            writeJson(client, 401, "Unauthorized", errorJson("Incorrect PIN."))
                        } else {
                            handleCommandRequest(client, path.removePrefix("/api/command/"))
                        }
                    }

                    else -> writeResponse(
                        client,
                        404,
                        "Not Found",
                        "text/plain; charset=utf-8",
                        "Not found",
                    )
                }
            }.onFailure {
                runCatching {
                    writeResponse(
                        client,
                        500,
                        "Internal Server Error",
                        "text/plain; charset=utf-8",
                        "Server error",
                    )
                }
            }
        }
    }

    private fun handleCommandRequest(
        client: Socket,
        commandPath: String,
    ) {
        val command = when (commandPath) {
            "start" -> RemoteMediaCommand.Start
            "play" -> RemoteMediaCommand.Play
            "pause" -> RemoteMediaCommand.Pause
            "previous" -> RemoteMediaCommand.Previous
            "next" -> RemoteMediaCommand.Next
            "stop" -> RemoteMediaCommand.Stop
            "interval/3" -> RemoteMediaCommand.SetImageInterval(3)
            "interval/5" -> RemoteMediaCommand.SetImageInterval(5)
            "interval/10" -> RemoteMediaCommand.SetImageInterval(10)
            else -> null
        }

        if (command == null) {
            writeJson(client, 404, "Not Found", errorJson("Unknown command."))
            return
        }

        val status = _mediaStatus.value
        if (command is RemoteMediaCommand.Start && !status.playlistReady) {
            writeJson(
                client,
                409,
                "Conflict",
                errorJson("Select media on the display first."),
            )
            return
        }

        val requiresActiveShow = command is RemoteMediaCommand.Play ||
            command is RemoteMediaCommand.Pause ||
            command is RemoteMediaCommand.Previous ||
            command is RemoteMediaCommand.Next ||
            command is RemoteMediaCommand.Stop
        if (requiresActiveShow && !status.active) {
            writeJson(client, 409, "Conflict", errorJson("Media Show is not running."))
            return
        }

        if (!_commands.tryEmit(command)) {
            writeJson(client, 503, "Service Unavailable", errorJson("Display is busy."))
            return
        }

        writeJson(
            client,
            202,
            "Accepted",
            JSONObject().put("ok", true).put("message", "Command accepted.").toString(),
        )
    }

    private fun isAuthorized(headers: Map<String, String>): Boolean =
        headers["x-adps-pin"] == pin

    private fun mediaStatusJson(): String {
        val status = _mediaStatus.value
        return JSONObject()
            .put("ok", true)
            .put("playlistReady", status.playlistReady)
            .put("active", status.active)
            .put("paused", status.paused)
            .put("currentTitle", status.currentTitle)
            .put("currentPosition", status.currentPosition)
            .put("itemCount", status.itemCount)
            .put("imageIntervalSeconds", status.imageIntervalSeconds)
            .toString()
    }

    private fun errorJson(message: String): String = JSONObject()
        .put("ok", false)
        .put("message", message)
        .toString()

    private fun writeJson(
        socket: Socket,
        statusCode: Int,
        reason: String,
        body: String,
    ) {
        writeResponse(
            socket = socket,
            statusCode = statusCode,
            reason = reason,
            contentType = "application/json; charset=utf-8",
            body = body,
        )
    }

    private fun writeResponse(
        socket: Socket,
        statusCode: Int,
        reason: String,
        contentType: String,
        body: String,
    ) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $statusCode $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)

        socket.getOutputStream().use { output ->
            output.write(headers)
            output.write(bodyBytes)
            output.flush()
        }
    }

    @Suppress("DEPRECATION")
    private fun findPrivateIpv4Addresses(): List<String> = runCatching {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeAddresses = connectivityManager.allNetworks
            .sortedBy { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                when {
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 0
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> 1
                    else -> 2
                }
            }
            .flatMap { network ->
                connectivityManager.getLinkProperties(network)
                    ?.linkAddresses
                    ?.map { linkAddress -> linkAddress.address }
                    .orEmpty()
            }
            .filterIsInstance<Inet4Address>()
            .filter { address -> address.isSiteLocalAddress && !address.isLoopbackAddress }
            .mapNotNull { address -> address.hostAddress }
            .distinct()

        if (activeAddresses.isNotEmpty()) return@runCatching activeAddresses

        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { network -> network.isUp && !network.isLoopback }
            .flatMap { network -> Collections.list(network.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { address -> address.isSiteLocalAddress && !address.isLoopbackAddress }
            .mapNotNull { address -> address.hostAddress }
            .distinct()
            .sortedWith(
                compareBy<String> { address ->
                    when {
                        address.startsWith("192.168.") -> 0
                        address.startsWith("10.") -> 1
                        else -> 2
                    }
                }.thenBy { it },
            )
            .toList()
    }.getOrDefault(emptyList())
}

@Composable
internal fun RemoteControlDialog(
    state: RemoteServerState,
    onDismiss: () -> Unit,
) {
    val primaryUrl = state.primaryUrl
    val qrUrl = primaryUrl?.let { "$it/?pin=${state.pin}" }
    val qrBitmap = remember(qrUrl) {
        qrUrl?.let { createRemoteControlQrBitmap(it, 520) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ),
            ) {
                Text("Close")
            }
        },
        title = {
            Text(
                text = "Smartphone Remote Control",
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    state.errorMessage != null -> Text(
                        text = state.errorMessage,
                        color = Color(0xFFEF9A9A),
                        textAlign = TextAlign.Center,
                    )

                    !state.running -> Text(
                        text = "Starting remote control server...",
                        textAlign = TextAlign.Center,
                    )

                    primaryUrl == null -> Text(
                        text = "Connect this display to Wi-Fi, then open this window again.",
                        textAlign = TextAlign.Center,
                    )

                    else -> {
                        Text(
                            text = "Connect your phone to the same Wi-Fi and scan the QR code.",
                            color = Color(0xFFBDBDBD),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        qrBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Remote control QR code",
                                modifier = Modifier
                                    .size(230.dp)
                                    .background(Color.White, RoundedCornerShape(8.dp))
                                    .padding(8.dp),
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                        }

                        Text(
                            text = primaryUrl,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = "PIN", color = Color(0xFFBDBDBD))
                            Text(
                                text = state.pin,
                                color = Color.White,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 4.sp,
                            )
                        }

                        if (state.addresses.size > 1) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = state.addresses.drop(1)
                                    .joinToString(separator = "\n") {
                                        "http://$it:$REMOTE_CONTROL_PORT"
                                    },
                                color = Color(0xFF8C8C8C),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        },
    )
}

private fun createRemoteControlQrBitmap(
    value: String,
    size: Int,
): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
        }
    }

    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}.getOrNull()

private val REMOTE_CONTROL_HTML = """
    <!doctype html>
    <html lang="en">
    <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
      <title>ADPS Remote Control</title>
      <style>
        :root { color-scheme: dark; font-family: system-ui, -apple-system, sans-serif; }
        * { box-sizing: border-box; }
        body { margin: 0; min-height: 100vh; background: #000; color: #fff; display: grid; place-items: center; }
        main { width: min(100%, 520px); padding: 24px; }
        h1 { margin: 0 0 6px; font-size: 30px; }
        .sub { margin: 0 0 22px; color: #9e9e9e; }
        .card { background: #141414; border: 1px solid #363636; border-radius: 18px; padding: 18px; margin-bottom: 16px; }
        .login { display: grid; grid-template-columns: 1fr auto; gap: 10px; }
        input { width: 100%; border: 1px solid #555; border-radius: 12px; background: #050505; color: #fff; padding: 14px; font-size: 20px; letter-spacing: 5px; text-align: center; }
        button { min-height: 54px; border: 0; border-radius: 12px; background: #292929; color: #fff; font-size: 16px; font-weight: 650; padding: 12px 16px; touch-action: manipulation; }
        button.primary { background: #fff; color: #000; }
        button.stop { background: #5b1d1d; }
        button:disabled { opacity: .34; }
        .grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
        .wide { grid-column: 1 / -1; }
        .status-row { display: flex; justify-content: space-between; gap: 12px; margin: 8px 0; }
        .label { color: #9e9e9e; }
        #title { overflow-wrap: anywhere; text-align: right; }
        #message { min-height: 24px; margin: 12px 0 0; color: #bdbdbd; text-align: center; }
        .intervals { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-top: 10px; }
        .selected { outline: 2px solid #fff; }
      </style>
    </head>
    <body>
      <main>
        <h1>ADPS Remote</h1>
        <p class="sub">Media Show controller</p>

        <section class="card" id="loginCard">
          <div class="login">
            <input id="pin" inputmode="numeric" maxlength="6" placeholder="6-digit PIN" aria-label="PIN">
            <button class="primary" id="connect">Connect</button>
          </div>
        </section>

        <section class="card">
          <div class="status-row"><span class="label">Status</span><strong id="status">Not connected</strong></div>
          <div class="status-row"><span class="label">Media</span><strong id="title">-</strong></div>
          <div class="status-row"><span class="label">Position</span><strong id="position">-</strong></div>
          <div class="status-row"><span class="label">Image interval</span><strong id="interval">5s</strong></div>
        </section>

        <section class="card grid">
          <button class="primary wide" id="start" onclick="sendCommand('start')">Start Media Show</button>
          <button id="previous" onclick="sendCommand('previous')">Previous</button>
          <button id="next" onclick="sendCommand('next')">Next</button>
          <button id="play" onclick="sendCommand('play')">Play</button>
          <button id="pause" onclick="sendCommand('pause')">Pause</button>
          <button class="stop wide" id="stop" onclick="sendCommand('stop')">Stop Media Show</button>
        </section>

        <section class="card">
          <span class="label">Photo interval</span>
          <div class="intervals">
            <button id="interval3" onclick="sendCommand('interval/3')">3s</button>
            <button id="interval5" onclick="sendCommand('interval/5')">5s</button>
            <button id="interval10" onclick="sendCommand('interval/10')">10s</button>
          </div>
          <p id="message"></p>
        </section>
      </main>

      <script>
        const pinInput = document.getElementById('pin');
        const message = document.getElementById('message');
        let pin = '';
        let pollTimer = null;

        function setMessage(text, isError) {
          message.textContent = text || '';
          message.style.color = isError ? '#ef9a9a' : '#bdbdbd';
        }

        async function request(path, options) {
          const response = await fetch(path, Object.assign({
            headers: { 'X-ADPS-PIN': pin },
            cache: 'no-store'
          }, options || {}));
          const data = await response.json();
          if (!response.ok) throw new Error(data.message || 'Request failed.');
          return data;
        }

        async function refreshStatus() {
          if (!pin) return;
          try {
            const data = await request('/api/status');
            document.getElementById('status').textContent = data.active ? (data.paused ? 'Paused' : 'Playing') : (data.playlistReady ? 'Ready' : 'Select media on display');
            document.getElementById('title').textContent = data.currentTitle || '-';
            document.getElementById('position').textContent = data.active ? data.currentPosition + ' / ' + data.itemCount : '-';
            document.getElementById('interval').textContent = data.imageIntervalSeconds + 's';
            document.getElementById('start').disabled = !data.playlistReady || data.active;
            ['previous', 'next', 'play', 'pause', 'stop'].forEach(function(id) {
              document.getElementById(id).disabled = !data.active;
            });
            [3, 5, 10].forEach(function(seconds) {
              document.getElementById('interval' + seconds).classList.toggle('selected', data.imageIntervalSeconds === seconds);
            });
            setMessage('Connected', false);
          } catch (error) {
            setMessage(error.message, true);
            document.getElementById('status').textContent = 'Disconnected';
          }
        }

        async function sendCommand(command) {
          if (!pin) return;
          try {
            const data = await request('/api/command/' + command, { method: 'POST' });
            setMessage(data.message, false);
            window.setTimeout(refreshStatus, 150);
          } catch (error) {
            setMessage(error.message, true);
          }
        }

        function connect() {
          const candidate = pinInput.value.trim();
          if (candidate.length !== 6 || /\D/.test(candidate)) {
            setMessage('Enter the 6-digit PIN shown on the display.', true);
            return;
          }
          pin = candidate;
          sessionStorage.setItem('adpsRemotePin', pin);
          refreshStatus();
          if (pollTimer) window.clearInterval(pollTimer);
          pollTimer = window.setInterval(refreshStatus, 1000);
        }

        document.getElementById('connect').addEventListener('click', connect);
        pinInput.addEventListener('keydown', function(event) {
          if (event.key === 'Enter') connect();
        });

        const queryPin = new URLSearchParams(window.location.search).get('pin');
        const rememberedPin = sessionStorage.getItem('adpsRemotePin');
        if (queryPin || rememberedPin) {
          pinInput.value = queryPin || rememberedPin;
          connect();
        }
      </script>
    </body>
    </html>
""".trimIndent()
