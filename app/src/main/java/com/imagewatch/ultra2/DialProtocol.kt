package com.imagewatch.ultra2

import kotlin.math.min

/**
 * Subida de esfera (dial) al Ultra2. Decodificado de una captura HCI de HiwatchPro.
 *
 * Canal:   NUS TX 6e400002 (escritura sin respuesta)  /  NUS RX 6e400003 (notificaciones)
 * Enlace:  MTU 23 -> fragmentos de 20 bytes. HiwatchPro NO usa 0xFF22 ni 0xFF11.
 * Trama:   [CD|DC] [00] [LEN] [LEN bytes]
 *
 * Secuencia observada (365 bloques de 200 B):
 *   teléfono: START              reloj: cd..20.. contador 1000   teléfono: ACK
 *   teléfono: BLOQUE n           reloj: cd..20.. contador 1000+n teléfono: ACK   (n = 1..365)
 *   teléfono: FINISH(len, sum)   reloj: cd..20.. contador 2      teléfono: ACK
 */
object DialProtocol {
    const val CHUNK = 20
    const val BLOCK_DATA = 200
    const val ACK_BASE = 1000L
    const val ACK_DONE = 2L

    /** Confirmación que el teléfono manda tras CADA trama de grupo 0x20 del reloj (367/367 idénticas). */
    val PHONE_ACK: ByteArray = byteArrayOf(0xDC.toByte(), 0x00, 0x05, 0x20, 0x01, 0x00, 0x0C, 0x01)

    /** Copiado tal cual de la captura. Significado de los 6 bytes de datos: NO decodificado. */
    fun start(): ByteArray = byteArrayOf(
        0xCD.toByte(), 0x00, 0x0B, 0x1F, 0x01, 0x02, 0x00, 0x06,
        0x03, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00
    )

    /** Bloque: seq u16 BE + datos + suma16 BE de (seq + datos). Verificado en los 365 bloques de la captura. */
    fun block(seq: Int, file: ByteArray, off: Int, len: Int): ByteArray {
        val dlen = len + 4
        val plen = 5 + dlen
        val out = ByteArray(3 + plen)
        out[0] = 0xCD.toByte(); out[1] = 0x00; out[2] = plen.toByte()
        out[3] = 0x1F; out[4] = 0x01; out[5] = 0x01; out[6] = 0x00; out[7] = dlen.toByte()
        out[8] = (seq shr 8).toByte(); out[9] = seq.toByte()
        System.arraycopy(file, off, out, 10, len)
        var sum = ((seq shr 8) and 0xFF) + (seq and 0xFF)
        for (i in off until off + len) sum += file[i].toInt() and 0xFF
        sum = sum and 0xFFFF
        out[10 + len] = (sum shr 8).toByte()
        out[11 + len] = sum.toByte()
        return out
    }

    /** Cierre: longitud total u32 BE + suma de todos los bytes del archivo u32 BE. */
    fun finish(total: Int, sum32: Long): ByteArray {
        val out = ByteArray(16)
        out[0] = 0xCD.toByte(); out[1] = 0x00; out[2] = 0x0D
        out[3] = 0x1F; out[4] = 0x01; out[5] = 0x03; out[6] = 0x00; out[7] = 0x08
        out[8] = (total shr 24).toByte(); out[9] = (total shr 16).toByte()
        out[10] = (total shr 8).toByte(); out[11] = total.toByte()
        out[12] = (sum32 shr 24).toByte(); out[13] = (sum32 shr 16).toByte()
        out[14] = (sum32 shr 8).toByte(); out[15] = sum32.toByte()
        return out
    }

    /** START + bloques + FINISH. */
    fun frames(file: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        out.add(start())
        var seq = 1
        var off = 0
        while (off < file.size) {
            val len = min(BLOCK_DATA, file.size - off)
            out.add(block(seq, file, off, len))
            seq++
            off += len
        }
        var s = 0L
        for (b in file) s += (b.toInt() and 0xFF)
        out.add(finish(file.size, s and 0xFFFFFFFFL))
        return out
    }

