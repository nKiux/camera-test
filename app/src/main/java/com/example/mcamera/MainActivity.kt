package com.example.mcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.AttributeSet
import android.util.Range
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.slider.Slider
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** 依指定長寬比縮放並置中的預覽畫面。 */
class AutoFitTextureView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : TextureView(c, a) {
    var rw = 3
    var rh = 4
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = MeasureSpec.getSize(hSpec)
        if (w * rh < h * rw) setMeasuredDimension(w, w * rh / rw)
        else setMeasuredDimension(h * rw / rh, h)
    }
}

class MainActivity : AppCompatActivity() {
    private enum class Sel { FOCUS, SHUTTER, ISO, COMP }

    private lateinit var mgr: CameraManager
    private lateinit var chars: CameraCharacteristics
    private var camId = ""
    private var cam: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var prevReq: CaptureRequest.Builder? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private val ui = Handler(Looper.getMainLooper())

    // 相機能力
    private var expR: Range<Long> = Range(100_000L, 1_000_000_000L)
    private var isoR: Range<Int> = Range(100, 3200)
    private var minFocus = 0f          // 屈光度，0 代表定焦
    private var sensorOri = 90

    // 拍攝狀態
    private var tNs = 10_000_000L
    private var iso = 200
    private var autoIso = true
    private var mf = false
    private var diopter = 0f
    private var comp = 0f

    // 介面狀態
    private var sel = Sel.SHUTTER
    private var updating = false

    private lateinit var tv: AutoFitTextureView
    private lateinit var chipMeter: Chip
    private lateinit var tgSel: MaterialButtonToggleGroup
    private lateinit var tgMode: MaterialButtonToggleGroup
    private lateinit var btnA: MaterialButton
    private lateinit var btnB: MaterialButton
    private lateinit var tileFocus: MaterialButton
    private lateinit var tileShutter: MaterialButton
    private lateinit var tileIso: MaterialButton
    private lateinit var tileComp: MaterialButton
    private lateinit var slider: Slider
    private lateinit var fab: ExtendedFloatingActionButton

    private val tLo get() = max(expR.lower, 20_000L)
    private val tHi get() = min(expR.upper, 1_000_000_000L)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        DynamicColors.applyToActivityIfAvailable(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        pickCamera()
        setContentView(R.layout.activity_main)
        bindUi()
        applySel()
        refresh()
        if (!hasPerm()) requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    private fun hasPerm() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (hasPerm() && tv.isAvailable) openCam()
    }

