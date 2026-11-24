package dk.egernet.factory

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/** Unit tests of [SvgIcons.inspect] (docs/ARCHITECTURE.md §8 "Project icons"). */
class SvgIconsTest {
    private val ns = """xmlns="http://www.w3.org/2000/svg""""
    private val xlink = """xmlns:xlink="http://www.w3.org/1999/xlink""""

    /** An SVG document with a viewBox around [body]; [attrs] go on the root. */
    private fun svg(body: String, attrs: String = "") = """<svg $ns $xlink viewBox="0 0 24 24"$attrs>$body</svg>"""

    private fun inspect(doc: String) = SvgIcons.inspect(doc.toByteArray())

    private fun assertValid(doc: String) = assertEquals(SvgResult.Valid, inspect(doc), doc)
    private fun assertNotSvg(doc: ByteArray) = assertEquals(SvgResult.NotSvg, SvgIcons.inspect(doc))
    private fun assertRefused(reason: String, doc: String) = assertEquals(SvgResult.Refused(reason), inspect(doc), doc)

    private val pngData = "data:image/png;base64," + Base64.getEncoder().encodeToString(png(2, 2))
    private val outside = { name: String -> "it references something outside the document ($name)" }
    private val css = "its styles contain @import, an external url() or another construct that is not allowed"
    private val image = "an <image> may only embed a PNG, JPEG, WebP or GIF as a data: URL"

