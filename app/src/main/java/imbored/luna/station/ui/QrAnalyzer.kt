package imbored.luna.station.ui

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/**
 * Finds a QR code in camera frames with ZXing (on the device, nothing sent anywhere).
 * Only the brightness plane is used; a QR code needs nothing else. Reports once.
 */
class QrAnalyzer(private val onFound: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }

    @Volatile private var done = false

    override fun analyze(image: ImageProxy) {
        image.use {
            if (done) return
            val plane = it.planes[0]
            val stride = plane.rowStride
            val buffer = plane.buffer
            val bytes = ByteArray(maxOf(buffer.remaining(), stride * it.height))   // last row can be short
            buffer.get(bytes, 0, buffer.remaining())
            val source = PlanarYUVLuminanceSource(bytes, stride, it.height, 0, 0, it.width, it.height, false)
            val text = decode(source) ?: decode(source.invert())    // light-on-dark codes too
            if (text != null) {
                done = true
                onFound(text)
            }
        }
    }

    private fun decode(source: LuminanceSource): String? = try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (e: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}
