package ai.mobilerun.spike

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Runtime config written by scripts/run-spike.sh into the app's private files dir
 * (never bundled, never committed). Fields: streamUrl, streamToken, codec.
 */
data class SpikeConfig(val streamUrl: String, val streamToken: String, val codec: String) {
    companion object {
        fun load(ctx: Context): SpikeConfig? {
            val f = File(ctx.filesDir, "spike-config.json")
            if (!f.exists()) return null
            val o = JSONObject(f.readText())
            return SpikeConfig(
                streamUrl = o.getString("streamUrl"),
                streamToken = o.getString("streamToken"),
                codec = o.optString("codec", "default"),
            )
        }
    }
}
