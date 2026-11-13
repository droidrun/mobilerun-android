package ai.mobilerun.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import okhttp3.OkHttpClient

/** Same app shell (Compose + OkHttp) without WebRTC, used only to measure the APK size delta. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = OkHttpClient()
        setContent { Text("baseline ${client.hashCode()}") }
    }
}
