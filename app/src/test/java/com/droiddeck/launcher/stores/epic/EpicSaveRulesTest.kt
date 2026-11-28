package com.droiddeck.launcher.stores.epic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpicSaveRulesTest {
    @Test fun aDirectoryPatternMatchesEverythingUnderTheFolderOnly() {
        val r = EpicSaveRules("Saves/", "")
        assertTrue(r.allows("Saves/slot1.sav"))
        assertTrue(r.allows("Saves/nested/deep/slot2.sav"))
        assertFalse(r.allows("MySaves/slot1.sav"))
        assertFalse(r.allows("Saves"))
        assertFalse(r.allows("Saves.txt"))
    }

    @Test fun aPlainPatternMatchesNamesThatEndWithIt() {
        val r = EpicSaveRules("slot.sav", "")
        assertTrue(r.allows("slot.sav"))
        assertTrue(r.allows("Dir/slot.sav"))
        assertTrue(r.allows("Dir/myslot.sav"))
        assertFalse(r.allows("Dir/slot2.sav"))
        assertFalse(r.allows("slot.sav.bak"))
    }

    @Test fun wildcardsSpanPathSeparators() {
        val r = EpicSaveRules("*.json", "")
        assertTrue(r.allows("a.json"))
        assertTrue(r.allows("Deep/Dir/x.json"))
        assertFalse(r.allows("Deep/Dir/x.json.bak"))
        assertFalse(r.allows("Deep/Dir/x.sav"))
        val q = EpicSaveRules("slot?.sav", "")
        assertTrue(q.allows("slot1.sav"))
        assertTrue(q.allows("slot/.sav"))
        assertFalse(q.allows("slot12.sav"))
        val c = EpicSaveRules("slot[0-9].sav", "")
        assertTrue(c.allows("slot7.sav"))
        assertFalse(c.allows("slotA.sav"))
    }

    @Test fun aCharacterClassMatchesFnmatchNotRegexSemantics() {
        // fnmatch's negation is `[!...]`; a leading `^` is a class member, not a negation.
        val caret = EpicSaveRules("slot[^a].sav", "")
        assertTrue(caret.allows("slot^.sav"))
        assertTrue(caret.allows("slota.sav"))
        assertFalse(caret.allows("slotb.sav"))
        val negate = EpicSaveRules("slot[!a].sav", "")
        assertTrue(negate.allows("slotb.sav"))
        assertFalse(negate.allows("slota.sav"))
    }

    @Test fun everythingIsCaseInsensitive() {
        assertTrue(EpicSaveRules("*.JSON", "").allows("Dir/x.json"))
        assertTrue(EpicSaveRules("SAVES/", "").allows("saves/a.dat"))
        assertTrue(EpicSaveRules("Slot.SAV", "").allows("Dir/slot.sav"))
        assertTrue(EpicSaveRules("", "*.Tmp").allows("keep.sav"))
        assertFalse(EpicSaveRules("", "*.Tmp").allows("junk.TMP"))
    }

    @Test fun anEmptyIncludeAllowsEverything() {
        assertTrue(EpicSaveRules("", "").allows("anything.bin"))
        assertTrue(EpicSaveRules(EpicSaveRules.parse(""), EpicSaveRules.parse("")).allows("Deep/anything.bin"))
        assertTrue(EpicSaveRules(emptyList(), emptyList()).allows("anything.bin"))
    }

    @Test fun anExcludeHitWinsOverTheInclude() {
        val r = EpicSaveRules("*.json", "cache/")
        assertFalse(r.allows("cache/state.json"))
        assertTrue(r.allows("saves/state.json"))
        assertFalse(r.allows("cache/whatever.txt"))
    }

    @Test fun excludeAloneFiltersWithoutAnInclude() {
        val r = EpicSaveRules("", "*.tmp")
        assertTrue(r.allows("a.sav"))
        assertTrue(r.allows("Deep/b.sav"))
        assertFalse(r.allows("a.tmp"))
    }

    @Test fun commaSeparatedPatternsAreSplitAndTrimmed() {
        assertEquals(listOf("*.json", "*.sav"), EpicSaveRules.parse(" *.json , *.sav ,,"))
        assertEquals(emptyList<String>(), EpicSaveRules.parse(""))
        assertEquals(emptyList<String>(), EpicSaveRules.parse(" , , "))
        val r = EpicSaveRules("*.json,*.sav", "")
        assertTrue(r.allows("x.sav"))
        assertTrue(r.allows("y.json"))
        assertFalse(r.allows("z.txt"))
    }
}
