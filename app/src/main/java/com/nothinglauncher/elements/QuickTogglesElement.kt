package com.nothinglauncher.elements

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Vibrator
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.nothinglauncher.R

/** Wi-Fi, Bluetooth, Torch and Sound mode toggles reflecting the real system state. */
class QuickTogglesElement(context: Context) : ElementView(context) {

    private val app = context.applicationContext
    private val wifi = try { app.getSystemService(Context.WIFI_SERVICE) as? WifiManager } catch (e: Exception) { null }
    private val btAdapter: BluetoothAdapter? = try { (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter } catch (e: Exception) { null }
    private val camera = try { app.getSystemService(Context.CAMERA_SERVICE) as? CameraManager } catch (e: Exception) { null }
    private val audio = try { app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager } catch (e: Exception) { null }
    private val notificationManager = try { app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager } catch (e: Exception) { null }
    private val torchId: String? = findTorch()

    private var torchOn = false
    private var torchUsable = torchId != null
    private var receiverOn = false
    private var torchCallbackOn = false

    private val wifiCell = ToggleCell(context)
    private val btCell = ToggleCell(context)
    private val torchCell = ToggleCell(context)
    private val soundCell = ToggleCell(context)
    private val cells = listOf(wifiCell, btCell, torchCell, soundCell)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            refreshStates()
        }
    }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != torchId) return
            torchOn = enabled
            torchUsable = true
            refreshStates()
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId != torchId) return
            // The camera is in use by another app; the torch can't be controlled right now.
            torchOn = false
            torchUsable = false
            refreshStates()
        }
    }

    init {
        setBackgroundColor(0)
        cells.forEach { addView(it) }
        wifiCell.setOnClickListener { EaKit.tap(it); onWifi() }
        btCell.setOnClickListener { EaKit.tap(it); onBluetooth() }
        torchCell.setOnClickListener { EaKit.tap(it); onTorch() }
        soundCell.setOnClickListener { EaKit.tap(it); onSound() }
        wifiCell.setup(R.drawable.ic_wifi, context.getString(R.string.ea_wifi))
        btCell.setup(R.drawable.ic_bluetooth, context.getString(R.string.ea_bluetooth))
        torchCell.setup(R.drawable.ic_flashlight, context.getString(R.string.ea_torch))
        refreshStates()
    }

    private fun findTorch(): String? {
        val cm = camera ?: return null
        return try {
            var fallback: String? = null
            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
                if (ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) continue
                if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) return id
                if (fallback == null) fallback = id
            }
            fallback
        } catch (e: Exception) {
            null
        }
    }

    // ---- state ------------------------------------------------------------------------------

    private fun wifiEnabled(): Boolean = try { wifi?.isWifiEnabled == true } catch (e: Exception) { false }

    private fun btEnabled(): Boolean = try { btAdapter?.isEnabled == true } catch (e: SecurityException) { false } catch (e: Exception) { false }

    private fun ringerMode(): Int = try { audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL } catch (e: Exception) { AudioManager.RINGER_MODE_NORMAL }

    private fun refreshStates() {
        wifiCell.update(on = wifiEnabled(), usable = wifi != null, state = stateWord(wifiEnabled()))
        btCell.update(on = btEnabled(), usable = btAdapter != null, state = stateWord(btEnabled()))
        torchCell.update(on = torchOn, usable = torchId != null && torchUsable, state = if (torchId == null) context.getString(R.string.ea_unavailable) else stateWord(torchOn))
        val mode = ringerMode()
        val (icon, label) = when (mode) {
            AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_ea_vibrate to context.getString(R.string.ea_vibrate)
            AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_ea_silent to context.getString(R.string.ea_silent)
            else -> R.drawable.ic_volume to context.getString(R.string.ea_sound)
        }
        soundCell.setup(icon, label)
        soundCell.update(on = mode == AudioManager.RINGER_MODE_NORMAL, usable = audio != null, state = label)
    }

    private fun stateWord(on: Boolean) = context.getString(if (on) R.string.ea_on else R.string.ea_off)

    // ---- actions ----------------------------------------------------------------------------

    private fun onWifi() {
        if (!isHostReady) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            host.startActivitySafely(Intent(Settings.Panel.ACTION_WIFI), wifiCell)
        } else {
            val ok = try {
                @Suppress("DEPRECATION")
                wifi?.setWifiEnabled(!wifiEnabled()) == true
            } catch (e: Exception) { false }
            if (!ok) host.startActivitySafely(Intent(Settings.ACTION_WIFI_SETTINGS), wifiCell)
            refreshStates()
        }
    }

    private fun onBluetooth() {
        if (!isHostReady) return
        val needsConnect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        if (needsConnect && !store.getBoolean(KEY_BT_ASKED)) {
            store.putBoolean(KEY_BT_ASKED, true)
            host.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) { granted ->
                if (granted && isLive) {
                    // Re-register so ACTION_STATE_CHANGED (which needs the permission) is delivered.
                    unregisterReceiver(); registerReceiver()
                }
                refreshStates()
                host.startActivitySafely(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), btCell)
            }
            return
        }
        host.startActivitySafely(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), btCell)
    }

    private fun onTorch() {
        val id = torchId ?: return
        val cm = camera ?: return
        try {
            cm.setTorchMode(id, !torchOn)
            if (!torchCallbackOn) {
                // Callback not registered (not live): reflect the change optimistically from the call.
                torchOn = !torchOn
                refreshStates()
            }
        } catch (e: Exception) {
            Toast.makeText(context, R.string.ea_torch_busy, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onSound() {
        if (!isHostReady) return
        val am = audio ?: return
        val current = ringerMode()
        val hasVibrator = try {
            (app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.hasVibrator() == true
        } catch (e: Exception) { false }
        val next = when (current) {
            AudioManager.RINGER_MODE_NORMAL -> if (hasVibrator) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_SILENT
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        val touchesSilent = next == AudioManager.RINGER_MODE_SILENT || current == AudioManager.RINGER_MODE_SILENT
        val policyGranted = try { notificationManager?.isNotificationPolicyAccessGranted == true } catch (e: Exception) { false }
        if (touchesSilent && !policyGranted) {
            Toast.makeText(context, R.string.ea_dnd_access_needed, Toast.LENGTH_LONG).show()
            host.startActivitySafely(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), soundCell)
            return
        }
        try {
            am.ringerMode = next
        } catch (e: SecurityException) {
            Toast.makeText(context, R.string.ea_dnd_access_needed, Toast.LENGTH_LONG).show()
            host.startActivitySafely(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), soundCell)
        } catch (_: Exception) {}
        refreshStates()
    }

    // ---- lifecycle --------------------------------------------------------------------------

    override fun onLiveChanged(live: Boolean) {
        if (live) {
            registerReceiver()
            if (!torchCallbackOn && torchId != null) {
                torchCallbackOn = try {
                    camera?.registerTorchCallback(torchCallback, EaKit.main)
                    camera != null
                } catch (e: Exception) { false }
            }
            refreshStates()
        } else {
            unregisterAll()
        }
    }

    private fun registerReceiver() {
        if (receiverOn) return
        val f = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }
        EaKit.register(context, receiver, f)
        receiverOn = true
    }

    private fun unregisterReceiver() {
        if (!receiverOn) return
        EaKit.unregister(context, receiver)
        receiverOn = false
    }

    private fun unregisterAll() {
        unregisterReceiver()
        if (torchCallbackOn) {
            try { camera?.unregisterTorchCallback(torchCallback) } catch (_: Exception) {}
            torchCallbackOn = false
        }
    }

    override fun onDetachedFromWindow() {
        unregisterAll()
        super.onDetachedFromWindow()
    }

    override fun onAccentChanged(color: Int) {
        cells.forEach { it.setAccent(color) }
    }

    // ---- layout -----------------------------------------------------------------------------

    private fun columns(w: Int, h: Int): Int =
        if (spanX <= 2 || w < h * 1.4f) 2 else 4

    override fun onSpanChanged(spanX: Int, spanY: Int) {
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cols = columns(w, h)
        val rows = if (cols == 4) 1 else 2
        val cw = (w / cols).coerceAtLeast(0)
        val ch = (h / rows).coerceAtLeast(0)
        cells.forEach {
            it.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        val cols = columns(w, h)
        val rows = if (cols == 4) 1 else 2
        val cw = w / cols
        val ch = h / rows
        cells.forEachIndexed { i, c ->
            val col = i % cols
            val row = i / cols
            c.layout(col * cw, row * ch, col * cw + cw, row * ch + ch)
        }
    }

    /** One round toggle with its label underneath. */
    private inner class ToggleCell(context: Context) : View(context) {
        private val discOff = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ElementUi.color(context, R.color.surface_raised) }
        private val discOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ElementUi.color(context, R.color.dot_on) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ElementUi.color(context, R.color.hairline)
            style = Paint.Style.STROKE
            strokeWidth = EaKit.dp(context, 1f)
        }
        private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
        private val labelPaint = ElementUi.labelPaint(context, 9f, primary = true)
        private val iconOn = ElementUi.color(context, R.color.surface)
        private val iconOff = ElementUi.color(context, R.color.text_primary)
        private var icon: Drawable? = null
        private var iconRes = 0
        private var label = ""
        private var on = false
        private var usable = true

        init {
            isClickable = true
            isFocusable = true
            isHapticFeedbackEnabled = true
        }

        fun setup(res: Int, text: String) {
            if (res != iconRes) {
                iconRes = res
                icon = ContextCompat.getDrawable(context, res)?.mutate()
            }
            if (text != label) label = text
            invalidate()
        }

        fun update(on: Boolean, usable: Boolean, state: String) {
            this.on = on && usable
            this.usable = usable
            isEnabled = usable
            alpha = if (usable) 1f else 0.4f
            contentDescription = "$label, $state"
            invalidate()
        }

        fun setAccent(c: Int) {
            accentPaint.color = c
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return
            val labelH = labelPaint.textSize * 1.9f
            val showLabel = h > labelH * 2.6f
            val discArea = if (showLabel) h - labelH else h
            val r = (minOf(w, discArea) / 2f - EaKit.dp(context, 3f)).coerceAtMost(EaKit.dp(context, 30f)).coerceAtLeast(EaKit.dp(context, 8f))
            val cx = w / 2f
            val totalH = r * 2 + if (showLabel) labelH else 0f
            val cy = (h - totalH) / 2f + r
            if (on) {
                canvas.drawCircle(cx, cy, r, discOn)
            } else {
                canvas.drawCircle(cx, cy, r, discOff)
                canvas.drawCircle(cx, cy, r, ring)
            }
            icon?.let { d ->
                val s = (r * 0.95f).toInt()
                d.setBounds((cx - s / 2f).toInt(), (cy - s / 2f).toInt(), (cx + s / 2f).toInt(), (cy + s / 2f).toInt())
                d.setTint(if (on) iconOn else iconOff)
                d.draw(canvas)
            }
            if (on) {
                // Small accent status dot on the disc's rim.
                val a = Math.toRadians(-45.0)
                canvas.drawCircle(cx + r * 0.78f * Math.cos(a).toFloat(), cy + r * 0.78f * Math.sin(a).toFloat(), r * 0.13f, accentPaint)
            }
            if (showLabel) {
                EaKit.drawFitText(canvas, label.uppercase(), cx, cy + r + labelH * 0.75f, w - EaKit.dp(context, 2f), labelPaint)
            }
        }
    }

    companion object {
        private const val KEY_BT_ASKED = "bt_perm_asked"
    }
}
