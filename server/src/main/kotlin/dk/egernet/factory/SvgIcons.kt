package dk.egernet.factory

import dk.egernet.factory.agent.INLINE_IMAGE_TYPES
import dk.egernet.factory.agent.Sniff
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLResolver
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/** What [SvgIcons.inspect] found. */
sealed interface SvgResult {
    /** Not an SVG document at all (the upload is answered 415). */
    data object NotSvg : SvgResult
    /** An SVG document that breaks a rule; [reason] completes "The SVG cannot be used: …" (answered 422). */
    data class Refused(val reason: String) : SvgResult
    /** An SVG that may be stored and served unchanged. */
    data object Valid : SvgResult
}

/**
 * Validation of SVG project icons (docs/ARCHITECTURE.md §8 "Project icons"). One streaming pass with the JDK's StAX
 * parser, hardened against DTDs and entities, over an allowlist of elements and attributes. Nothing is rewritten:
 * a document is either accepted as it is or refused with the first violation in document order. All checks run on
 * parser-decoded values (attribute values and text after character-reference resolution).
 */
object SvgIcons {
    const val MEDIA_TYPE = "image/svg+xml"

    private const val SVG_NS = "http://www.w3.org/2000/svg"
    private const val XLINK_NS = "http://www.w3.org/1999/xlink"
    private const val XML_NS = "http://www.w3.org/XML/1998/namespace"

    /** Namespaces of editor metadata that browsers attach no behaviour to (Inkscape, sodipodi, RDF, CC, Dublin Core). */
    private val EDITOR_NAMESPACES = setOf(
        "http://www.inkscape.org/namespaces/inkscape",
        "http://sodipodi.sourceforge.net/DTD/sodipodi-0.dtd",
        "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
        "http://creativecommons.org/ns#",
        "http://purl.org/dc/elements/1.1/",
    )

    /** SVG elements that may appear (local names in the SVG namespace, case-sensitive). */
    private val ELEMENTS = setOf(
        // Structure and metadata
        "svg", "g", "defs", "symbol", "use", "switch", "title", "desc", "metadata", "style",
        // Shapes
        "path", "rect", "circle", "ellipse", "line", "polyline", "polygon",
        // Text
        "text", "tspan", "textPath",
        // Paint servers and effects
        "linearGradient", "radialGradient", "stop", "pattern", "clipPath", "mask", "marker", "image", "filter",
        // Filter primitives
        "feBlend", "feColorMatrix", "feComponentTransfer", "feComposite", "feConvolveMatrix", "feDiffuseLighting",
        "feDisplacementMap", "feDistantLight", "feDropShadow", "feFlood", "feFuncA", "feFuncB", "feFuncG", "feFuncR",
        "feGaussianBlur", "feMerge", "feMergeNode", "feMorphology", "feOffset", "fePointLight", "feSpecularLighting",
        "feSpotLight", "feTile", "feTurbulence",
    )

