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
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * ImageWatch v2: sube una imagen como esfera al Ultra2 imitando a HiwatchPro (según su captura HCI):
 * canal NUS (6e400002 / 6e400003), sin cambiar el MTU, fragmentos de 20 bytes y un bloque por confirmación.
 * Solo se activan las notificaciones de NUS RX, igual que HiwatchPro.
 *
 * SIN PROBAR en el reloj real al escribir esto.
 */
@SuppressLint("MissingPermission")
class DialActivity : Activity() {

    companion object {
        const val WATCH_MAC = "27:E2:F7:00:08:ED"
        const val REQ_PERMS = 1
        const val REQ_IMAGE = 2
        val UUID_NUS_TX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9d")
        val UUID_NUS_RX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9d")
        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val ui = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var scanCb: ScanCallback? = null
    private val seen = HashSet<String>()

    private val writeSem = Semaphore(0)
    private val rxQueue = LinkedBlockingQueue<ByteArray>()
    private val rxParser = RxParser()
    @Volatile private var ready = false
    @Volatile private var uploading = false
    private val mtuSem = Semaphore(0)
    @Volatile private var mtu = 23
    private var cbWorks = true
    private var cbMiss = 0

    private var dialFile: ByteArray? = null

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var preview: ImageView
    private lateinit var bar: ProgressBar
    private lateinit var btnSend: Button
    private lateinit var rgClock: RadioGroup
    private lateinit var chkFast: CheckBox

    // ------------------------------------------------------------------ UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#0a0e14"))
        root.setPadding(pad, dp(32), pad, pad)

        root.addView(label("Esfera (experimental)", 20f, "#00e87a"))
        root.addView(label("Sube la imagen como esfera. Dura ~75 s: no cierres la app.", 12f, "#ffb347"))

        preview = ImageView(this)
        preview.setBackgroundColor(Color.parseColor("#1a1f2a"))
        preview.scaleType = ImageView.ScaleType.FIT_CENTER
        val lp = LinearLayout.LayoutParams(dp(100), dp(120))
        lp.topMargin = dp(8)
        lp.bottomMargin = dp(8)
        root.addView(preview, lp)

        rgClock = RadioGroup(this)
        val etiquetas = arrayOf("Hora/fecha normal", "Glifos a cero (ocultar)", "Glifos en rojo (diagnóstico)")
        for (i in etiquetas.indices) {
            val r = RadioButton(this)
            r.id = 100 + i
            r.text = etiquetas[i]
            r.setTextColor(Color.parseColor("#9fb0c0"))
            rgClock.addView(r)
        }
        rgClock.check(100)
        root.addView(rgClock)
        chkFast = CheckBox(this)
        chkFast.text = "Escritura rápida (experimental)"
        chkFast.setTextColor(Color.parseColor("#9fb0c0"))
        root.addView(chkFast)
        root.addView(button("1. Elegir imagen") { pickImage() })
        root.addView(button("2. Conectar al reloj") { connect() })
        btnSend = button("3. Subir como esfera") { upload() }
        root.addView(btnSend)

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        root.addView(bar)

        logView = TextView(this)
        logView.setTextColor(Color.parseColor("#9fb0c0"))
        logView.textSize = 11f
        logView.typeface = Typeface.MONOSPACE
        scroll = ScrollView(this)
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        log("Cierra HiwatchPro antes de conectar: el reloj acepta un solo cliente BLE.")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(Color.parseColor(color))
        t.setPadding(0, dp(3), 0, dp(3))
        return t
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.setOnClickListener { onClick() }
        return b
    }

