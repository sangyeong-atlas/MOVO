package com.hopon.car.voice

import android.test.InstrumentationTestRunner
import com.hopon.car.navigation.*
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hopon.car.MainActivity
import com.hopon.car.data.JourneyRepository
import org.json.JSONObject
import java.io.File

/** Browser coordinates only a newly created :8793 Journey. Shared user app/preferences are never used. */
@Suppress("DEPRECATION")
class MobileStopUiIntegrationTest:InstrumentationTestCase(){
    fun testMobilePlusArrivesAndDriverButtonsControlCourse(){
        val args=(instrumentation as InstrumentationTestRunner).arguments
        require(args.getString("movoNativeRouteApproval")=="isolated-native-route-and-restore") { "Manual native route test requires explicit argument" }
        var native=PleosNavigationAdapter(instrumentation.targetContext,allowRouteChanges=true)
        native.start()
        await{native.snapshot.route.receivedAtElapsedMs!=null&&native.snapshot.destination.receivedAtElapsedMs!=null&&native.snapshot.waypoints.receivedAtElapsedMs!=null}
        fun emptyNative()=native.snapshot.route.value?.mode=="SAFE_GUIDING" && native.snapshot.destination.value?.name?.isBlank()==true && native.snapshot.waypoints.value?.isEmpty()==true
        assertTrue("Preserve existing native route",emptyNative())
        native.close();instrumentation.waitForIdleSync()
        val ctx=instrumentation.targetContext;assertEquals("com.hopon.car.voiceprobe",ctx.packageName)
        val fixture=JSONObject(File(ctx.filesDir,"mobile-stop-fixture.json").readText())
        assertEquals("http://127.0.0.1:8793",fixture.getString("base"))
        val suffix=System.nanoTime().toString()
        val isolated=object:ContextWrapper(ctx){override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=super.getSharedPreferences("mobile-stop-$suffix-$name",mode)}
        val activity=instrumentation.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val field=MainActivity::class.java.getDeclaredField("repository").apply{isAccessible=true}
        val update=MainActivity::class.java.getDeclaredMethod("onDataChanged").apply{isAccessible=true}
        lateinit var repo:JourneyRepository
        fun textViews(v:View):List<TextView> = when(v){is TextView->listOf(v);is ViewGroup->(0 until v.childCount).flatMap{textViews(v.getChildAt(it))};else->emptyList()}
        fun capture(name:String){val v=activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File(ctx.filesDir,name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        try{
            instrumentation.runOnMainSync{
                (field.get(activity) as JourneyRepository).destroy()
                repo=JourneyRepository(isolated){if(!activity.isDestroyed)update.invoke(activity)};field.set(activity,repo)
                MainActivity::class.java.getDeclaredField("screen").apply{isAccessible=true}.set(activity,"home")
                MainActivity::class.java.getDeclaredField("supportTab").apply{isAccessible=true}.set(activity,"requests")
                repo.start();repo.pair(fixture.getString("base"),fixture.getString("code"))
            }
            await{repo.online&&!repo.busy&&repo.tripId==fixture.getString("tripId")}
            await{(repo.snapshot?.optJSONArray("requests")?.length()?:0)==1}
            assertEquals(0,repo.snapshot!!.getJSONArray("course").length())
            await{File(ctx.filesDir,"mobile-stop-approve-${fixture.getString("tripId")}").exists()}
            instrumentation.runOnMainSync{
                val labels=textViews(activity.window.decorView)
                assertTrue(labels.any{it.text.contains("성수 온실 카페")})
                capture("mobile-stop-vehicle-pending.png")
                labels.single{it.text.toString()=="승인"}.performClick()
            }
            await{!repo.busy&&repo.snapshot!!.getJSONArray("course").length()==1}
            instrumentation.runOnMainSync{
                MainActivity::class.java.getDeclaredField("supportTab").apply{isAccessible=true}.set(activity,"overview")
                MainActivity::class.java.getDeclaredMethod("render").apply{isAccessible=true}.invoke(activity)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync{
                assertTrue(textViews(activity.window.decorView).any{it.text.contains("성수 온실 카페")})
                capture("mobile-stop-vehicle-course.png")
            }
            await{repo.snapshot!!.getJSONArray("requests").length()==2}
            await{File(ctx.filesDir,"mobile-stop-reject-${fixture.getString("tripId")}").exists()}
            instrumentation.runOnMainSync{
                MainActivity::class.java.getDeclaredField("supportTab").apply{isAccessible=true}.set(activity,"requests")
                MainActivity::class.java.getDeclaredMethod("render").apply{isAccessible=true}.invoke(activity)
                textViews(activity.window.decorView).single{it.text.toString()=="거절"}.performClick()
            }
            await{!repo.busy&&repo.snapshot!!.getJSONArray("requests").getJSONObject(1).optString("status")=="rejected"}
            assertEquals(1,repo.snapshot!!.getJSONArray("course").length())
        }finally{
            instrumentation.runOnMainSync{activity.finish()}
            try {
                native=PleosNavigationAdapter(instrumentation.targetContext,allowRouteChanges=true);native.start()
                await{native.snapshot.connection==NavigationConnection.CONNECTED}
                val before=android.os.SystemClock.elapsedRealtime();native.refresh()
                await{(native.snapshot.destination.receivedAtElapsedMs?:0)>=before}
                if(!emptyNative()) {
                    val expected=NavigationTarget("서울숲",37.5445,127.0374,"","서울숲")
                    assertTrue("Do not cancel unrelated route",expected.matches(native.snapshot.destination.value))
                    native.cancelDestination(expected)
                    await{emptyNative()}
                }
            }finally{native.close()}
        }
    }
    private fun await(condition:()->Boolean){val until=System.nanoTime()+90_000_000_000L;while(!condition()&&System.nanoTime()<until)Thread.sleep(100);assertTrue("Timed out waiting for isolated mobile/vehicle interaction",condition())}
}
