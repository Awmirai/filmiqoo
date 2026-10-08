package com.filmiqoo.app
import org.junit.Assert.*
import org.junit.Test
class CinemaHubRulesTest {
 @Test fun durationUsesRecordedTimeAndDoesNotInventHours(){
  assertEquals("۰ دقیقه",cinemaHubWatchDuration(-9));assertEquals("کمتر از یک دقیقه",cinemaHubWatchDuration(59_999));assertEquals("۱ دقیقه",cinemaHubWatchDuration(60_000));assertEquals("۱ ساعت",cinemaHubWatchDuration(3_600_000));assertEquals("۲ ساعت و ۵ دقیقه",cinemaHubWatchDuration(7_500_000));assertEquals("۴۸ ساعت",cinemaHubWatchDuration(172_800_000))
 }
 @Test fun tasteRejectsImpossibleFractionsWithoutInventingOrRenormalizing(){
  val observed=CinemaTasteShare("KR","کرهٔ جنوبی",.25f)
  val invalid=listOf(CinemaTasteShare("a","",.2f),CinemaTasteShare("b","ناشناخته",Float.NaN),CinemaTasteShare("c","خارج از بازه",1.2f),CinemaTasteShare("d","صفر",0f))
  assertEquals(listOf(observed),cinemaHubTasteShares(invalid+observed+observed))
 }
}
