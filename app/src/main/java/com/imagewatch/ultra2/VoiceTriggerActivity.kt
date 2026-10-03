package com.imagewatch.ultra2

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PRUEBA del disparador: si el botón de asistente del reloj llega al teléfono como "comando de voz"
 * (HFP AT+BVRA), Android lanza la acción VOICE_COMMAND. Si esta pantalla se abre al pulsarlo, el reloj
 * puede disparar acciones de nuestra app sin hablar. Si no se abre, el reloj usa otro mecanismo.
 */
class VoiceTriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#0a0e14"))
        root.setPadding(pad, pad * 2, pad, pad)
        val t = TextView(this)
        t.setTextColor(Color.parseColor("#00e87a"))
        t.textSize = 18f
        t.text = "Disparador recibido a las $hora\n\nAcción: ${intent?.action}"
        root.addView(t)
        val b = Button(this)
        b.text = "Abrir Ultra2 Lab"
        b.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        root.addView(b)
        setContentView(root)
    }
}
