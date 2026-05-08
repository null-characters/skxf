package com.skxf.display

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = getSharedPreferences(PrefKeys.FILE, MODE_PRIVATE)
        val input = findViewById<TextInputEditText>(R.id.inputUrl)
        val sw = findViewById<SwitchCompat>(R.id.switchManualOnly)
        input.setText(prefs.getString(PrefKeys.MANUAL_URL, ""))
        sw.isChecked = prefs.getBoolean(PrefKeys.MANUAL_ONLY, false)

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            val raw = input.text?.toString()?.trim() ?: ""
            if (raw.isNotBlank() &&
                !raw.startsWith("http://") &&
                !raw.startsWith("https://")
            ) {
                Toast.makeText(this, "地址需以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putString(PrefKeys.MANUAL_URL, raw)
                .putBoolean(PrefKeys.MANUAL_ONLY, sw.isChecked)
                .apply()
            finish()
        }
    }
}
