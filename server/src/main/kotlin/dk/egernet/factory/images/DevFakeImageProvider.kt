package dk.egernet.factory.images

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * A provider for development and the e2e suite (only offered with FACTORY_DEV_FAKE_GITHUB): accepts keys starting
 * with `fake-image-` and the model `fake-image-1`, and draws a PNG of the requested size. A prompt containing
 * `fake:refuse` is refused for its content, `fake:quota` runs out of quota.
 */
class DevFakeImageProvider : ImageProvider {
    override val id = "fake"
    override val label = "Fake (development)"
    override val models = listOf("fake-image-1")
    override val defaultModel = "fake-image-1"
    override val supportsTransparency = true
    override val keyPlaceholder = "fake-image-…"

    override fun validate(key: String, model: String) {
        if (!key.startsWith("fake-image-")) throw ImageProviderException(ImageProviderException.INVALID_CREDENTIAL)
        if (model != "fake-image-1") throw ImageProviderException(ImageProviderException.UNKNOWN_MODEL)
    }

    override fun generate(key: String, model: String, request: ImageRequest): List<ByteArray> {
        validate(key, model)
        if ("fake:refuse" in request.prompt) throw ImageProviderException(ImageProviderException.CONTENT_REFUSED)
        if ("fake:quota" in request.prompt) throw ImageProviderException(ImageProviderException.QUOTA)
        return (0 until request.count).map { draw(request.width, request.height, it, request.transparent) }
    }

    companion object {
        private val COLOURS = listOf(Color(0x2B, 0x6C, 0xB0), Color(0xC0, 0x56, 0x21), Color(0x2F, 0x85, 0x5A), Color(0x80, 0x5A, 0xD5))

        /** A simple, recognisable picture: a coloured disc on a light (or transparent) background. */
        fun draw(width: Int, height: Int, n: Int, transparent: Boolean): ByteArray {
            System.setProperty("java.awt.headless", "true")
            val img = BufferedImage(width, height, if (transparent) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                if (!transparent) {
                    g.color = Color(0xF4, 0xF1, 0xEA)
                    g.fillRect(0, 0, width, height)
                }
                g.color = COLOURS[n % COLOURS.size]
                val d = (minOf(width, height) * 0.7).toInt()
                g.fillOval((width - d) / 2, (height - d) / 2, d, d)
            } finally {
                g.dispose()
            }
            return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        }
    }
}