    /** Si la trama es una confirmación del reloj (grupo 0x20, 4 bytes de datos) devuelve su contador. */
    fun ackCounter(f: ByteArray): Long? {
        if (f.size < 12) return null
        if (f[0] != 0xCD.toByte() || f[3] != 0x20.toByte() || f[7] != 0x04.toByte()) return null
        return ((f[8].toLong() and 0xFF) shl 24) or ((f[9].toLong() and 0xFF) shl 16) or
            ((f[10].toLong() and 0xFF) shl 8) or (f[11].toLong() and 0xFF)
    }
}

/** Reensambla tramas [CD|DC][LEN u16 BE][...] a partir de notificaciones de hasta 20 bytes. */
class RxParser {
    private var buf = ByteArray(0)

    fun feed(data: ByteArray): List<ByteArray> {
        buf = buf + data
        val out = ArrayList<ByteArray>()
        while (true) {
            while (buf.isNotEmpty() && buf[0] != 0xCD.toByte() && buf[0] != 0xDC.toByte()) {
                buf = buf.copyOfRange(1, buf.size)
            }
            if (buf.size < 3) break
            val len = ((buf[1].toInt() and 0xFF) shl 8) or (buf[2].toInt() and 0xFF)
            if (len > 1200) {
                buf = buf.copyOfRange(1, buf.size)
                continue
            }
            val need = 3 + len
            if (buf.size < need) break
            out.add(buf.copyOfRange(0, need))
            buf = buf.copyOfRange(need, buf.size)
        }
        return out
    }
}

/**
 * Sube el archivo de esfera con el mismo ritmo que HiwatchPro: un bloque, esperar la confirmación
 * del reloj, confirmar nosotros, siguiente bloque. Nunca se manda un bloque sin la confirmación anterior.
 *
 * write:     envía UN fragmento (<= 20 B) y bloquea hasta que salió; false si falla
 * nextFrame: siguiente trama completa del reloj (espera hasta el plazo en ms) o null
 */
class DialUploader(
    private val write: (ByteArray) -> Boolean,
    private val nextFrame: (Long) -> ByteArray?,
    private val log: (String) -> Unit,
    private val progress: (Int, Int) -> Unit,
    private val ackTimeoutMs: Long = 6000,
    private val chunk: Int = DialProtocol.CHUNK
) {
    fun upload(file: ByteArray): Boolean {
        val frames = DialProtocol.frames(file)
        val blocks = frames.size - 2
        log("Subida: $blocks bloques (${file.size} bytes)")
        if (!send(frames[0])) return false
        if (!waitAck(DialProtocol.ACK_BASE, "el inicio")) return false
        for (n in 1..blocks) {
            if (!send(frames[n])) return false
            if (!waitAck(DialProtocol.ACK_BASE + n, "el bloque $n")) return false
            progress(n, blocks)
        }
        if (!send(frames[frames.size - 1])) return false
        if (!waitAck(DialProtocol.ACK_DONE, "el cierre")) return false
        log("✓ El reloj confirmó la esfera (estado 2)")
        return true
    }

    private fun send(frame: ByteArray): Boolean {
        var p = 0
        while (p < frame.size) {
            val e = min(p + chunk, frame.size)
            if (!write(frame.copyOfRange(p, e))) {
                log("✗ Escritura rechazada (¿se desconectó el reloj?)")
                return false
            }
            p = e
        }
        return true
    }

    private fun waitAck(expected: Long, what: String): Boolean {
        val t0 = System.currentTimeMillis()
        while (true) {
            val left = ackTimeoutMs - (System.currentTimeMillis() - t0)
            if (left <= 0) {
                log("✗ El reloj no respondió tras $what (${ackTimeoutMs / 1000} s)")
                return false
            }
            val f = nextFrame(left) ?: continue
            val c = DialProtocol.ackCounter(f)
            if (c == null) {
                log("  (trama ajena ignorada: ${f.joinToString(" ") { "%02X".format(it) }})")
                continue
            }
            if (!send(DialProtocol.PHONE_ACK)) return false
            if (c != expected) {
                log("✗ Contador inesperado tras $what: llegó $c, se esperaba $expected")
                return false
            }
            return true
        }
    }
}