    /** Attributes without a namespace that may appear on any allowed SVG element (case-sensitive). */
    private val ATTRIBUTES = setOf(
        // Core
        "id", "class", "style", "lang", "role", "version", "baseProfile", "x", "y", "width", "height", "viewBox",
        "preserveAspectRatio", "transform", "href",
        // Geometry
        "d", "points", "x1", "y1", "x2", "y2", "cx", "cy", "r", "rx", "ry", "pathLength",
        // Paint and rendering
        "fill", "fill-opacity", "fill-rule",
        "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "stroke-dasharray",
        "stroke-dashoffset", "stroke-opacity",
        "opacity", "color", "display", "visibility", "overflow",
        "clip-path", "clip-rule", "mask", "filter",
        "marker-start", "marker-mid", "marker-end",
        "paint-order", "vector-effect", "shape-rendering", "color-rendering", "image-rendering", "text-rendering",
        "color-interpolation", "color-interpolation-filters",
        "mix-blend-mode", "isolation", "enable-background",
        "stop-color", "stop-opacity", "flood-color", "flood-opacity", "lighting-color",
        // Text
        "font-family", "font-size", "font-size-adjust", "font-stretch", "font-style", "font-variant", "font-weight",
        "text-anchor", "text-decoration", "letter-spacing", "word-spacing",
        "dominant-baseline", "alignment-baseline", "baseline-shift",
        "direction", "unicode-bidi", "writing-mode",
        "dx", "dy", "rotate", "textLength", "lengthAdjust", "startOffset", "method", "spacing", "side",
        // Gradients, patterns, markers, clipping
        "offset", "gradientUnits", "gradientTransform", "spreadMethod", "fx", "fy", "fr",
        "patternUnits", "patternContentUnits", "patternTransform",
        "markerWidth", "markerHeight", "markerUnits", "refX", "refY", "orient",
        "clipPathUnits", "maskUnits", "maskContentUnits", "filterUnits", "primitiveUnits",
        // Filter primitives
        "in", "in2", "result", "stdDeviation", "values", "type", "mode", "operator",
        "k1", "k2", "k3", "k4",
        "scale", "xChannelSelector", "yChannelSelector",
        "order", "kernelMatrix", "divisor", "bias", "targetX", "targetY", "edgeMode", "kernelUnitLength", "preserveAlpha",
        "surfaceScale", "diffuseConstant", "specularConstant", "specularExponent",
        "azimuth", "elevation", "z", "pointsAtX", "pointsAtY", "pointsAtZ", "limitingConeAngle",
        "radius", "baseFrequency", "numOctaves", "seed", "stitchTiles",
        "tableValues", "slope", "intercept", "amplitude", "exponent",
        // Conditional
        "systemLanguage", "requiredFeatures", "requiredExtensions",
    )

    /** The only attributes a `<style>` element may carry. */
    private val STYLE_ATTRIBUTES = setOf("type", "media", "id")

    /** Elements on which `href` / `xlink:href` may appear. */
    private val REFERENCE_ELEMENTS = setOf("use", "image", "linearGradient", "radialGradient", "pattern", "filter", "textPath")

