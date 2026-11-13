package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The launcher icon, as square, circle and themed layers. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class IconTest {
    @Test
    fun icon() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val ctx = RuntimeEnvironment.getApplication()
        val size = 432
        val sheet = Bitmap.createBitmap(size * 3 + 80, size + 40, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet); c.drawColor(Color.WHITE)
        val bg = ctx.getDrawable(R.drawable.ic_launcher_background)!!
        val fg = ctx.getDrawable(R.drawable.ic_launcher_foreground)!!
        val mono = ctx.getDrawable(R.drawable.ic_launcher_monochrome)!!
        for (k in 0..2) {
            val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val lc = Canvas(layer)
            if (k < 2) { bg.setBounds(0, 0, size, size); bg.draw(lc); fg.setBounds(0, 0, size, size); fg.draw(lc) }
            else { lc.drawColor(Color.rgb(230, 225, 245)); mono.setTint(Color.rgb(60, 40, 120)); mono.setBounds(0, 0, size, size); mono.draw(lc) }
            // mask: rounded square, circle, circle
            val masked = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val mc = Canvas(masked)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            p.shader = android.graphics.BitmapShader(layer, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
            // the launcher shows the middle 72/108 of the layers
            val inset = size * 18f / 108f
            mc.save(); mc.scale(108f / 72f, 108f / 72f, size / 2f, size / 2f)
            if (k == 0) mc.drawRoundRect(inset, inset, size - inset, size - inset, size * 0.12f, size * 0.12f, p)
            else mc.drawCircle(size / 2f, size / 2f, size / 2f - inset, p)
            mc.restore()
            c.drawBitmap(masked, 20f + k * (size + 20f), 20f, null)
        }
        File(out, "icon.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
