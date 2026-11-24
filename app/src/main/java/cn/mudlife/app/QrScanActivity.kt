package cn.mudlife.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import cn.mudlife.app.utils.HapticHelper
import cn.mudlife.app.utils.PrefsHelper
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * 扫码绑定热水器。
 *
 * 设备二维码格式示例：KLCXKJ-Water,M,001122334455（虚构编号）。
 * 解析最后一个字段（冒号去掉后为 12 位十六进制）作为设备 snCode。
 *
 * 返回：RESULT_OK + extra "sn_code"
 */
class QrScanActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SN_CODE = "sn_code"
        const val EXTRA_ERROR = "error"

        fun parseSnCode(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            // 兼容带有 URL 的二维码：提取参数或路径末尾
            val target = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                val uri = try { android.net.Uri.parse(trimmed) } catch (_: Exception) { null }
                uri?.getQueryParameter("sn")
                    ?: uri?.getQueryParameter("snCode")
                    ?: uri?.getQueryParameter("device")
                    ?: uri?.getQueryParameter("mac")
                    ?: uri?.lastPathSegment
                    ?: trimmed
            } else trimmed

            val parts = target.split(",")
            val candidate = parts.lastOrNull()?.trim() ?: target.trim()
            val normalized = candidate.replace(":", "").uppercase()
            // 1. 优先匹配 12 位标准十六进制 MAC
            if (normalized.length == 12 && normalized.matches(Regex("[0-9A-F]{12}"))) {
                return normalized
            }
            // 2. 兼容 6~20 位字母/数字设备编号（如直饮水机 8 位数字 62591102 等）
            if (normalized.length in 6..20 && normalized.matches(Regex("[0-9A-Z]+"))) {
                return normalized
            }
            // 3. 兜底提取字符串中连续 6-16 位的十六进制或字母数字
            val match = Regex("(?<![0-9A-Za-z])[0-9A-Za-z]{6,16}(?![0-9A-Za-z])").find(raw)
            return match?.value?.uppercase()
        }
    }

    private lateinit var previewView: PreviewView
    private var scanning = false
    private var camera: Camera? = null
    private var scanner: BarcodeScanner? = null
    private var torchAvailable by mutableStateOf(false)
    private var torchOn by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showError("需要相机权限才能扫码")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        val colors = PrefsHelper.appearance(this).palette.dark
        setContent {
            ScanView(previewView, colors.Accent, colors.Background, torchAvailable, torchOn,
                onBack = ::finish,
                onTorch = {
                    HapticHelper.tick(this)
                    camera?.cameraControl?.enableTorch(!torchOn)
                })
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        val executor = ContextCompat.getMainExecutor(this)
        providerFuture.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val options = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                val reader = BarcodeScanning.getClient(options)
                scanner = reader
                analysis.setAnalyzer(executor) { frame ->
                    val image = frame.image
                    if (image == null || scanning || isFinishing || isDestroyed) {
                        frame.close()
                        return@setAnalyzer
                    }
                    val input = InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees)
                    reader.process(input)
                        .addOnSuccessListener { codes ->
                            if (!scanning && !isFinishing && !isDestroyed) {
                                codes.firstNotNullOfOrNull { parseSnCode(it.rawValue) }?.let { device ->
                                    scanning = true
                                    HapticHelper.tick(this)
                                    setResult(RESULT_OK, Intent().putExtra(EXTRA_SN_CODE, device))
                                    finish()
                                }
                            }
                        }
                        .addOnCompleteListener { frame.close() }
                }
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis).also { bound ->
                    torchAvailable = bound.cameraInfo.hasFlashUnit()
                    bound.cameraInfo.torchState.observe(this) { torchOn = it == TorchState.ON }
                }
            } catch (_: Exception) {
                showError("相机暂时无法打开，请重试")
            }
        }, executor)
    }

    override fun onDestroy() {
        camera?.cameraControl?.enableTorch(false)
        scanner?.close()
        super.onDestroy()
    }

    private fun showError(message: String) {
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, message))
        finish()
    }
}

@Composable
private fun ScanView(
    preview: PreviewView, accent: Color, paper: Color, torchAvailable: Boolean, torchOn: Boolean,
    onBack: () -> Unit, onTorch: () -> Unit
) {
    val ink = Color(0xFFF5F3EB)
    BoxWithConstraints(Modifier.fillMaxSize().background(paper)) {
        val opening = minOf(maxWidth * .70f, maxHeight * .48f, 320.dp)
        AndroidView(factory = { preview }, modifier = Modifier.matchParentSize())
        Canvas(Modifier.matchParentSize()) {
            val edge = opening.toPx()
            val radius = 17.dp.toPx()
            val left = (size.width - edge) / 2
            val top = (size.height - edge) / 2 - 14.dp.toPx()
            val frame = RoundRect(left, top, left + edge, top + edge, CornerRadius(radius))
            val cutout = Path().apply { addRoundRect(frame) }
            clipPath(cutout, clipOp = ClipOp.Difference) {
                drawRect(paper.copy(alpha = .65f))
            }
            val corner = 29.dp.toPx()
            val width = 3.dp.toPx()
            for (horizontal in listOf(1f, -1f)) for (vertical in listOf(1f, -1f)) {
                val x = if (horizontal > 0) frame.left else frame.right
                val y = if (vertical > 0) frame.top else frame.bottom
                val mark = Path().apply {
                    moveTo(x + horizontal * corner, y)
                    lineTo(x + horizontal * radius, y)
                    quadraticBezierTo(x, y, x, y + vertical * radius)
                    lineTo(x, y + vertical * corner)
                }
                drawPath(mark, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width, cap = StrokeCap.Round))
            }
        }
        Box(Modifier.align(Alignment.TopCenter).windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)
                .background(paper.copy(alpha = .86f), CircleShape)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = ink)
            }
            Text("扫描饮水机二维码", Modifier.align(Alignment.Center), color = ink, fontSize = 20.sp,
                fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium)
        }
        Text("将饮水机二维码放入框内",
            Modifier.align(Alignment.Center).offset(y = opening / 2 + 17.dp)
                .background(paper.copy(alpha = .84f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            color = ink, fontSize = 13.sp)
        if (torchAvailable) Column(
            Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconButton(onClick = onTorch, modifier = Modifier.size(56.dp)
                .background(if (torchOn) accent else paper.copy(alpha = .88f), CircleShape)
                .semantics { stateDescription = if (torchOn) "已开启" else "已关闭" }) {
                Icon(if (torchOn) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff, "手电筒",
                    tint = if (torchOn) paper else ink)
            }
            Text("手电筒", Modifier.padding(top = 7.dp), color = ink, fontSize = 12.sp)
        }
    }
}