    @Test
    fun `accepted documents`() {
        // XML declaration and BOM.
        assertValid("\uFEFF<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + svg("<path d=\"M0 0h24v24H0z\"/>"))
        assertValid("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"no\"?>" + svg("<rect width=\"4\" height=\"4\"/>"))
        // Only a viewBox (also comma-separated), only width/height.
        assertValid("""<svg $ns viewBox="0,0,10,10"><circle cx="5" cy="5" r="4"/></svg>""")
        assertValid("""<svg $ns width="32" height="32px"><circle cx="5" cy="5" r="4"/></svg>""")
        // With a viewBox, width/height may be percentages.
        assertValid("""<svg $ns viewBox="0 0 10 10" width="100%" height="100%"/>""")
        // Gradients, clipPath, use with href and xlink:href to fragments.
        assertValid(svg("""
            <defs>
              <linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#f00"/><stop offset="1" stop-color="#00f"/></linearGradient>
              <radialGradient id="r" xlink:href="#g"/>
              <clipPath id="c"><circle cx="12" cy="12" r="10"/></clipPath>
              <path id="p" d="M0 0L10 10"/>
            </defs>
            <g clip-path="url(#c)"><rect width="24" height="24" fill="url(#g)"/></g>
            <use href="#p"/><use xlink:href="#p" x="2"/>
            <text><textPath href="#p">Hi &amp; &#233;</textPath></text>
        """))
        // <style> and fill="url(#g)" with quotes and whitespace.
        assertValid(svg("""<style type="text/css">.a{fill:url( '#g' )} .b{stroke:url("#g")}</style><rect class="a" fill=" url(#g) " width="1" height="1"/>"""))
        assertValid(svg("""<style><![CDATA[ .a > .b { fill: red } ]]></style>"""))
        // An embedded Base64 PNG, with whitespace in the payload.
        assertValid(svg("""<image width="24" height="24" href=" data:image/png;base64,${pngData.substringAfter(",").chunked(20).joinToString("\n ")} "/>"""))
        assertValid(svg("""<image xlink:href="DATA:IMAGE/PNG;BASE64,${pngData.substringAfter(",")}"/>"""))
        // Inkscape / sodipodi / RDF metadata, comments, data- and aria- attributes, xml:space.
        assertValid("""<svg $ns xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape"
              xmlns:sodipodi="http://sodipodi.sourceforge.net/DTD/sodipodi-0.dtd"
              xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:cc="http://creativecommons.org/ns#"
              xmlns:dc="http://purl.org/dc/elements/1.1/" width="24" height="24" inkscape:version="1.3" sodipodi:docname="x.svg">
              <!-- a comment -->
              <sodipodi:namedview id="nv" inkscape:zoom="1"/>
              <metadata><rdf:RDF><cc:Work rdf:about=""><dc:format>image/svg+xml</dc:format></cc:Work></rdf:RDF></metadata>
              <g inkscape:label="Layer 1" inkscape:groupmode="layer" data-x="1" aria-hidden="true" xml:space="preserve"><path d="M0 0"/></g>
            </svg>""")
    }

    @Test
    fun `not an SVG`() {
        assertNotSvg("just some text".toByteArray())
        assertNotSvg("<html><script>alert(1)</script></html>".toByteArray())
        assertNotSvg("<!DOCTYPE html><html><body/></html>".toByteArray())
        assertNotSvg("%PDF-1.4\n".toByteArray())
        assertNotSvg(byteArrayOf(0x3C, 0x73, 0x76, 0x67, 0xC3.toByte(), 0x28, 0x3E)) // invalid UTF-8
        assertNotSvg("<svg $ns viewBox=\"0 0 1 1\">\u0000</svg>".toByteArray())
        assertNotSvg(svg("").toByteArray(Charsets.UTF_16))
        assertNotSvg(ByteArray(0))
    }

    @Test
    fun `forbidden elements`() {
        assertRefused("it contains a <script> element", svg("<script>alert(1)</script>"))
        assertRefused("it contains a <foreignObject> element", svg("<foreignObject><div xmlns=\"http://www.w3.org/1999/xhtml\"/></foreignObject>"))
        assertRefused("the element a is not allowed", svg("<a href=\"#x\"><rect/></a>"))
        assertRefused("the element animate is not allowed", svg("<animate attributeName=\"x\"/>"))
        assertRefused("the element set is not allowed", svg("<set attributeName=\"href\" to=\"javascript:alert(1)\"/>"))
        assertRefused("the element feImage is not allowed", svg("<filter id=\"f\"><feImage href=\"#x\"/></filter>"))
        assertRefused("the element h:div is not allowed", svg("<h:div xmlns:h=\"http://www.w3.org/1999/xhtml\"/>"))
        assertRefused("the element math is not allowed", svg("<math xmlns=\"http://www.w3.org/1998/Math/MathML\"/>"))
        assertRefused("the element g is not allowed", svg("<g xmlns=\"\"/>"))
        assertRefused("it contains a <script> element", svg("<h:script xmlns:h=\"http://www.w3.org/1999/xhtml\">alert(1)</h:script>"))
    }

    @Test
    fun `forbidden attributes`() {
        assertRefused("it contains an event handler attribute (onload)", svg("", " onload=\"alert(1)\""))
        assertRefused("it contains an event handler attribute (ONLOAD)", svg("<rect ONLOAD=\"alert(1)\"/>"))
        assertRefused("it contains an event handler attribute (x:onclick)", svg("<rect xmlns:x=\"urn:x\" x:onclick=\"alert(1)\"/>"))
        assertRefused(
            "it contains an event handler attribute (inkscape:onclick)",
            svg("<inkscape:x xmlns:inkscape=\"http://www.inkscape.org/namespaces/inkscape\" inkscape:onclick=\"alert(1)\"/>"),
        )
        assertRefused("the attribute xml:base is not allowed", svg("<g xml:base=\"https://evil.example/\"/>"))
        assertRefused("the attribute xlink:title is not allowed", svg("<g xlink:title=\"x\"/>"))
        assertRefused("the attribute x:foo is not allowed", svg("<g xmlns:x=\"urn:x\" x:foo=\"1\"/>"))
        assertRefused("the attribute attributeName is not allowed", svg("<g attributeName=\"x\"/>"))
        assertRefused("the attribute href is not allowed", svg("<rect href=\"#x\"/>"))
        assertRefused("the attribute media is not allowed", svg("<rect media=\"screen\"/>"))
        assertRefused("the attribute type is not allowed", svg("<style type=\"text/javascript\"/>"))
        assertRefused("the attribute class is not allowed", svg("<style class=\"x\"/>"))
    }

    @Test
    fun `references`() {
        assertRefused(outside("href"), svg("<use href=\"javascript:alert(1)\"/>"))
        assertRefused(outside("href"), svg("<use href=\"&#106;avascript:alert(1)\"/>"))
        assertRefused(outside("xlink:href"), svg("<use xlink:href=\"JaVaScRiPt:alert(1)\"/>"))
        assertRefused(outside("href"), svg("<use href=\"https://evil.example/x.svg#a\"/>"))
        assertRefused(outside("xlink:href"), svg("<use xlink:href=\"other.svg#a\"/>"))
        assertRefused(outside("href"), svg("<use href=\"//evil.example/x.svg#a\"/>"))
        assertRefused(outside("href"), svg("<linearGradient href=\"data:image/svg+xml,&lt;svg/&gt;#a\"/>"))
        assertRefused(outside("href"), svg("<use href=\"file:///etc/passwd\"/>"))
        assertRefused(image, svg("<image href=\"https://evil.example/x.png\"/>"))
        assertRefused(image, svg("<image xlink:href=\"logo.png\"/>"))
        assertRefused(image, svg("<image href=\"javascript:alert(1)\"/>"))
        assertRefused(image, svg("<image href=\"data:image/svg+xml;base64,PHN2Zy8+\"/>"))
        assertRefused(image, svg("<image href=\"data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==\"/>"))
        assertRefused(image, svg("<image href=\"data:image/png;base64,${Base64.getEncoder().encodeToString("<svg/>".toByteArray())}\"/>"))
        assertRefused(image, svg("<image href=\"data:image/png;base64,!!!not base64\"/>"))
        assertRefused(image, svg("<image href=\"data:image/png,rawbytes\"/>"))
    }

    @Test
    fun `url references in attributes`() {
        assertRefused(outside("fill"), svg("<rect fill=\"url(https://evil.example/p.svg#g)\"/>"))
        assertRefused(outside("filter"), svg("<rect filter=\"URL( 'http://x' )\"/>"))
        assertRefused(outside("mask"), svg("<rect mask=\"url(other.svg#m)\"/>"))
        assertRefused(outside("data-x"), svg("<rect data-x=\"url(//evil.example)\"/>"))
        assertRefused("the attribute fill contains a backslash", svg("<rect fill=\"\\75rl(http://x)\"/>"))
        assertRefused(css, svg("<rect style=\"fill:url(https://evil.example/x)\"/>"))
        assertRefused(css, svg("<rect style=\"background:URL( 'http://x' )\"/>"))
    }

    @Test
    fun `styles`() {
        assertRefused(css, svg("<style>@import url(https://evil.example/x.css);</style>"))
        assertRefused(css, svg("<style>@IMPORT 'x.css';</style>"))
        assertRefused(css, svg("<style>@\\69mport 'x.css';</style>"))
        assertRefused(css, svg("<style><![CDATA[@import 'x';]]></style>"))
        assertRefused(css, svg("<style>@im<!-- split -->port 'x';</style>"))
        assertRefused(css, svg("<style>.a{fill:url(https://evil.example/p.svg#g)}</style>"))
        assertRefused(css, svg("<style>@font-face{font-family:x;src:local(x)}</style>"))
        assertRefused(css, svg("<style>.a{background:image-set('x.png' 1x)}</style>"))
        assertRefused(css, svg("<style>.a{width:expression(alert(1))}</style>"))
        assertRefused(css, svg("<rect style=\"fill:red;behavior:javascript:alert(1)\"/>"))
    }

    @Test
    fun `document structure`() {
        assertRefused("it contains a DOCTYPE", "<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\">" + svg(""))
        assertRefused("it contains a DOCTYPE", "<!DOCTYPE svg [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;\">]>" + svg("<text>&b;</text>"))
        assertRefused("it contains a DOCTYPE", "<!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>" + svg("<text>&x;</text>"))
        assertRefused("it contains a processing instruction", "<?xml-stylesheet href=\"https://evil.example/x.css\"?>" + svg(""))
        assertRefused("it contains a processing instruction", svg("<?foo bar?>"))
        assertRefused("it contains a processing instruction", svg("") + "<?foo bar?>")
        assertRefused("it must be encoded as UTF-8", "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + svg(""))
        assertRefused("it is not well-formed XML", svg("<g>"))
        assertRefused("it is not well-formed XML", svg("") + "<svg/>")
        assertRefused("it is not well-formed XML", svg("<text>&undeclared;</text>"))
        assertRefused("the root element must be <svg xmlns=\"http://www.w3.org/2000/svg\">", "<svg viewBox=\"0 0 1 1\"/>")
        assertRefused("the root element must be <svg xmlns=\"http://www.w3.org/2000/svg\">", "<svg xmlns=\"urn:x\" viewBox=\"0 0 1 1\"/>")
        val size = "it needs a viewBox, or a width and height in pixels"
        assertRefused(size, "<svg $ns/>")
        assertRefused(size, "<svg $ns width=\"10\"/>")
        assertRefused(size, "<svg $ns width=\"100%\" height=\"100%\"/>")
        assertRefused(size, "<svg $ns width=\"2em\" height=\"2em\"/>")
        assertRefused(size, "<svg $ns width=\"0\" height=\"10\"/>")
        assertRefused(size, "<svg $ns viewBox=\"0 0 0 10\"/>")
        assertRefused(size, "<svg $ns viewBox=\"0 0 10\"/>")
        assertRefused(size, "<svg $ns viewBox=\"a b c d\"/>")
    }
}
