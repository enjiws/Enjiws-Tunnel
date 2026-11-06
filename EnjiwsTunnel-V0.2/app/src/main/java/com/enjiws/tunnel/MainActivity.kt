package com.enjiws.tunnel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.enjiws.tunnel.ui.EnjiwsApp
import com.enjiws.tunnel.ui.EnjiwsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EnjiwsTheme { EnjiwsApp() } }
    }
}
