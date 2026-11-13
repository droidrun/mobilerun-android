package ai.mobilerun.spike

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpCapabilities
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "Spike"

/** Never logs the token, the URL, or ICE server credentials (only message types / key names). */
fun metric(msg: String) {
    Log.i(TAG, "METRIC $msg")
}

/**
 * One signalling + peer connection session, following use-webrtc-connection.ts:
 * WS(?token=) -> requestRtcConfiguration -> client offers (recvonly audio+video,
 * negotiated data channels control=1 / hover=2) -> answer -> candidates (buffered until answer).
 */
class StreamSession(
    private val factory: PeerConnectionFactory,
    private val cfg: SpikeConfig,
    private val http: OkHttpClient,
    private val sink: VideoSink,
    private val onVideoSize: (w: Int, h: Int, rotation: Int) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val sessionId = UUID.randomUUID().toString()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var pc: PeerConnection? = null
    @Volatile var control: DataChannel? = null; private set
    @Volatile var hover: DataChannel? = null; private set
    @Volatile private var remoteDescriptionSet = false
    private val pendingCandidates = mutableListOf<IceCandidate>()
    @Volatile private var stopped = false
    @Volatile private var lastRtcConfig: PeerConnection.RTCConfiguration? = null

    private var t0 = 0L
    private val frames = AtomicInteger(0)
    private var firstFrameAt = 0L
    @Volatile private var keepAlive: Runnable? = null
    private var videoTrack: VideoTrack? = null

    /** Frame counter exposed for the "does video continue after WS close" probe. */
    val frameCount: Int get() = frames.get()

    private val countingSink = VideoSink { frame: VideoFrame ->
        if (frames.incrementAndGet() == 1) {
            firstFrameAt = SystemClock.elapsedRealtime()
            metric("first_video_frame_ms=${firstFrameAt - t0} size=${frame.buffer.width}x${frame.buffer.height} rotation=${frame.rotation}")
            main.postDelayed({ dumpStats() }, 3000)
        }
        onVideoSize(frame.buffer.width, frame.buffer.height, frame.rotation)
        sink.onFrame(frame)
    }

    fun start() {
        t0 = SystemClock.elapsedRealtime()
        stopped = false
        val url = cfg.streamUrl.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
            .toHttpUrl().newBuilder().addQueryParameter("token", cfg.streamToken).build()
        ws = http.newWebSocket(Request.Builder().url(url).build(), WsListener())
    }

    fun stop() {
        stopped = true
        stopKeepAlive()
        ws?.close(1000, "bye"); ws = null
        control?.close(); hover?.close(); control = null; hover = null
        videoTrack?.removeSink(countingSink); videoTrack = null
        pc?.close(); pc = null
        remoteDescriptionSet = false
        pendingCandidates.clear()
    }

    /** Forced WS close (abrupt, no close frame) to probe what keeps working without signalling. */
    fun killWebSocket() {
        metric("ws_force_cancel frames_at_cancel=${frames.get()}")
        stopKeepAlive()
        ws?.cancel()
    }

    fun sendControl(bytes: ByteArray): Boolean {
        val dc = control ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        return dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
    }

    private fun sendJson(o: JSONObject) {
        ws?.send(o.toString())
    }

    private fun startKeepAlive() {
        stopKeepAlive()
        val sock = ws
        val r = object : Runnable {
            override fun run() {
                if (stopped || ws !== sock) return
                sendJson(JSONObject().put("type", "keepAlive").put("sessionId", sessionId))
                main.postDelayed(this, 10_000)
            }
        }
        keepAlive = r
        main.postDelayed(r, 10_000)
    }

    private fun stopKeepAlive() {
        keepAlive?.let { main.removeCallbacks(it) }
        keepAlive = null
    }

    private inner class WsListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            metric("ws_open_ms=${SystemClock.elapsedRealtime() - t0}")
            webSocket.send(
                JSONObject().put("type", "requestRtcConfiguration").put("sessionId", sessionId)
                    .put("supportsLiveRtcRefresh", true).toString(),
            )
            startKeepAlive()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== ws) return
            val m = try { JSONObject(text) } catch (e: Exception) { return }
            when (val type = m.optString("type")) {
                "rtcConfiguration" -> {
                    val rc = m.optJSONObject("rtcConfiguration")
                    if (pc == null) {
                        metric("rtc_config_ms=${SystemClock.elapsedRealtime() - t0} keys=${rc?.keys()?.asSequence()?.toList()}")
                        createPeer(rc)
                    } else {
                        refreshConfig(rc)
                    }
                }
                "answer" -> {
                    val p = pc ?: return
                    p.setRemoteDescription(object : SdpObserverAdapter("setRemote") {
                        override fun onSetSuccess() {
                            metric("answer_applied_ms=${SystemClock.elapsedRealtime() - t0}")
                            remoteDescriptionSet = true
                            synchronized(pendingCandidates) {
                                pendingCandidates.forEach { p.addIceCandidate(it) }
                                pendingCandidates.clear()
                            }
                        }
                    }, SessionDescription(SessionDescription.Type.ANSWER, m.getString("sdp")))
                }
                "candidate" -> {
                    val p = pc ?: return
                    val c = IceCandidate(
                        m.optString("sdpMid").takeIf { !m.isNull("sdpMid") },
                        m.optInt("sdpMLineIndex", 0),
                        m.getString("candidate"),
                    )
                    if (!remoteDescriptionSet) synchronized(pendingCandidates) { pendingCandidates.add(c) }
                    else p.addIceCandidate(c)
                }
                else -> Log.d(TAG, "unhandled message type=$type")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            metric("ws_closed code=$code pc_state=${pc?.connectionState()}")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            metric("ws_failure type=${t.javaClass.simpleName} http=${response?.code} pc_state=${pc?.connectionState()}")
        }
    }

    private fun parseRtcConfig(rc: JSONObject?): PeerConnection.RTCConfiguration {
        val servers = mutableListOf<PeerConnection.IceServer>()
        val arr = rc?.optJSONArray("iceServers") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val urls = s.opt("urls").let {
                if (it is JSONArray) (0 until it.length()).map { j -> it.getString(j) } else listOf(it.toString())
            }
            servers.add(
                PeerConnection.IceServer.builder(urls)
                    .setUsername(s.optString("username", ""))
                    .setPassword(s.optString("credential", ""))
                    .createIceServer(),
            )
        }
        val c = PeerConnection.RTCConfiguration(servers)
        c.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        if (rc?.optString("iceTransportPolicy") == "relay") c.iceTransportsType = PeerConnection.IceTransportsType.RELAY
        metric("ice_servers=${servers.size} policy=${rc?.optString("iceTransportPolicy", "all")}")
        return c
    }

    private fun createPeer(rc: JSONObject?) {
        val config = parseRtcConfig(rc)
        lastRtcConfig = config
        val p = factory.createPeerConnection(config, PcObserver()) ?: error("createPeerConnection returned null")
        pc = p
        p.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )
        val video = p.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )
        applyCodecPreference(video)

        control = p.createDataChannel("control", DataChannel.Init().apply {
            ordered = true; negotiated = true; id = 1
        }).also { dc ->
            dc.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {}
                override fun onMessage(buffer: DataChannel.Buffer) {}
                override fun onStateChange() {
                    metric("control_state=${dc.state()} ms=${SystemClock.elapsedRealtime() - t0}")
                    if (dc.state() == DataChannel.State.OPEN) {
                        // requestFrame x12 at 125 ms once control opens
                        for (i in 0 until 12) main.postDelayed({
                            if (!stopped) sendJson(JSONObject().put("type", "requestFrame").put("sessionId", sessionId))
                        }, i * 125L)
                    }
                }
            })
        }
        hover = p.createDataChannel("hover", DataChannel.Init().apply {
            ordered = false; maxRetransmits = 0; negotiated = true; id = 2
        }).also { dc ->
            dc.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {}
                override fun onMessage(buffer: DataChannel.Buffer) {}
                override fun onStateChange() { metric("hover_state=${dc.state()}") }
            })
        }

        p.createOffer(object : SdpObserverAdapter("createOffer") {
            override fun onCreateSuccess(desc: SessionDescription) {
                p.setLocalDescription(object : SdpObserverAdapter("setLocal") {
                    override fun onSetSuccess() {
                        sendJson(JSONObject().put("type", "offer").put("sdp", desc.description).put("sessionId", sessionId))
                        metric("offer_sent_ms=${SystemClock.elapsedRealtime() - t0}")
                    }
                }, desc)
            }
        }, MediaConstraints())
    }

    /** Mid-session rtcConfiguration: setConfiguration + ICE-restart offer. */
    private fun refreshConfig(rc: JSONObject?) {
        val p = pc ?: return
        if (rc?.optJSONArray("iceServers") == null) return
        iceRestart(parseRtcConfig(rc), "credential_refresh")
    }

    /** Also callable from the debug broadcast to prove setConfiguration + ICE restart against the bridge. */
    fun iceRestart(config: PeerConnection.RTCConfiguration? = lastRtcConfig, reason: String = "debug") {
        val p = pc ?: return
        if (config != null) {
            val ok = p.setConfiguration(config)
            metric("setConfiguration ok=$ok reason=$reason")
            if (!ok) return
        }
        val t = SystemClock.elapsedRealtime()
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        p.createOffer(object : SdpObserverAdapter("createOffer(iceRestart)") {
            override fun onCreateSuccess(desc: SessionDescription) {
                p.setLocalDescription(object : SdpObserverAdapter("setLocal(iceRestart)") {
                    override fun onSetSuccess() {
                        remoteDescriptionSet = false
                        synchronized(pendingCandidates) { pendingCandidates.clear() }
                        sendJson(JSONObject().put("type", "offer").put("sdp", desc.description).put("sessionId", sessionId))
                        metric("ice_restart_offer_sent frames=${frames.get()} t=$t")
                    }
                }, desc)
            }
        }, constraints)
    }

    private fun applyCodecPreference(t: RtpTransceiver) {
        val caps = factory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
        metric("receiver_codecs=" + caps.joinToString(",") { it.mimeType.removePrefix("video/") })
        fun prio(c: RtpCapabilities.CodecCapability): Int {
            val m = c.mimeType.lowercase()
            return when {
                cfg.codec == "h265" && (m.contains("h265") || m.contains("hevc")) -> 0
                cfg.codec == "h264" && m.contains("h264") -> 0
                m.contains("vp9") -> 1
                m.contains("h265") || m.contains("hevc") -> 2
                m.contains("h264") -> 3
                else -> 4
            }
        }
        val sorted = when (cfg.codec) {
            // forced runs: keep only the wanted codec (+ rtx) so the bridge can only pick it
            "h265" -> caps.filter { val m = it.mimeType.lowercase(); m.contains("h265") || m.contains("hevc") || m.contains("rtx") }
            "h264" -> caps.filter { val m = it.mimeType.lowercase(); m.contains("h264") || m.contains("rtx") }
            else -> caps.sortedBy { prio(it) }
        }
        if (sorted.none { !it.mimeType.lowercase().contains("rtx") }) {
            metric("codec_pref cfg=${cfg.codec} UNAVAILABLE_in_receiver_caps (leaving defaults)")
            return
        }
        t.setCodecPreferences(sorted)
        metric("codec_pref cfg=${cfg.codec} order=" + sorted.joinToString(",") { it.mimeType.removePrefix("video/") })
    }

    fun dumpStats() {
        val p = pc ?: return
        p.getStats { report: RTCStatsReport ->
            val byId = report.statsMap
            val inbound = byId.values.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "video" }
            if (inbound != null) {
                val codecId = inbound.members["codecId"] as String?
                val codec = codecId?.let { byId[it]?.members }
                metric(
                    "stats codec=${codec?.get("mimeType")} fmtp=${codec?.get("sdpFmtpLine")} " +
                        "decoder=${inbound.members["decoderImplementation"]} powerEfficient=${inbound.members["powerEfficientDecoder"]} " +
                        "framesDecoded=${inbound.members["framesDecoded"]} frameSize=${inbound.members["frameWidth"]}x${inbound.members["frameHeight"]} " +
                        "fps=${inbound.members["framesPerSecond"]} bytes=${inbound.members["bytesReceived"]} " +
                        "jitterBufferMs=${inbound.members["jitterBufferDelay"]}/${inbound.members["jitterBufferEmittedCount"]}",
                )
            } else {
                metric("stats no_inbound_video")
            }
            byId.values.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }
                ?.let { cp ->
                    val l = byId[cp.members["localCandidateId"]]?.members
                    val r = byId[cp.members["remoteCandidateId"]]?.members
                    metric(
                        "stats pair local=${l?.get("candidateType")}/${l?.get("protocol")} remote=${r?.get("candidateType")}/${r?.get("protocol")} " +
                            "rttMs=${(cp.members["currentRoundTripTime"] as? Double)?.times(1000)}",
                    )
                }
        }
    }

    private inner class PcObserver : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            sendJson(
                JSONObject().put("type", "candidate").put("candidate", c.sdp)
                    .put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex).put("sessionId", sessionId),
            )
        }

        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
            metric("pc_state=$s ms=${SystemClock.elapsedRealtime() - t0}")
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            metric("on_track kind=${track?.kind()} ms=${SystemClock.elapsedRealtime() - t0}")
            if (track is VideoTrack) {
                videoTrack = track
                track.addSink(countingSink)
            }
        }

        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
            metric("ice_state=$s ms=${SystemClock.elapsedRealtime() - t0}")
        }
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: org.webrtc.MediaStream) {}
        override fun onRemoveStream(s: org.webrtc.MediaStream) {}
        override fun onDataChannel(dc: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    private open class SdpObserverAdapter(private val what: String) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { metric("$what create_failure=$error") }
        override fun onSetFailure(error: String?) { metric("$what set_failure=$error") }
    }
}
