package com.droiddeck.launcher

import android.graphics.Color
import android.util.Xml
import androidx.core.graphics.createBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconPackTest {
    private fun filter(xml: String) = IconPack.parse(Xml.newPullParser().also { it.setInput(StringReader(xml)) })

    private fun parse(xml: String) = filter(xml).icons

    private fun plate(xml: String) = filter(xml).plate

    private fun solid(color: Int) = createBitmap(10, 10).also { it.eraseColor(color) }

    @Test fun plateTagsAreReadFromTheFilter() {
        val xml = """<resources>
            <iconback img1="back" img2="other" />
            <iconmask img1="mask" />
            <iconupon img1="upon" />
            <scale factor="0.75" />
            <item component="ComponentInfo{com.example/com.example.Main}" drawable="main" />
          </resources>"""
        assertEquals(IconPack.Plate("back", "mask", "upon", 0.75f), plate(xml))
    }

    @Test fun aPackWithoutPlateImagesHasNoPlateEvenWithAScale() {
        assertNull(plate("""<resources><iconback img1="" /><scale factor="1.0" /></resources>"""))
    }

    @Test fun anInvalidScaleLeavesTheIconFullSize() {
        assertEquals(1f, plate("""<resources><iconback img1="back" /><scale factor="big" /></resources>""")?.scale)
    }

    @Test fun theMaskCutsTheIconWhereItIsOpaqueAndTheBackShowsThrough() {
        val mask = createBitmap(10, 10).also { it.setPixel(0, 0, Color.BLACK) }
        val out = IconPack.compose(solid(Color.RED), solid(Color.BLUE), mask, null, 1f)
        assertEquals(Color.BLUE, out.getPixel(0, 0))
        assertEquals(Color.RED, out.getPixel(5, 5))
    }

    @Test fun theScaleShrinksTheIconOntoTheBack() {
        val out = IconPack.compose(solid(Color.RED), solid(Color.BLUE), null, null, 0.5f)
        assertEquals(Color.BLUE, out.getPixel(1, 1))
        assertEquals(Color.RED, out.getPixel(5, 5))
    }

    @Test fun anAppColorRepaintsTheBackInsideItsShape() {
        val back = createBitmap(10, 10).also { it.setPixel(5, 5, Color.WHITE) }
        val out = IconPack.compose(createBitmap(10, 10), back, null, null, 1f, Color.GREEN)
        assertEquals(Color.GREEN, out.getPixel(5, 5))
        assertEquals(Color.TRANSPARENT, out.getPixel(0, 0))
    }

    @Test fun theUponImageDrawsOverEverything() {
        val out = IconPack.compose(solid(Color.RED), solid(Color.BLUE), null, solid(Color.GREEN), 1f)
        assertEquals(Color.GREEN, out.getPixel(5, 5))
    }

    @Test fun drawableXmlNamesAreDistinctAndInOrder() {
        val xml = """<resources><category title="All" /><item drawable="b" /><item drawable="a" /><item drawable="b" /><item /></resources>"""
        assertEquals(listOf("b", "a"), IconPack.parseNames(Xml.newPullParser().also { it.setInput(StringReader(xml)) }))
    }

    @Test fun aClassNameStartingWithADotIsExpandedWithThePackage() {
        assertEquals("com.example.app/com.example.app.Main", IconPack.componentKey("ComponentInfo{com.example.app/.Main}"))
    }

    @Test fun entriesWithoutAUsableComponentOrDrawableAreSkipped() {
        val icons = parse(
            """<resources>
                 <item component="ComponentInfo{com.example/}" drawable="b" />
                 <item component="ComponentInfo{}" drawable="c" />
                 <item component="ComponentInfo{com.example/com.example.Main}" drawable="" />
                 <item drawable="d" />
                 <item component="ComponentInfo{com.example/com.example.Main}" />
               </resources>""",
        )
        assertTrue(icons.toString(), icons.isEmpty())
    }

    @Test fun theFirstEntryForAComponentWins() {
        val icons = parse(
            """<resources>
                 <item component="ComponentInfo{com.example/com.example.Main}" drawable="first" />
                 <item component="ComponentInfo{com.example/com.example.Main}" drawable="second" />
               </resources>""",
        )
        assertEquals("first", icons["com.example/com.example.Main"])
    }

    @Test fun aCalendarTriesTodaysIconBeforeItsStaticOne() {
        val filter = filter(
            """<resources>
                 <calendar component="ComponentInfo{com.example/com.example.Cal}" prefix="calendar_" />
                 <item component="ComponentInfo{com.example/com.example.Cal}" drawable="cal" />
                 <item component="ComponentInfo{com.example/com.example.Main}" drawable="main" />
               </resources>""",
        )
        assertEquals(listOf("calendar_7", "cal"), filter.names("com.example/com.example.Cal", 7))
        assertEquals(listOf("main"), filter.names("com.example/com.example.Main", 7))
    }

    @Test fun tagsThatAreNotItemsAreIgnored() {
        val icons = parse(
            """<resources>
                 <iconback img1="back" />
                 <scale factor="0.8" />
                 <calendar component="ComponentInfo{com.example/com.example.Cal}" prefix="calendar_" />
                 <item component="ComponentInfo{com.example/com.example.Main}" drawable="main" />
               </resources>""",
        )
        assertEquals(mapOf("com.example/com.example.Main" to "main"), icons)
    }
}
