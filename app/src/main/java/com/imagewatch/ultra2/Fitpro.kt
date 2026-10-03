package com.imagewatch.ultra2

import java.util.Calendar

/**
 * Protocolo binario del Ultra2 (FitPro). Fuentes:
 *  - captura HCI de HiwatchPro (tramas reales)
 *  - desensamblado del APK: SendData.getProtocol / SwitchProtocol / getSendPushRemindValue, NotifyService.sendNotifyPush
 *
 * Trama:  CD | LEN u16 BE (= total - 3) | grupo | 01 | clave | longitud datos u16 BE | datos
 */
object Fitpro {
    const val G_SETTING = 0x12
    const val G_SPORT = 0x15
    const val G_GET = 0x1A
    const val G_DIAL_READ = 0x20

    const val K_TIME = 0x01
    const val K_NOTIFY = 0x12
    const val K_FIND = 0x0B
    const val K_PHONE_TYPE = 0xFF
    const val K_PAIR = 0x0A
    const val K_NOTIFY_SWITCHES = 0x07

    /** Códigos de app del icono en el reloj (tabla de NotifyService.sendNotifyPush). */
    val APP_CODES: List<Pair<String, Int>> = listOf(
        "SMS" to 1, "QQ" to 2, "WeChat" to 3, "Facebook" to 4, "Twitter/X" to 5, "Skype" to 6,
        "Line" to 7, "WhatsApp" to 8, "KakaoTalk" to 9, "Snapchat" to 10, "TikTok" to 11,
        "Instagram" to 16, "LinkedIn" to 17, "Telegram" to 18, "OK.ru" to 19, "VK" to 20,
        "TenChat" to 21, "Viber" to 22, "Messenger" to 23
    )

    /** Límite de HiwatchPro: el texto se recorta a 300 bytes UTF-8. */
    const val MAX_TEXT_BYTES = 300

    fun frame(group: Int, key: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val len = 5 + data.size
        val out = ByteArray(3 + len)
        out[0] = 0xCD.toByte()
        out[1] = (len shr 8).toByte()
        out[2] = len.toByte()
        out[3] = group.toByte()
        out[4] = 0x01
        out[5] = key.toByte()
        out[6] = (data.size shr 8).toByte()
        out[7] = data.size.toByte()
        System.arraycopy(data, 0, out, 8, data.size)
        return out
    }

    /** Consulta de solo lectura (grupo 0x1A, sin datos). */
    fun get(key: Int): ByteArray = frame(G_GET, key)

    /** Hora local empaquetada en 32 bits: año-2000 (6) | mes (4) | día (5) | hora (5) | minuto (6) | segundo (6). */
    fun packTime(c: Calendar): ByteArray {
        val v = ((c.get(Calendar.YEAR) - 2000) shl 26) or ((c.get(Calendar.MONTH) + 1) shl 22) or
            (c.get(Calendar.DAY_OF_MONTH) shl 17) or (c.get(Calendar.HOUR_OF_DAY) shl 12) or
            (c.get(Calendar.MINUTE) shl 6) or c.get(Calendar.SECOND)
        return byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    }

    fun timeSync(c: Calendar): ByteArray = frame(G_SETTING, K_TIME, packTime(c))

    /** HiwatchPro lo manda al conectar: grupo 0x12, clave 0xFF, dato 01. */
    fun phoneType(): ByteArray = frame(G_SETTING, K_PHONE_TYPE, byteArrayOf(1))

    fun readDialInfo(): ByteArray = frame(G_DIAL_READ, 0x02)

    /** Primer comando de HiwatchPro al conectar: SendData.getPair() = (0x12, 0x0A, 0x02). Verificado en la captura. */
    fun pair(): ByteArray = frame(G_SETTING, K_PAIR, byteArrayOf(2))

    /**
     * Interruptores de categorías de aviso (SendData.getSetCallRemindValue = grupo 0x12, clave 0x07).
     * Un byte (0/1) por categoría, en este orden: llamadas, SMS, WeChat, QQ, Facebook, Twitter, Skype, Line,
     * WhatsApp, KakaoTalk, Instagram [+ LinkedIn, Snapchat, TikTok, Telegram, OK.ru, VK, TenChat, Viber, Messenger
     * si el reloj lo anuncia]. HiwatchPro guarda cada interruptor con valor por defecto "0" (apagado).
     * HIPÓTESIS: con las categorías apagadas el reloj ignora los avisos de texto.
     */
    fun notifySwitches(on: Boolean, count: Int = 11): ByteArray =
        frame(G_SETTING, K_NOTIFY_SWITCHES, ByteArray(count) { if (on) 1 else 0 })

    fun readNotifySwitches(): ByteArray = get(K_NOTIFY_SWITCHES)

    /** Suposición: SwitchProtocol(grupo, clave, valor) = trama con un solo byte de datos. */
    fun findWatch(on: Boolean): ByteArray = frame(G_SETTING, K_FIND, byteArrayOf(if (on) 1 else 0))

    /** Recorta a [max] bytes UTF-8 sin partir caracteres. */
    fun truncUtf8(s: String, max: Int = MAX_TEXT_BYTES): String {
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val str = String(Character.toChars(cp))
            val n = str.toByteArray(Charsets.UTF_8).size
            if (bytes + n > max) break
            sb.append(str)
            bytes += n
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** Texto como lo arma HiwatchPro: "Título:Cuerpo" (si hay título). */
    fun composeText(title: String, body: String): String =
        truncUtf8(if (title.isBlank()) body else "$title:$body")

    /** Notificación de mensaje: grupo 0x12, clave 0x12, datos = [código de app, 0, 0] + UTF-8 del texto. */
    fun notify(appCode: Int, text: String): ByteArray {
        val t = truncUtf8(text).toByteArray(Charsets.UTF_8)
        val data = ByteArray(3 + t.size)
        data[0] = appCode.toByte()
        System.arraycopy(t, 0, data, 3, t.size)
        return frame(G_SETTING, K_NOTIFY, data)
    }

    /** Confirmación que HiwatchPro manda a las tramas periódicas del grupo 0x15 (copiada de la captura). */
    val ACK_SPORT: ByteArray = byteArrayOf(0xDC.toByte(), 0x00, 0x05, 0x15, 0x01, 0x00, 0x14, 0x01)

    fun hex(b: ByteArray, max: Int = 48): String {
        val n = minOf(b.size, max)
        val sb = StringBuilder()
        for (i in 0 until n) {
            if (i > 0) sb.append(' ')
            sb.append("%02X".format(b[i]))
        }
        if (b.size > max) sb.append(" … (${b.size} B)")
        return sb.toString()
    }

    /** Descripción legible de una trama TX o RX. */
    fun describe(f: ByteArray): String {
        if (f.size < 4) return "trama corta"
        val kind = when (f[0].toInt() and 0xFF) {
            0xCD -> "cmd"
            0xDC -> "resp"
            else -> "?"
        }
        val g = f[3].toInt() and 0xFF
        return if (f[0] == 0xDC.toByte() && f.size >= 8) {
            val k = f[4].toInt() and 0xFF
            "$kind ${Names.group(g)}/${Names.key(g, k)} estado=0x%02X".format(f[6].toInt() and 0xFF)
        } else if (f.size >= 8) {
            val k = f[5].toInt() and 0xFF
            val dlen = ((f[6].toInt() and 0xFF) shl 8) or (f[7].toInt() and 0xFF)
            "$kind ${Names.group(g)}/${Names.key(g, k)} datos=$dlen"
        } else {
            "$kind ${Names.group(g)}"
        }
    }
}
