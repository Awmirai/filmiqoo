package com.filmiqoo.app

import org.junit.Assert.*
import org.junit.Test

class WatchPartyTimingTest {
    private fun party(playing:Boolean=true,state:String="live",timed:Boolean=true)=WatchPartyInfo(
        "party","Together",state,"public",null,42_000,playing,2,"room",
        WatchPartyHost("host","host","Host","",false),WatchPartyMedia("title","Movie",null,null,"file",null),
        serverTimed=timed)
    @Test fun monotonicPositionAdvancesWhilePlayingAndHoldsWhenPausedOrClosed() {
        assertEquals(45_500,watchPartyTargetPosition(party(),1000,4500))
        assertEquals(42_000,watchPartyTargetPosition(party(playing=false),1000,4500))
        assertEquals(42_000,watchPartyTargetPosition(party(state="ended"),1000,4500))
        assertEquals(42_000,watchPartyTargetPosition(party(),4500,1000))
        assertEquals(42_000,watchPartyTargetPosition(party(timed=false),1000,4500))
    }
    @Test fun inviteEntryAcceptsSharedLinkAndUuidButRejectsOtherRoutesAndMalformedIds() {
        val id="12345678-1234-4234-8234-123456789abc"
        assertEquals(PartyInviteLink(id,"safe-code"),parsePartyInvite("دعوت به فیلم\nfilmiqoo://party/$id?invite=safe-code"))
        assertEquals(PartyInviteLink(id,null),parsePartyInvite(id.uppercase()))
        assertNull(parsePartyInvite("filmiqoo://play/$id"))
        assertNull(parsePartyInvite("https://example.com/party/$id"))
        assertNull(parsePartyInvite("filmiqoo://party/not-an-id?invite=code"))
        assertNull(parsePartyInvite("code-alone"))
    }
}