    private fun log(msg: String) {
        ui.post {
            logView.append(msg + "\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it) }

    // ------------------------------------------------------------------ permisos

    private fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPerms(): Boolean =
        neededPerms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            if (hasPerms()) log("Permisos concedidos. Pulsa Conectar otra vez.")
            else log("Faltan permisos: Ajustes > Apps > ImageWatch > Permisos > Dispositivos cercanos (Android 11 o menor: Ubicación).")
        }
    }

    // ------------------------------------------------------------------ imagen

    private fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "image/*"
        i.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(Intent.createChooser(i, "Imagen"), REQ_IMAGE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        procesar(uri)
    }

    private fun decode(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= DialEncoder.WIDTH * 2 &&
            bounds.outHeight / (sample * 2) >= DialEncoder.HEIGHT * 2
        ) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun procesar(uri: Uri) {
        dialFile = null
        log("Procesando imagen (recorte 5:6, ${DialEncoder.WIDTH}x${DialEncoder.HEIGHT}, ${DialEncoder.MAX_COLORS} colores)…")
        thread(name = "procesar") {
            try {
                val bmp = decode(uri)
                if (bmp == null) {
                    log("No pude leer la imagen")
                    return@thread
                }
                val w = DialEncoder.WIDTH
                val h = DialEncoder.HEIGHT
                val dstRatio = w.toFloat() / h
                var cw = bmp.width
                var ch = bmp.height
                if (bmp.width.toFloat() / bmp.height > dstRatio) cw = (bmp.height * dstRatio).toInt()
                else ch = (bmp.width / dstRatio).toInt()
                val cropped = Bitmap.createBitmap(bmp, (bmp.width - cw) / 2, (bmp.height - ch) / 2, cw, ch)
                val small = Bitmap.createScaledBitmap(cropped, w, h, true)
                val px = IntArray(w * h)
                small.getPixels(px, 0, w, 0, 0, w, h)
                val t0 = System.currentTimeMillis()
                val enc = DialEncoder.encode(px, w, h)
                dialFile = enc.file
                val prev = Bitmap.createBitmap(enc.preview, w, h, Bitmap.Config.ARGB_8888)
                ui.post { preview.setImageBitmap(prev) }
                log("Lista: ${enc.colors} colores, archivo de ${enc.file.size} bytes (${System.currentTimeMillis() - t0} ms)")
            } catch (e: Exception) {
                log("Error con la imagen: ${e.message}")
            }
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
        log("Conectando a ${dev.address}…")
        gatt = dev.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Conectado (status $status). Descubriendo servicios (sin tocar el MTU, como HiwatchPro)…")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Desconectado (status $status)")
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

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = newMtu
            mtuSem.release()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Descubrimiento falló: $status")
                return
            }
            var tx: BluetoothGattCharacteristic? = null
            var rx: BluetoothGattCharacteristic? = null
            for (s in g.services) {
                for (c in s.characteristics) {
                    if (c.uuid == UUID_NUS_TX) tx = c
                    if (c.uuid == UUID_NUS_RX) rx = c
                }
            }
            if (tx == null || rx == null) {
                log("✗ No encontré el servicio NUS (6e400002/6e400003) en este reloj")
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
            if (status == 0) {
                ready = true
                log("Notificaciones NUS RX activas. Listo para subir.")
            } else {
                log("✗ Error activando notificaciones: $status")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            recibir(c.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            recibir(value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeSem.release()
        }
    }

    private fun recibir(b: ByteArray) {
        if (!uploading) log("RX ${hex(b)}")
        for (f in rxParser.feed(b)) rxQueue.add(f)
    }

    // ------------------------------------------------------------------ subida

    /** Un fragmento de <= 20 bytes por escritura sin respuesta, esperando a que salga antes del siguiente. */
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
                    log("Sin confirmaciones de escritura del sistema: uso pausa fija de 12 ms")
                }
            }
        } else {
            Thread.sleep(12)
        }
        return true
    }

    private fun upload() {
        val g = gatt
        val ch = txChar
        val base = dialFile
        val mode = rgClock.checkedRadioButtonId - 100
        val fast = chkFast.isChecked
        val file = if (base != null) DialEncoder.styleClock(base, mode) else base
        if (g == null || ch == null || !ready) {
            log("Primero conecta (paso 2) y espera \"Listo para subir\"")
            return
        }
        if (file == null) {
            log("Primero elige una imagen (paso 1) y espera \"Lista:\"")
            return
        }
        if (uploading) return
        uploading = true
        btnSend.isEnabled = false
        bar.progress = 0
        rxQueue.clear()
        thread(name = "subida") {
            try {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                var chunk = DialProtocol.CHUNK
                if (fast) {
                    mtuSem.drainPermits()
                    if (g.requestMtu(247) && mtuSem.tryAcquire(3, TimeUnit.SECONDS)) {
                        chunk = (mtu - 3).coerceIn(DialProtocol.CHUNK, 244)
                        log("Modo rápido: MTU $mtu, escrituras de hasta $chunk bytes")
                    } else {
                        log("No pude negociar el MTU: sigo con fragmentos de 20 bytes")
                    }
                }
                log("Modo de hora: $mode · fragmento: $chunk bytes")
                val t0 = System.currentTimeMillis()
                val up = DialUploader(
                    write = { writeChunk(g, ch, it) },
                    nextFrame = { ms -> rxQueue.poll(ms, TimeUnit.MILLISECONDS) },
                    log = { log(it) },
                    progress = { n, total ->
                        val p = n * 100 / total
                        ui.post { bar.progress = p }
                        if (n % 50 == 0) log("  bloque $n/$total")
                    },
                    chunk = chunk
                )
                val ok = up.upload(file)
                val seg = (System.currentTimeMillis() - t0) / 1000
                if (ok) log("✓ Subida terminada en $seg s. El reloj puede mostrar su logo y reiniciar la interfaz.")
                else log("✗ Subida abortada a los $seg s. Manda captura de este log.")
            } catch (e: Exception) {
                log("✗ Error en la subida: ${e.message}")
            } finally {
                try {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                } catch (e: Exception) {
                    log("priority: ${e.message}")
                }
                uploading = false
                ui.post { btnSend.isEnabled = true }
            }
        }
    }

    override fun onDestroy() {
        stopScan()
        gatt?.close()
        gatt = null
        super.onDestroy()
    }
}