    /** 選擇支援手動感光元件控制的後置鏡頭。 */
    private fun pickCamera() {
        for (id in mgr.cameraIdList) {
            val c = mgr.getCameraCharacteristics(id)
            val back = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            val manual = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
            if (back && manual) {
                camId = id; chars = c
                expR = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)!!
                isoR = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)!!
                minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                sensorOri = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                tNs = tNs.coerceIn(tLo, tHi)
                iso = iso.coerceIn(isoR.lower, isoR.upper)
                return
            }
        }
        Toast.makeText(this, "找不到支援手動曝光的後置鏡頭", Toast.LENGTH_LONG).show()
    }

    // ───────── 相機控制 ─────────

    private fun startThread() {
        if (thread != null) return
        thread = HandlerThread("cam").also { it.start(); bg = Handler(it.looper) }
    }

    @SuppressLint("MissingPermission")
    private fun openCam() {
        if (cam != null || camId.isEmpty()) return
        startThread()
        mgr.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(c: CameraDevice) { cam = c; startPreview() }
            override fun onDisconnected(c: CameraDevice) { c.close(); cam = null }
            override fun onError(c: CameraDevice, e: Int) { c.close(); cam = null }
        }, bg)
    }

    private fun startPreview() {
        val c = cam ?: return
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val jpg = map.getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.width.toLong() * it.height }!!
        val prev = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width * jpg.height == it.height * jpg.width && it.width <= 1920 }
            .maxByOrNull { it.width * it.height }
            ?: map.getOutputSizes(SurfaceTexture::class.java).first()
        ui.post { tv.rw = prev.height; tv.rh = prev.width; tv.requestLayout() }

        reader = ImageReader.newInstance(jpg.width, jpg.height, ImageFormat.JPEG, 2).also { r ->
            r.setOnImageAvailableListener({ ir ->
                val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                    save(bytes)
                } finally { img.close() }
            }, bg)
        }
        val st = tv.surfaceTexture ?: return
        st.setDefaultBufferSize(prev.width, prev.height)
        val surf = Surface(st)
        prevReq = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surf) }
        @Suppress("DEPRECATION")
        c.createCaptureSession(listOf(surf, reader!!.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { session = s; push() }
            override fun onConfigureFailed(s: CameraCaptureSession) {}
        }, bg)
    }

    /** 把目前的手動參數寫進請求：關閉自動曝光，由使用者決定快門與感光度。 */
    private fun fill(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, tNs)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(tNs, 33_333_333L))
        if (mf) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopter)
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
    }

    private fun push() {
        val s = session ?: return
        val b = prevReq ?: return
        fill(b)
        try { s.setRepeatingRequest(b.build(), null, bg) } catch (_: Exception) {}
    }

    private fun shoot() {
        val s = session ?: return
        val c = cam ?: return
        val r = reader ?: return
        try {
            val b = c.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(r.surface)
            fill(b)
            b.set(CaptureRequest.JPEG_ORIENTATION, sensorOri)
            b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            s.capture(b.build(), null, bg)
        } catch (_: Exception) {}
    }

    private fun save(bytes: ByteArray) {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "M_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MCamera")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        ui.post { Toast.makeText(this, "已儲存到 Pictures/MCamera", Toast.LENGTH_SHORT).show() }
    }

    // ───────── 測光與自動 ISO ─────────
    // 只讀取預覽的縮小亮度，用來決定 ISO；不對影像做任何處理。

    private val meterTick = object : Runnable {
        override fun run() { meter(); ui.postDelayed(this, 250) }
    }

    private fun meter() {
        if (!tv.isAvailable || session == null) return
        val bmp = tv.getBitmap(24, 24) ?: return
        val px = IntArray(24 * 24)
        bmp.getPixels(px, 0, 24, 0, 0, 24, 24)
        bmp.recycle()
        var sum = 0.0
        var wt = 0.0
        for (y in 0 until 24) for (x in 0 until 24) {
            val p = px[y * 24 + x]
            val l = (0.2126 * Color.red(p) + 0.7152 * Color.green(p) + 0.0722 * Color.blue(p)) / 255.0
            val k = 1 + 2 * (1 - min(1.0, hypot(x - 11.5, y - 11.5) / 16.3)) // 中央重點
            sum += l.pow(2.2) * k
            wt += k
        }
        val lum = max(sum / wt, 1e-4)
        val target = 0.18 * 2.0.pow(comp.toDouble())
        val ev = log2(lum / target)
        showMeter(ev)
        if (autoIso && abs(ev) > 0.15) {
            val n = (iso * (target / lum).pow(0.5)).roundToInt().coerceIn(isoR.lower, isoR.upper)
            if (n != iso) { iso = n; push(); refresh(sel == Sel.ISO) }
        }
    }

    /** 曝光接近目標時，測光標籤換成 tertiary 容器色。 */
    private fun showMeter(ev: Double) {
        val ok = abs(ev) < 0.4
        val bgAttr = if (ok) com.google.android.material.R.attr.colorTertiaryContainer
        else com.google.android.material.R.attr.colorSurfaceContainerHigh
        val fgAttr = if (ok) com.google.android.material.R.attr.colorOnTertiaryContainer
        else com.google.android.material.R.attr.colorOnSurface
        chipMeter.text = "測光 %+.1f EV".format(ev)
        chipMeter.chipBackgroundColor = ColorStateList.valueOf(MaterialColors.getColor(chipMeter, bgAttr))
        chipMeter.setTextColor(MaterialColors.getColor(chipMeter, fgAttr))
    }

    // ───────── 介面 ─────────

    private fun bindUi() {
        tv = findViewById(R.id.tv)
        chipMeter = findViewById(R.id.chipMeter)
        tgSel = findViewById(R.id.tgSel)
        tgMode = findViewById(R.id.tgMode)
        btnA = findViewById(R.id.btnA)
        btnB = findViewById(R.id.btnB)
        tileFocus = findViewById(R.id.tileFocus)
        tileShutter = findViewById(R.id.tileShutter)
        tileIso = findViewById(R.id.tileIso)
        tileComp = findViewById(R.id.tileComp)
        slider = findViewById(R.id.slider)
        fab = findViewById(R.id.fab)

        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { if (hasPerm()) openCam() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }

        tgSel.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            sel = when (id) {
                R.id.tileFocus -> Sel.FOCUS
                R.id.tileShutter -> Sel.SHUTTER
                R.id.tileIso -> Sel.ISO
                else -> Sel.COMP
            }
            applySel()
        }

        tgMode.addOnButtonCheckedListener { _, id, checked ->
            if (updating || !checked) return@addOnButtonCheckedListener
            when (sel) {
                Sel.FOCUS -> mf = id == R.id.btnB
                Sel.ISO -> autoIso = id == R.id.btnA
                else -> {}
            }
            push(); syncSlider(); refresh(false)
        }

        slider.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            when (sel) {
                Sel.FOCUS -> diopter = v / 1000f * minFocus
                Sel.SHUTTER -> tNs = (tLo * (tHi.toDouble() / tLo).pow(v / 1000.0)).toLong()
                Sel.ISO -> iso = (isoR.lower * (isoR.upper.toDouble() / isoR.lower).pow(v / 1000.0)).roundToInt()
                Sel.COMP -> comp = v / 3f
            }
            push(); refresh(false)
        }

        fab.setOnClickListener { shoot() }
    }

    /** 切換目前調整的參數：重設滑桿範圍與副選項。 */
    private fun applySel() {
        updating = true
        when (sel) {
            Sel.FOCUS -> {
                btnA.text = "AF"; btnB.text = "MF"; btnB.isEnabled = minFocus > 0f
                tgMode.check(if (mf) R.id.btnB else R.id.btnA)
            }
            Sel.ISO -> {
                btnA.text = "自動"; btnB.text = "手動"; btnB.isEnabled = true
                tgMode.check(if (autoIso) R.id.btnA else R.id.btnB)
            }
            else -> {}
        }
        tgMode.visibility = if (sel == Sel.FOCUS || sel == Sel.ISO) View.VISIBLE else View.INVISIBLE
        if (sel == Sel.COMP) {            // 以三分之一級為單位，範圍正負三級
            slider.stepSize = 1f; slider.valueFrom = -9f; slider.valueTo = 9f
        } else {
            slider.stepSize = 0f; slider.valueFrom = 0f; slider.valueTo = 1000f
        }
        updating = false
        syncSlider()
    }

    private fun syncSlider() {
        val v = when (sel) {
            Sel.FOCUS -> if (minFocus > 0f) diopter / minFocus * 1000f else 0f
            Sel.SHUTTER -> (ln(tNs.toDouble() / tLo) / ln(tHi.toDouble() / tLo) * 1000).toFloat()
            Sel.ISO -> (ln(iso.toDouble() / isoR.lower) / ln(isoR.upper.toDouble() / isoR.lower) * 1000).toFloat()
            Sel.COMP -> (comp * 3f).roundToInt().toFloat()
        }
        slider.value = v.coerceIn(slider.valueFrom, slider.valueTo)
        slider.isEnabled = when (sel) {
            Sel.FOCUS -> mf
            Sel.ISO -> !autoIso
            else -> true
        }
    }

    private fun fmtT(ns: Long): String {
        val s = ns / 1e9
        return if (s >= 0.5) "%.1f″".format(s) else "1/${(1 / s).roundToInt()}"
    }

    private fun refresh(sync: Boolean = true) {
        tileFocus.text = "對焦\n" + when {
            !mf -> "AF"
            diopter < 0.05f -> "∞"
            else -> "%.2f m".format(1f / diopter)
        }
        tileShutter.text = "快門\n" + fmtT(tNs)
        tileIso.text = "ISO\n" + (if (autoIso) "A " else "") + iso
        tileComp.text = "補償\n" + "%+.1f".format(comp)
        if (sync) syncSlider()
    }

    // ───────── 生命週期 ─────────

    override fun onResume() {
        super.onResume()
        ui.post(meterTick)
        if (hasPerm() && tv.isAvailable) openCam()
    }

    override fun onPause() {
        ui.removeCallbacks(meterTick)
        session?.close(); session = null
        cam?.close(); cam = null
        reader?.close(); reader = null
        thread?.quitSafely(); thread = null; bg = null
        super.onPause()
    }
}
