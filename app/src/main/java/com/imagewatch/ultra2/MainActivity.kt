package com.imagewatch.ultra2

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Ultra2 Lab: laboratorio para explorar el reloj. Todo lo que pasa se anota en un registro con hora
 * (se guarda en Descargas con "Guardar log"). Los comandos replican lo observado en la captura de HiwatchPro
 * y lo desensamblado del APK. SIN PROBAR en el reloj real al escribir esto.
 */
@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    companion object {
        const val WATCH_MAC = "27:E2:F7:00:08:ED"
        const val REQ_PERMS = 1
        val UUID_NUS_TX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9d")
        val UUID_NUS_RX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9d")
        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val SCAN_KEYS = intArrayOf(
            0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10,
            0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x20, 0x21, 0x22, 0x23, 0x25, 0x26, 0x27, 0x28
        )
    }

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val ackIo = Executors.newSingleThreadExecutor()
    private val writeLock = Any()

    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var scanCb: ScanCallback? = null
    private val seen = HashSet<String>()

    private val writeSem = Semaphore(0)
    private val descSem = Semaphore(0)
    private val readSem = Semaphore(0)
    private val rxQueue = LinkedBlockingQueue<Pair<Long, ByteArray>>()
    private val rxParser = RxParser()
    private val pendingCccd = ArrayDeque<BluetoothGattDescriptor>()
    @Volatile private var ready = false
    @Volatile private var busy = false
    private var cbWorks = true
    private var cbMiss = 0

    private val logBuf = StringBuilder()
    private val tf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var statusView: TextView
    private lateinit var spIcon: Spinner
    private lateinit var etTitle: EditText
    private lateinit var etBody: EditText
    private lateinit var etNote: EditText
    private lateinit var etG: EditText
    private lateinit var etK: EditText
    private lateinit var etD: EditText

    // ------------------------------------------------------------------ UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(12)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#0a0e14"))
        root.setPadding(pad, dp(28), pad, pad)

        root.addView(label("Ultra2 Lab v6", 20f, "#00e87a"))
        statusView = label("Desconectado", 12f, "#ffb347")
        root.addView(statusView)

        val row1 = row()
        row1.addView(button("Conectar") { connect() }, weight())
        row1.addView(button("Guardar log") { saveLog() }, weight())
        row1.addView(button("Esfera…") { openDial() }, weight())
        root.addView(row1)

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL

        // ---- Texto
        panel.addView(section("Texto al reloj (notificación)"))
        spIcon = Spinner(this)
        val names = Fitpro.APP_CODES.map { "${it.first} (${it.second})" }
        spIcon.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        panel.addView(spIcon)
        etTitle = EditText(this)
        etTitle.hint = "Título (opcional)"
        etTitle.setTextColor(Color.WHITE)
        etTitle.setHintTextColor(Color.parseColor("#667788"))
        panel.addView(etTitle)
        etBody = EditText(this)
        etBody.hint = "Texto (máx. 300 bytes)"
        etBody.setTextColor(Color.WHITE)
        etBody.setHintTextColor(Color.parseColor("#667788"))
        panel.addView(etBody)
        val presets = row()
        presets.addView(button("ASCII") { preset("Clara", "Hola, esto es una prueba 123") }, weight())
        presets.addView(button("Acentos") { preset("Ñandú", "¿Cómo estás? áéíóú ñ ü ¡Bien!") }, weight())
        presets.addView(button("Emoji") { preset("Emoji", "Prueba \uD83D\uDE00 ✅ ❤ \uD83D\uDC4D") }, weight())
        presets.addView(button("280") { preset("", "0123456789".repeat(28)) }, weight())
        panel.addView(presets)
        panel.addView(button("Enviar texto al reloj") { sendText() })

        // ---- Comandos replicados
        panel.addView(section("Comandos que manda HiwatchPro"))
        val r2 = row()
        r2.addView(button("Sincronizar hora") { sendOne("Sincronizar hora", Fitpro.timeSync(Calendar.getInstance())) }, weight())
        r2.addView(button("PhoneType=1") { sendOne("PhoneType=1", Fitpro.phoneType()) }, weight())
        panel.addView(r2)
        val r3 = row()
        r3.addView(button("Buscar reloj ON") { sendOne("Buscar reloj ON", Fitpro.findWatch(true)) }, weight())
        r3.addView(button("Buscar reloj OFF") { sendOne("Buscar reloj OFF", Fitpro.findWatch(false)) }, weight())
        panel.addView(r3)

        // ---- Preparar notificaciones
        panel.addView(section("Notificaciones: preparar el reloj"))
        val rn = row()
        rn.addView(button("Leer interruptores") { sendOne("Leer interruptores (get 0x07)", Fitpro.readNotifySwitches()) }, weight())
        rn.addView(button("Pair") { sendOne("Pair (12 0A 02)", Fitpro.pair()) }, weight())
        rn.addView(button("Activar todos") { sendOne("Interruptores ON (12 07)", Fitpro.notifySwitches(true)) }, weight())
        panel.addView(rn)
        panel.addView(button("PRUEBA GUIADA DE TEXTO (6 pasos)") { pruebaTexto() })

        // ---- Consultas
        panel.addView(section("Consultas (solo lectura)"))
        val r4 = row()
        r4.addView(button("Info pantalla") { infoPantalla() }, weight())
        r4.addView(button("MAC") { sendOne("Consulta MAC", Fitpro.get(0x0A)) }, weight())
        panel.addView(r4)
        val r5 = row()
        r5.addView(button("Leer info GATT") { leerGatt() }, weight())
        r5.addView(button("Latencia x20") { latencia() }, weight())
        panel.addView(r5)
        panel.addView(button("Escanear claves (solo lectura)") { escanearClaves() })

        // ---- Escuchar
        panel.addView(section("Escuchar al reloj"))
        panel.addView(button("Escuchar TODO (puede pedir emparejar)") { suscribirTodo() })
        panel.addView(label("Pulsa botones/gestos del reloj y mira el log.", 11f, "#667788"))

        // ---- Manual y notas
        panel.addView(section("Comando manual (hex)"))
        val rm = row()
        etG = EditText(this); etG.hint = "grupo"; etG.setText("12"); etG.setTextColor(Color.WHITE); etG.setHintTextColor(Color.parseColor("#667788"))
        etK = EditText(this); etK.hint = "clave"; etK.setTextColor(Color.WHITE); etK.setHintTextColor(Color.parseColor("#667788"))
        etD = EditText(this); etD.hint = "datos"; etD.setTextColor(Color.WHITE); etD.setHintTextColor(Color.parseColor("#667788"))
        rm.addView(etG, weight())
        rm.addView(etK, weight())
        rm.addView(etD, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        panel.addView(rm)
        panel.addView(button("Enviar comando manual") { enviarManual() })
        panel.addView(section("Marcar evento en el log"))
        etNote = EditText(this)
        etNote.hint = "ej: vi aparecer T1 en el reloj"
        etNote.setTextColor(Color.WHITE)
        etNote.setHintTextColor(Color.parseColor("#667788"))
        panel.addView(etNote)
        panel.addView(button("Marcar") { log("### NOTA: " + etNote.text.toString()); etNote.setText("") })

        val sv = ScrollView(this)
        sv.addView(panel)
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        logView = TextView(this)
        logView.setTextColor(Color.parseColor("#9fb0c0"))
        logView.textSize = 10f
        logView.typeface = Typeface.MONOSPACE
        logScroll = ScrollView(this)
        logScroll.setBackgroundColor(Color.parseColor("#05080c"))
        logScroll.addView(logView)
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)))

        setContentView(root)
        log("Cierra HiwatchPro antes de conectar (el reloj acepta un solo cliente BLE).")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(Color.parseColor(color))
        t.setPadding(0, dp(2), 0, dp(2))
        return t
    }

    private fun section(text: String): TextView {
        val t = label(text, 13f, "#00e87a")
        t.setPadding(0, dp(12), 0, dp(2))
        return t
    }

    private fun row(): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        return r
    }

    private fun weight(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 11f
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun preset(title: String, body: String) {
        etTitle.setText(title)
        etBody.setText(body)
    }

    private fun setStatus(s: String) {
        ui.post { statusView.text = s }
    }

    private fun log(msg: String) {
        val line = tf.format(Date()) + "  " + msg + "\n"
        synchronized(logBuf) { logBuf.append(line) }
        ui.post {
            logView.append(line)
            if (logView.length() > 24000) logView.text = logView.text.toString().takeLast(16000)
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun saveLog() {
        val name = "ultra2-lab-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val text = synchronized(logBuf) { logBuf.toString() }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues()
                v.put(MediaStore.Downloads.DISPLAY_NAME, name)
                v.put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                if (uri == null) {
                    log("✗ No pude crear el archivo de log")
                    return
                }
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                log("✓ Log guardado en Descargas/$name")
            } else {
                val f = File(getExternalFilesDir(null), name)
                f.writeText(text)
                log("✓ Log guardado en ${f.absolutePath}")
            }
        } catch (e: Exception) {
            log("✗ Error guardando el log: ${e.message}")
        }
    }

    private fun openDial() {
        gatt?.close()
        gatt = null
        ready = false
        txChar = null
        setStatus("Desconectado (abriendo esfera)")
        startActivity(Intent(this, DialActivity::class.java))
    }

    // ------------------------------------------------------------------ permisos

    private fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPerms(): Boolean =
        neededPerms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            if (hasPerms()) log("Permisos concedidos. Pulsa Conectar otra vez.")
            else log("Faltan permisos: Ajustes > Apps > Ultra2 Lab > Permisos > Dispositivos cercanos (Android 11 o menor: Ubicación).")
        }
    }

    // ------------------------------------------------------------------ conexión

    private fun connect() {
        if (!hasPerms()) {
            log("Pidiendo permisos…")
            requestPermissions(neededPerms(), REQ_PERMS)
            return
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth apagado o no disponible")
            return
        }
        val old = gatt
        if (old != null) {
            old.close()
            gatt = null
        }
        txChar = null
        ready = false
        seen.clear()
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            log("No hay escáner BLE")
            return
        }
        setStatus("Escaneando…")
        log("Escaneando 10 s… (Android 11 o menor: Ubicación activada)")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                val name: String = dev.name ?: result.scanRecord?.deviceName ?: "?"
                if (seen.add(dev.address)) log("  · $name  ${dev.address}  ${result.rssi} dBm")
                val esReloj = dev.address.equals(WATCH_MAC, true) ||
                    name.contains("ULTRA", true) || name.contains("LS7076", true)
                if (esReloj && scanning) {
                    stopScan()
                    log("Reloj encontrado: $name ${dev.address}")
                    abrirGatt(dev)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                log("Fallo de escaneo, código $errorCode")
            }
        }
        scanCb = cb
        scanning = true
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        ui.postDelayed({
            if (scanning) {
                stopScan()
                log("No apareció en el escaneo. Intento directo por MAC $WATCH_MAC")
                abrirGatt(adapter.getRemoteDevice(WATCH_MAC))
            }
        }, 10000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val cb = scanCb
            if (cb != null) adapter.bluetoothLeScanner?.stopScan(cb)
        } catch (e: Exception) {
            log("stopScan: ${e.message}")
        }
    }

    private fun abrirGatt(dev: BluetoothDevice) {
        if (gatt != null) return
        setStatus("Conectando…")
        log("Conectando a ${dev.address}…")
        gatt = dev.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun siguienteCccd(g: BluetoothGatt) {
        val d = pendingCccd.removeFirstOrNull()
        if (d != null) g.writeDescriptor(d)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Conectado (status $status). Descubriendo servicios (sin tocar el MTU)…")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Desconectado (status $status)")
                setStatus("Desconectado")
                ready = false
                g.close()
                ui.post {
                    if (gatt === g) {
                        gatt = null
                        txChar = null
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Descubrimiento falló: $status")
                return
            }
            var tx: BluetoothGattCharacteristic? = null
            var rx: BluetoothGattCharacteristic? = null
            for (s in g.services) {
                log("Servicio ${s.uuid}")
                for (c in s.characteristics) {
                    log("   car ${c.uuid} props=0x${Integer.toHexString(c.properties)}")
                    if (c.uuid == UUID_NUS_TX) tx = c
                    if (c.uuid == UUID_NUS_RX) rx = c
                }
            }
            if (tx == null || rx == null) {
                log("✗ No encontré NUS (6e400002/6e400003) en este reloj")
                return
            }
            txChar = tx
            val d = rx.getDescriptor(UUID_CCCD)
            if (d == null || !g.setCharacteristicNotification(rx, true)) {
                log("✗ No pude activar las notificaciones de NUS RX")
                return
            }
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val u = d.characteristic.uuid
            if (u == UUID_NUS_RX && status == 0 && !ready) {
                ready = true
                setStatus("Conectado · listo")
                log("Notificaciones NUS RX activas. Listo.")
            } else {
                log("  notif ${u.toString().substring(0, 8)}: " + (if (status == 0) "ok" else "error $status"))
            }
            descSem.release()
            siguienteCccd(g)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            recibir(c.uuid, c.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            recibir(c.uuid, value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeSem.release()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            lecturaTerminada(c.uuid, c.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            lecturaTerminada(c.uuid, value, status)
        }
    }

    private fun lecturaTerminada(u: UUID, v: ByteArray, status: Int) {
        val ascii = String(v.map { if (it in 32..126) it.toInt().toChar() else '.' }.toCharArray())
        log("  READ ${u.toString().substring(0, 8)} st=$status ${Fitpro.hex(v, 24)}  \"$ascii\"")
        readSem.release()
    }

    private fun recibir(u: UUID, b: ByteArray) {
        if (u != UUID_NUS_RX) {
            log("NOTIF ${u.toString().substring(0, 8)}: ${Fitpro.hex(b)}")
            return
        }
        val now = System.nanoTime()
        for (f in rxParser.feed(b)) {
            log("RX  ${Fitpro.hex(f)}   | ${Fitpro.describe(f)}")
            rxQueue.add(Pair(now, f))
            if (f[0] == 0xCD.toByte() && f[3] == Fitpro.G_SPORT.toByte()) {
                ackIo.execute { sendRaw(Fitpro.ACK_SPORT, "ack deporte", false) }
            }
        }
    }

    // ------------------------------------------------------------------ envío

    private fun writeChunk(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.value = data
        writeSem.drainPermits()
        var ok = g.writeCharacteristic(ch)
        var tries = 0
        while (!ok && tries < 100) {
            Thread.sleep(10)
            ok = g.writeCharacteristic(ch)
            tries++
        }
        if (!ok) return false
        if (cbWorks) {
            if (writeSem.tryAcquire(60, TimeUnit.MILLISECONDS)) {
                cbMiss = 0
            } else {
                cbMiss++
                if (cbMiss >= 5) {
                    cbWorks = false
                    log("Sin confirmaciones de escritura del sistema: pausa fija de 12 ms")
                }
            }
        } else {
            Thread.sleep(12)
        }
        return true
    }

    /** Envía una trama completa en fragmentos de 20 bytes. Bloquea; llamar fuera del hilo de la interfaz. */
    private fun sendRaw(frame: ByteArray, label: String, logIt: Boolean = true): Boolean {
        val g = gatt
        val ch = txChar
        if (g == null || ch == null || !ready) {
            log("✗ No conectado (pulsa Conectar y espera \"listo\")")
            return false
        }
        synchronized(writeLock) {
            if (logIt) log("TX  ${Fitpro.hex(frame)}   | $label")
            var p = 0
            while (p < frame.size) {
                val e = minOf(p + DialProtocol.CHUNK, frame.size)
                if (!writeChunk(g, ch, frame.copyOfRange(p, e))) {
                    log("✗ Escritura rechazada: el GATT está bloqueado o se desconectó. Pulsa Conectar para reiniciar.")
                    ready = false
                    setStatus("GATT bloqueado: reconecta")
                    g.disconnect()
                    return false
                }
                p = e
            }
        }
        return true
    }

    private fun runTask(name: String, body: () -> Unit) {
        if (busy) {
            log("Ocupado: espera a que termine la prueba anterior")
            return
        }
        busy = true
        io.execute {
            try {
                body()
            } catch (e: Exception) {
                log("✗ $name: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    private fun sendOne(label: String, frame: ByteArray) {
        runTask(label) { exchange(label, frame, 1500) }
    }

    /** Envía y espera la respuesta del mismo grupo/clave (DC con la clave o CD con la clave). */
    private fun exchange(label: String, frame: ByteArray, timeoutMs: Long): Boolean {
        val g = frame[3]
        val k = frame[5]
        val (f, ms) = request(frame, label, timeoutMs) {
            it.size > 5 && it[3] == g && ((it[0] == 0xDC.toByte() && it[4] == k) || (it[0] == 0xCD.toByte() && it[5] == k))
        }
        if (f == null) log("  ✗ sin respuesta en $timeoutMs ms")
        else log("  ✓ respuesta en $ms ms: ${Fitpro.hex(f)} | ${Fitpro.describe(f)}")
        return f != null
    }

    private fun hexToBytes(s: String): ByteArray? {
        val t = s.filter { it.isLetterOrDigit() }
        if (t.length % 2 != 0) return null
        return try {
            ByteArray(t.length / 2) { t.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        } catch (e: Exception) {
            null
        }
    }

    private fun enviarManual() {
        val g = hexToBytes(etG.text.toString())
        val k = hexToBytes(etK.text.toString())
        val d = hexToBytes(etD.text.toString())
        if (g == null || k == null || d == null || g.size != 1 || k.size != 1) {
            log("Manual: grupo y clave son 1 byte en hex (ej. 12 y 07); datos en hex pares o vacío")
            return
        }
        val frame = Fitpro.frame(g[0].toInt() and 0xFF, k[0].toInt() and 0xFF, d)
        sendOne("manual", frame)
    }

    private fun pruebaTexto() {
        runTask("Prueba guiada") {
            log("=== PRUEBA GUIADA: mira la pantalla del reloj y anota qué textos aparecen ===")
            log("Paso 1/6: leer interruptores actuales (grupo 0x1A, clave 0x07)")
            exchange("get interruptores", Fitpro.readNotifySwitches(), 2500)
            Thread.sleep(1500)
            log("Paso 2/6: Pair (12 0A 02), lo primero que manda HiwatchPro")
            exchange("Pair", Fitpro.pair(), 2500)
            Thread.sleep(1500)
            log("Paso 3/6: activar interruptores de notificación (12 07 con 11 bytes = 01)")
            exchange("Interruptores ON", Fitpro.notifySwitches(true), 2500)
            Thread.sleep(1500)
            log("Paso 4/6: texto T1 con icono SMS (1) — ¿aparece \"Lab:T1 icono SMS\"?")
            exchange("T1 SMS", Fitpro.notify(1, Fitpro.composeText("Lab", "T1 icono SMS")), 3000)
            Thread.sleep(6000)
            log("Paso 5/6: texto T2 con icono WhatsApp (8) — ¿aparece \"Lab:T2 icono WhatsApp\"?")
            exchange("T2 WhatsApp", Fitpro.notify(8, Fitpro.composeText("Lab", "T2 icono WhatsApp")), 3000)
            Thread.sleep(6000)
            log("Paso 6/6: releer interruptores")
            exchange("get interruptores", Fitpro.readNotifySwitches(), 2500)
            log("=== FIN. ¿Apareció T1 y/o T2 en el reloj? Escribe una NOTA y pulsa Guardar log ===")
        }
    }

    /** Envía una consulta y espera la primera trama que cumpla [match]. Devuelve (trama, ms). */
    private fun request(frame: ByteArray, label: String, timeoutMs: Long, match: (ByteArray) -> Boolean): Pair<ByteArray?, Long> {
        rxQueue.clear()
        val t0 = System.nanoTime()
        if (!sendRaw(frame, label)) return Pair(null, -1L)
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) return Pair(null, -1L)
            val f = rxQueue.poll(left, TimeUnit.MILLISECONDS) ?: return Pair(null, -1L)
            if (match(f.second)) return Pair(f.second, (f.first - t0) / 1_000_000)
        }
    }

    // ------------------------------------------------------------------ acciones

    private fun sendText() {
        val title = etTitle.text.toString()
        val body = etBody.text.toString()
        if (body.isBlank()) {
            log("Escribe un texto primero (o pulsa un preset)")
            return
        }
        val code = Fitpro.APP_CODES[spIcon.selectedItemPosition].second
        val text = Fitpro.composeText(title, body)
        val frame = Fitpro.notify(code, text)
        val bytes = text.toByteArray(Charsets.UTF_8).size
        runTask("Texto") {
            log("Texto: ${text.length} caracteres, $bytes bytes UTF-8, icono código $code")
            if (sendRaw(frame, "NotifyMsgPush")) {
                val t0 = System.nanoTime()
                val r = waitResponse(0x12, Fitpro.K_NOTIFY, 2000)
                log(if (r) "  respuesta del reloj en ${(System.nanoTime() - t0) / 1_000_000} ms" else "  sin respuesta del reloj en 2 s (mira si apareció en pantalla)")
            }
        }
    }

    private fun waitResponse(group: Int, key: Int, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) return false
            val f = rxQueue.poll(left, TimeUnit.MILLISECONDS) ?: return false
            val b = f.second
            if (b[0] == 0xDC.toByte() && (b[3].toInt() and 0xFF) == group && (b[4].toInt() and 0xFF) == key) return true
        }
    }

    private fun infoPantalla() {
        runTask("Info pantalla") {
            val (f, ms) = request(Fitpro.readDialInfo(), "DialReadInfo", 2500) {
                it[0] == 0xCD.toByte() && it[3] == Fitpro.G_DIAL_READ.toByte() && it[5] == 0x02.toByte()
            }
            if (f == null) {
                log("✗ Sin respuesta")
            } else {
                val d = f.copyOfRange(8, f.size)
                log("  respuesta en $ms ms, ${d.size} bytes de datos")
                if (d.size >= 6) {
                    val w = ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)
                    val h = ((d[4].toInt() and 0xFF) shl 8) or (d[5].toInt() and 0xFF)
                    log("  interpretación probable: pantalla ${w}x$h")
                }
                val ascii = String(d.map { if (it in 32..126) it.toInt().toChar() else '.' }.toCharArray())
                log("  ASCII: $ascii")
            }
        }
    }

    private fun latencia() {
        runTask("Latencia") {
            val ts = ArrayList<Long>()
            var lost = 0
            for (i in 1..20) {
                val (f, ms) = request(Fitpro.get(0x0A), "ping $i", 2000) {
                    it[0] == 0xCD.toByte() && it[3] == Fitpro.G_GET.toByte() && it[5] == 0x0A.toByte()
                }
                if (f == null) lost++ else ts.add(ms)
                Thread.sleep(150)
            }
            if (ts.isEmpty()) {
                log("✗ Latencia: ninguna respuesta (perdidas $lost)")
            } else {
                log("Latencia ida y vuelta (incluye la escritura): mín ${ts.min()} ms, media ${ts.sum() / ts.size} ms, máx ${ts.max()} ms, perdidas $lost de 20")
            }
        }
    }

    private fun escanearClaves() {
        runTask("Escaneo") {
            log("Escaneo de ${SCAN_KEYS.size} claves en el grupo 0x1A (consultas sin datos)…")
            var n = 0
            for (k in SCAN_KEYS) {
                val (f, ms) = request(Fitpro.get(k), "get 0x%02X ${Names.key(Fitpro.G_SETTING, k)}".format(k), 1200) {
                    (it[0] == 0xCD.toByte() && it[3] == Fitpro.G_GET.toByte() && it[5] == k.toByte()) ||
                        (it[0] == 0xDC.toByte() && it[3] == Fitpro.G_GET.toByte() && it[4] == k.toByte())
                }
                if (f != null) {
                    n++
                    log("  → clave 0x%02X respondió en $ms ms".format(k))
                } else {
                    log("  → clave 0x%02X sin respuesta".format(k))
                }
                Thread.sleep(250)
            }
            log("Escaneo terminado: $n de ${SCAN_KEYS.size} claves respondieron")
        }
    }

    private fun leerGatt() {
        val g = gatt
        if (g == null || !ready) {
            log("Primero conecta")
            return
        }
        runTask("Leer GATT") {
            log("Leyendo características legibles de Información del dispositivo y Batería…")
            for (s in g.services) {
                val id = s.uuid.toString().substring(0, 8)
                if (id != "0000180a" && id != "0000180f") continue
                for (c in s.characteristics) {
                    if ((c.properties and BluetoothGattCharacteristic.PROPERTY_READ) == 0) continue
                    readSem.drainPermits()
                    if (!g.readCharacteristic(c)) {
                        log("  no pude leer ${c.uuid}")
                        continue
                    }
                    if (!readSem.tryAcquire(2000, TimeUnit.MILLISECONDS)) log("  sin respuesta leyendo ${c.uuid}")
                }
            }
            log("Lectura terminada")
        }
    }

    private fun suscribirTodo() {
        val g = gatt
        if (g == null || !ready) {
            log("Primero conecta")
            return
        }
        runTask("Escuchar todo") {
            log("Activando notificaciones en todas las características que las ofrecen…")
            pendingCccd.clear()
            for (s in g.services) {
                if (s.uuid.toString().startsWith("00001812")) {
                    log("  (HID 0x1812 omitido: exige emparejar y bloquea el GATT si no responde)")
                    continue
                }
                for (c in s.characteristics) {
                    if (c.uuid == UUID_NUS_RX) continue
                    val notify = (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    val indicate = (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    if (!notify && !indicate) continue
                    val d = c.getDescriptor(UUID_CCCD) ?: continue
                    if (!g.setCharacteristicNotification(c, true)) continue
                    d.value = if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    pendingCccd.addLast(d)
                }
            }
            log("  ${pendingCccd.size} características para suscribir")
            descSem.drainPermits()
            val total = pendingCccd.size
            siguienteCccd(g)
            for (i in 0 until total) {
                if (!descSem.tryAcquire(5000, TimeUnit.MILLISECONDS)) {
                    log("  tiempo agotado esperando una suscripción (¿pide emparejar?)")
                    break
                }
            }
            log("Escuchando. Pulsa botones del reloj, abre su cámara/música, etc.")
        }
    }

    override fun onDestroy() {
        stopScan()
        gatt?.close()
        gatt = null
        io.shutdownNow()
        ackIo.shutdownNow()
        super.onDestroy()
    }
}
