package ai.mobilerun.spike

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import okhttp3.OkHttpClient
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Throwaway spike: renders the cloud device stream and forwards touches.
 * Debug controls are broadcasts (adb), see scripts/run-spike.sh:
 *   KILL_WS     abrupt WebSocket close, then observe whether video continues
 *   RECONNECT   full teardown + new session (time to first frame again)
 *   ICE_RESTART setConfiguration + restart offer on the live session
 *   STATS       dump codec / decoder / candidate-pair stats
 */
class MainActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var egl: EglBase
    private lateinit var factory: PeerConnectionFactory
    private val http = OkHttpClient()
    private var session: StreamSession? = null
    private var cfg: SpikeConfig? = null
    private var renderer: SurfaceViewRenderer? = null

    // last frame geometry as reported by the renderer (pre-rotation)
    @Volatile private var frameW = 0
    @Volatile private var frameH = 0
    @Volatile private var rotation = 0

    private var status by mutableStateOf("starting")

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val s = session
            when (intent.action) {
                ACTION_KILL_WS -> s?.let { ss ->
                    val before = ss.frameCount
                    ss.killWebSocket()
                    // does media keep flowing without signalling?
                    for (sec in intArrayOf(5, 15)) main.postDelayed({
                        metric("after_ws_kill t=${sec}s frames_delta=${ss.frameCount - before}")
                        ss.dumpStats()
                    }, sec * 1000L)
                }
                ACTION_RECONNECT -> reconnect()
                ACTION_ICE_RESTART -> s?.let { ss ->
                    val before = ss.frameCount
                    ss.iceRestart()
                    main.postDelayed({ metric("after_ice_restart t=6s frames_delta=${ss.frameCount - before}"); ss.dumpStats() }, 6000)
                }
                ACTION_STATS -> s?.dumpStats()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        egl = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .createPeerConnectionFactory()

        cfg = SpikeConfig.load(this)
        val filter = IntentFilter().apply {
            addAction(ACTION_KILL_WS); addAction(ACTION_RECONNECT); addAction(ACTION_ICE_RESTART); addAction(ACTION_STATS)
        }
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)

        setContent {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).also { r ->
                            r.init(egl.eglBaseContext, object : RendererCommon.RendererEvents {
                                override fun onFirstFrameRendered() { metric("renderer_first_frame_rendered") }
                                override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rot: Int) {
                                    metric("renderer_resolution ${videoWidth}x$videoHeight rotation=$rot")
                                }
                            })
                            r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                            r.setEnableHardwareScaler(true)
                            r.setOnTouchListener { v, ev -> handleTouch(v as SurfaceViewRenderer, ev); true }
                            renderer = r
                            connect()
                        }
                    },
                )
                Text(status)
            }
        }
    }

    private fun connect() {
        val c = cfg ?: run { status = "no spike-config.json"; metric("blocker no_config"); return }
        val r = renderer ?: return
        session = StreamSession(factory, c, http, r) { w, h, rot -> frameW = w; frameH = h; rotation = rot }.also { it.start() }
        status = "connecting (${c.codec})"
    }

    private fun reconnect() {
        metric("reconnect_requested")
        session?.stop()
        connect()
    }

    private var downPointer = HashMap<Int, Boolean>()

    private fun handleTouch(v: SurfaceViewRenderer, ev: MotionEvent) {
        val s = session ?: return
        val action = when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> TouchMessage.ACTION_DOWN
            MotionEvent.ACTION_MOVE -> TouchMessage.ACTION_MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> TouchMessage.ACTION_UP
            MotionEvent.ACTION_CANCEL -> TouchMessage.ACTION_CANCEL
            else -> return
        }
        val indices = if (action == TouchMessage.ACTION_MOVE) (0 until ev.pointerCount) else listOf(ev.actionIndex)
        for (i in indices) {
            val m = TouchMessage.mapToVideo(ev.getX(i), ev.getY(i), v.width, v.height, frameW, frameH, rotation)
            if (m == null) { metric("touch outside video"); continue }
            val (x, y, size) = m
            val ok = s.sendControl(TouchMessage.create(action, ev.getPointerId(i).toLong(), size.first, size.second, x, y))
            if (action != TouchMessage.ACTION_MOVE) {
                metric("touch action=$action view=(${ev.getX(i).toInt()},${ev.getY(i).toInt()}) -> video=(${x.toInt()},${y.toInt()}) of ${size.first}x${size.second} sent=$ok")
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        session?.stop()
        renderer?.release()
        factory.dispose()
        egl.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_KILL_WS = "ai.mobilerun.spike.KILL_WS"
        const val ACTION_RECONNECT = "ai.mobilerun.spike.RECONNECT"
        const val ACTION_ICE_RESTART = "ai.mobilerun.spike.ICE_RESTART"
        const val ACTION_STATS = "ai.mobilerun.spike.STATS"
    }
}
