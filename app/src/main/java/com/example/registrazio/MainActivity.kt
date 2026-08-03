package com.example.registrazio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.registrazio.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Il prototipo disegna sotto le barre di sistema (`viewport-fit=cover`)
        // e gestisce da sé le safe area: qui serve lo stesso edge-to-edge.
        enableEdgeToEdge()
        setContent {
            AppRoot()
        }
    }
}