    private val FRAGMENT = Regex("""^#[^\s#]+$""")
    private val RASTER_DATA_URL = Regex("""^data:image/(png|jpeg|jpg|gif|webp);base64,(.*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val URL_START = Regex("""url\s*\(""", RegexOption.IGNORE_CASE)
    private val LOCAL_URL = Regex("""url\s*\(\s*(#[^\s'"()]+|'#[^\s'"()]+'|"#[^\s'"()]+")\s*\)""", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("""\s+""")
    private val VIEWBOX_SEPARATOR = Regex("""[\s,]+""")
    private val NUMBER = Regex("""^[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?$""")
    private val PIXELS = Regex("""^\+?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?(px)?$""")
    /** CSS constructs refused anywhere in styles (checked on the lowercased text). */
    private val CSS_FORBIDDEN = listOf("\\", "@import", "@font-face", "javascript:", "expression(", "image-set(")

    private const val NOT_WELL_FORMED = "it is not well-formed XML"
    private const val CSS_REASON = "its styles contain @import, an external url() or another construct that is not allowed"
    private const val IMAGE_REASON = "an <image> may only embed a PNG, JPEG, WebP or GIF as a data: URL"

    /** Thrown internally to stop at the first violation. */
    private class Violation(val reason: String) : Exception(reason, null, false, false)

    /** Checks [bytes] as an SVG document (docs/ARCHITECTURE.md §8 "Project icons" lists the rules). */
    fun inspect(bytes: ByteArray): SvgResult {
        if (!strictUtf8(bytes)) return SvgResult.NotSvg
        val reader = try {
            factory().createXMLStreamReader(ByteArrayInputStream(bytes))
        } catch (e: XMLStreamException) {
            return SvgResult.NotSvg
        } catch (e: RuntimeException) {
            return SvgResult.NotSvg
        }
        var rootSeen = false
        return try {
            walk(reader) { rootSeen = true }
        } catch (v: Violation) {
            SvgResult.Refused(v.reason)
        } catch (e: XMLStreamException) {
            if (rootSeen) SvgResult.Refused(NOT_WELL_FORMED) else SvgResult.NotSvg
        } catch (e: RuntimeException) {
            if (rootSeen) SvgResult.Refused(NOT_WELL_FORMED) else SvgResult.NotSvg
        } finally {
            runCatching { reader.close() }
        }
    }

    /** Strictly valid UTF-8 over the whole file (a BOM is just U+FEFF) and no NUL byte. */
    private fun strictUtf8(bytes: ByteArray): Boolean {
        if (bytes.any { it == 0.toByte() }) return false
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { dec.decode(ByteBuffer.wrap(bytes)) }.isSuccess
    }

    /** A fresh, hardened factory per call: no DTDs, no external entities, no entity expansion, no resolution. */
    private fun factory(): XMLInputFactory = XMLInputFactory.newDefaultFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
        setProperty(XMLInputFactory.IS_COALESCING, true)
        xmlResolver = XMLResolver { _, _, _, _ -> throw XMLStreamException("External resources are not resolved") }
        runCatching { setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "") }
    }

    /** Reads the whole document; returns [SvgResult.NotSvg] or [SvgResult.Valid], throws [Violation] on a broken rule. */
    private fun walk(r: XMLStreamReader, onRoot: () -> Unit): SvgResult {
        // Problems in the prolog only count once the root turns out to be an `svg`.
        var prologProblem: String? = null
        var rootSeen = false
        var depth = 0
        var styleDepth = 0
        val styleText = StringBuilder()

        r.characterEncodingScheme?.let { if (!it.equals("UTF-8", ignoreCase = true)) prologProblem = "it must be encoded as UTF-8" }
        while (r.hasNext()) {
            when (r.next()) {
                XMLStreamConstants.DTD -> {
                    if (!rootSeen) prologProblem = prologProblem ?: "it contains a DOCTYPE" else throw Violation("it contains a DOCTYPE")
                }
                XMLStreamConstants.PROCESSING_INSTRUCTION -> {
                    val reason = "it contains a processing instruction"
                    if (!rootSeen) prologProblem = prologProblem ?: reason else throw Violation(reason)
                }
                XMLStreamConstants.ENTITY_REFERENCE -> throw Violation("it contains an entity reference")
                XMLStreamConstants.START_ELEMENT -> {
                    if (!rootSeen) {
                        if (r.localName != "svg") return SvgResult.NotSvg
                        rootSeen = true
                        onRoot()
                        prologProblem?.let { throw Violation(it) }
                        if (r.namespaceURI != SVG_NS) throw Violation("the root element must be <svg xmlns=\"$SVG_NS\">")
                        checkElement(r)
                        checkRootSize(r)
                    } else {
                        checkElement(r)
                    }
                    depth++
                    if (styleDepth > 0) styleDepth++
                    else if (r.namespaceURI == SVG_NS && r.localName == "style") { styleDepth = 1; styleText.setLength(0) }
                }
                XMLStreamConstants.END_ELEMENT -> {
                    depth--
                    if (styleDepth > 0 && --styleDepth == 0) checkCss(styleText.toString())
                }
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                    if (styleDepth > 0) styleText.append(r.text)
                }
            }
        }
        return if (rootSeen && depth == 0) SvgResult.Valid else SvgResult.NotSvg
    }

    private fun qualified(prefix: String?, local: String) = if (prefix.isNullOrEmpty()) local else "$prefix:$local"

    private fun checkElement(r: XMLStreamReader) {
        val ns = r.namespaceURI.orEmpty()
        val local = r.localName
        val inEditorNs = ns in EDITOR_NAMESPACES
        if (!inEditorNs) {
            when {
                local == "script" -> throw Violation("it contains a <script> element")
                local == "foreignObject" -> throw Violation("it contains a <foreignObject> element")
                ns != SVG_NS || local !in ELEMENTS -> throw Violation("the element ${qualified(r.prefix, local)} is not allowed")
            }
        }
        for (i in 0 until r.attributeCount) {
            val aNs = r.getAttributeNamespace(i).orEmpty()
            val aLocal = r.getAttributeLocalName(i)
            val name = qualified(r.getAttributePrefix(i), aLocal)
            val value = r.getAttributeValue(i)
            if (aLocal.startsWith("on", ignoreCase = true)) throw Violation("it contains an event handler attribute ($name)")
            if (inEditorNs) continue
            checkSvgAttribute(local, aNs, aLocal, name, value)
        }
    }

    private fun checkSvgAttribute(element: String, ns: String, local: String, name: String, value: String) {
        val notAllowed = { Violation("the attribute $name is not allowed") }
        when {
            ns.isEmpty() && element == "style" -> {
                if (local !in STYLE_ATTRIBUTES) throw notAllowed()
                if (local == "type" && !value.trim().equals("text/css", ignoreCase = true)) throw notAllowed()
            }
            ns.isEmpty() && local == "href" -> checkReference(element, name, value) ?: throw notAllowed()
            ns == XLINK_NS && local == "href" -> checkReference(element, name, value) ?: throw notAllowed()
            ns.isEmpty() && (local in ATTRIBUTES || local.startsWith("data-") || local.startsWith("aria-")) -> {
                if (local == "style") checkCss(value)
                else {
                    // Presentation attributes are parsed as CSS: an escape could spell `url(` without the letters.
                    if (local in ATTRIBUTES && '\\' in value) throw Violation("the attribute $name contains a backslash")
                    checkUrls(name, value)
                }
            }
            ns == XML_NS && (local == "lang" || local == "space") -> Unit
            ns in EDITOR_NAMESPACES -> checkUrls(name, value)
            else -> throw notAllowed()
        }
    }

    /** Null when [element] may not carry a reference at all; otherwise checks [value] (throws on a violation). */
    private fun checkReference(element: String, name: String, value: String): Unit? {
        if (element !in REFERENCE_ELEMENTS) return null
        val v = value.trim()
        if (element == "image") {
            val m = RASTER_DATA_URL.matchEntire(v) ?: throw Violation(IMAGE_REASON)
            val decoded = try {
                Base64.getDecoder().decode(m.groupValues[2].replace(WHITESPACE, ""))
            } catch (e: IllegalArgumentException) {
                throw Violation(IMAGE_REASON)
            }
            if (Sniff.mediaType(decoded) !in INLINE_IMAGE_TYPES) throw Violation(IMAGE_REASON)
        } else if (!FRAGMENT.matches(v)) {
            throw Violation("it references something outside the document ($name)")
        }
        return Unit
    }

    /** True when every `url(` in [value] is a reference to a fragment of this document. */
    private fun onlyLocalUrls(value: String): Boolean =
        URL_START.findAll(value).all { LOCAL_URL.matchesAt(value, it.range.first) }

    private fun checkUrls(name: String, value: String) {
        if (!onlyLocalUrls(value)) throw Violation("it references something outside the document ($name)")
    }

    private fun checkCss(css: String) {
        val lower = css.lowercase()
        if (CSS_FORBIDDEN.any { it in lower } || !onlyLocalUrls(css)) throw Violation(CSS_REASON)
    }

    /** The root needs a usable viewBox, or a positive width and height in pixels. */
    private fun checkRootSize(r: XMLStreamReader) {
        fun attr(local: String): String? =
            (0 until r.attributeCount).firstOrNull { r.getAttributeNamespace(it).isNullOrEmpty() && r.getAttributeLocalName(it) == local }
                ?.let { r.getAttributeValue(it).trim() }
        val viewBoxOk = attr("viewBox")?.split(VIEWBOX_SEPARATOR)?.filter { it.isNotEmpty() }
            ?.takeIf { parts -> parts.size == 4 && parts.all { NUMBER.matches(it) } }
            ?.map { it.toDouble() }
            ?.let { it.all { n -> n.isFinite() } && it[2] > 0 && it[3] > 0 } == true
        fun pixels(v: String?): Boolean {
            val m = v?.let { PIXELS.matchEntire(it) } ?: return false
            val n = v.removeSuffix("px").toDoubleOrNull() ?: return false
            return m.value == v && n.isFinite() && n > 0
        }
        if (!viewBoxOk && !(pixels(attr("width")) && pixels(attr("height")))) {
            throw Violation("it needs a viewBox, or a width and height in pixels")
        }
    }
}
