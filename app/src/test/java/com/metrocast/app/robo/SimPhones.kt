package com.metrocast.app.robo

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.data.CrashLog
import com.metrocast.app.ui.MainActivity
import com.metrocast.app.ui.Net
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

/**
 * Simulated phones (only with -Psim=<seconds>): the real app, for minutes at a time — random taps,
 * whole rides (start a trip, trains move stop by stop, board, transfer, arrive), WMATA trouble
 * (position feed down, errors, garbage, offline), the Trains/Airports screens and settings.
 * Fails on any crash; writes everything odd it sees to a findings file.
 */
abstract class SimPhone(private val name: String, private val seed: Int) {
    private val fake = object : TestRule {
        override fun apply(base: Statement, d: Description) = object : Statement() {
            override fun evaluate() {
                ApplicationProvider.getApplicationContext<Context>()
                    .getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().putBoolean("onboarded", true).commit()
                FakeWmata.install(); resetViewModelFactory()
                base.evaluate()
            }
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fake).around(compose)
    @After fun tearDown() { FakeWmata.uninstall(); Net.testOnline = null }

    private val rnd = Random(seed)
    private val log = ArrayList<String>()
    private val findings = LinkedHashMap<String, String>() // what → where first seen
    private val counts = HashMap<String, Int>()

    private fun texts(): List<String> = compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        .flatMap { n -> (n.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()) + (n.config.getOrNull(SemanticsProperties.EditableText)?.let { listOf(it.text) } ?: emptyList()) }
    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun idle() { compose.waitForIdle(); org.robolectric.shadows.ShadowLooper.idleMainLooper() }
    private fun note(what: String) { counts.merge(what, 1, Int::plus); findings.putIfAbsent(what, log.takeLast(6).joinToString(" → ")) }

    private fun tap(n: androidx.compose.ui.semantics.SemanticsNode, label: String) {
        log += label
        try { compose.runOnUiThread { n.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() } }
        catch (e: Throwable) { throw AssertionError("[$name] CRASH tapping \"$label\" after: ${log.takeLast(15)}", e) }
        idle(); check()
    }
    private fun tapText(t: String): Boolean {
        val n = compose.onAllNodes(hasText(t, substring = true) and hasClickAction()).fetchSemanticsNodes().firstOrNull() ?: return false
        tap(n, t); return true
    }
    private fun back() { log += "back"; compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; idle(); check() }
    private fun home() { repeat(10) { if (!sees("Updated") || compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Menu")).fetchSemanticsNodes().isEmpty()) back() } }
    private fun menu(item: String): Boolean {
        home()
        val m = compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Menu")).fetchSemanticsNodes().firstOrNull() ?: return false
        tap(m, "☰"); return tapText(item)
    }
    private fun label(n: androidx.compose.ui.semantics.SemanticsNode) =
        n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")?.take(40) ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") ?: "?"

    /** Anything on screen that a person shouldn't see. */
    private fun check() {
        val all = texts()
        for (t in all) {
            if (Regex("\\bnull\\b").containsMatchIn(t)) note("shows \"null\": \"${t.take(80)}\"")
            if ("NaN" in t || "Infinity" in t) note("shows NaN/Infinity: \"${t.take(80)}\"")
            if (Regex("(^|[^\\w])-\\d+ ?(min|m)\\b").containsMatchIn(t)) note("negative minutes: \"${t.take(80)}\"")
            if (Regex("Exception|java\\.|kotlin\\.|at com\\.metrocast").containsMatchIn(t)) note("shows an error trace: \"${t.take(80)}\"")
            if (Regex("\\{\\s*\"|\\[\\s*\\{").containsMatchIn(t)) note("shows raw JSON: \"${t.take(80)}\"")
            if (t.length > 600 && t.any { it != '•' }) note("very long text (${t.length} chars): \"${t.take(60)}…\"")
        }
        val crashes = CrashLog.entries(ApplicationProvider.getApplicationContext())
        if (crashes.isNotEmpty()) throw AssertionError("[$name] crash noted: ${crashes.first().take(400)} after ${log.takeLast(15)}")
    }

    // ---- scenarios
    private fun randomTaps(k: Int) = repeat(k) {
        val nodes = compose.onAllNodes(hasClickAction() and SemanticsMatcher("enabled") { !it.config.contains(SemanticsProperties.Disabled) }).fetchSemanticsNodes()
        if (rnd.nextInt(9) == 0) back() else if (nodes.isNotEmpty()) nodes[rnd.nextInt(nodes.size)].let { tap(it, label(it)) }
    }

    private val stations = listOf("Navy Yard", "Metro Center", "Union Station", "Rosslyn", "Pentagon", "Gallery Pl", "L'Enfant", "Shady Grove", "Ashburn", "Largo", "Huntington", "Vienna", "Dupont", "Anacostia", "Greenbelt", "Tysons", "Crystal City", "Fort Totten")

    private fun ride() {
        if (!menu("Trip planner")) return
        idle()
        if (!tapText("Choose where you're going") && !tapText("TO")) return
        compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().firstOrNull() ?: return
        val dest = stations[rnd.nextInt(stations.size)]
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(dest.take(5)); idle()
        val hit = compose.onAllNodes(hasText(dest.take(5), substring = true) and hasClickAction()).fetchSemanticsNodes()
        if (hit.isEmpty()) { note("station search found nothing for \"${dest.take(5)}\""); back(); return }
        tap(hit.first(), "pick $dest")
        if (!sees("Start live trip")) { log += "no route to $dest"; return }
        tapText("Start live trip")
        if (sees("No, I'll tap the button")) tapText("No, I'll tap the button")
        // The ride: trains move (recorded snapshots 1→5), board, maybe transfer, then end.
        for (snap in 1..5) {
            FakeWmata.snapshot = snap; compose.activity.runOnUiThread { }
            Thread.sleep(200); idle(); check()
            if (sees("I'm on the train") || sees("BOARD")) { tapText("I'm on the train") || tapText("BOARD") }
            if (rnd.nextInt(3) == 0 && sees("I'm off")) tapText("I'm off")
            if (rnd.nextInt(4) == 0) randomTaps(2)
        }
        FakeWmata.snapshot = 1
        if (!tapText("End trip")) home()
    }

    private fun trouble() {
        val kind = rnd.nextInt(7)
        when (kind) {
            5 -> FakeWmata.slow = mapOf("open-meteo" to 4_000L)                                          // weather very slow
            6 -> FakeWmata.failing = mapOf("GetPrediction" to java.net.UnknownHostException("api.wmata.com")) // phone just woke up
            0 -> FakeWmata.bodies = mapOf("TrainPositions?" to """{"TrainPositions":[]}""")          // WMATA's feed down
            1 -> FakeWmata.failing = mapOf("GetPrediction" to com.metrocast.app.data.HttpException(500))
            2 -> FakeWmata.bodies = mapOf("GetPrediction" to "{", "Incidents" to "<html>oops</html>")   // garbage
            3 -> Net.testOnline = false                                                                 // offline
            else -> FakeWmata.failing = mapOf("api.wmata.com" to java.io.IOException("timeout"))
        }
        log += "trouble"
        home(); compose.onAllNodes(hasClickAction()).fetchSemanticsNodes() // refreshes happen on their own
        repeat(3) { if (rnd.nextBoolean()) randomTaps(3) else menu(listOf("Live trains", "Train maps", "Trip planner", "Train issues")[rnd.nextInt(4)]) }
        FakeWmata.bodies = emptyMap(); FakeWmata.failing = emptyMap(); Net.testOnline = true
        // Trouble's over: how long until fresh live trains are on screen? (The test clock moves with real time here.)
        val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[com.metrocast.app.ui.MainViewModel::class.java]
        if (vm.state.value.snapshot.hasApiKey && vm.state.value.snapshot.trips.isNotEmpty()) {
            val cleared = System.currentTimeMillis()
            compose.runOnUiThread { vm.refresh() } // what the next refresh tick (or coming back online) does
            fun fresh() = vm.state.value.trainError == null && vm.state.value.snapshot.trainsUpdatedAt >= cleared
            while (!fresh() && System.currentTimeMillis() - cleared < 8_000) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS); Thread.sleep(50)
            }
            val took = System.currentTimeMillis() - cleared
            if (fresh()) recoveries += took.also { slowest.merge(kind, it) { a, b -> maxOf(a, b) } } else {
                note("live trains not back 8 s after the trouble (${listOf("feed down", "error 500", "garbage", "offline", "timeouts", "slow weather", "phone waking")[kind]})")
                val st = vm.state.value
                traceFile?.appendText("==== [$name] trains not back: ${vm.debugState()}; trainError=${st.trainError}; trains updated ${(System.currentTimeMillis() - st.snapshot.trainsUpdatedAt) / 1000}s ago; trips=${st.snapshot.trips.size}; online=${Net.testOnline}; requests in the wait: ${FakeWmata.seen().takeLast(6)}\n" +
                    "    main thread: " + Thread.getAllStackTraces().entries.filter { it.key.name.contains("DefaultDispatcher") && it.value.any { f -> f.className.startsWith("com.metrocast") } }.joinToString("\n    ") { (t, st) -> t.name + ": " + st.filter { f -> f.className.startsWith("com.metrocast") }.take(6).joinToString(" < ") } + "\n\n")
            }
        }
        FakeWmata.slow = emptyMap()
    }
    private val recoveries = ArrayList<Long>()
    private val slowest = sortedMapOf<Int, Long>()
    private val kinds = listOf("feed down", "error 500", "garbage", "offline", "timeouts", "slow weather", "phone waking")

    private fun trains() {
        if (!menu("Amtrak, MARC & VRE")) return
        repeat(4) {
            when (rnd.nextInt(5)) {
                0 -> tapText(listOf("Amtrak", "MARC", "VRE", "All")[rnd.nextInt(4)])
                1 -> tapText(if (rnd.nextBoolean()) "🚉  Stations" else "🚆  Trains")
                2 -> compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().firstOrNull()?.let {
                    compose.onAllNodes(hasSetTextAction())[0].performTextInput(listOf("New York", "171", "Fred", "x", "Bal")[rnd.nextInt(5)]); idle(); check()
                }
                else -> randomTaps(2)
            }
        }
    }

    private fun airports() {
        if (!menu("Airports")) return
        tapText(if (rnd.nextBoolean()) "Dulles (IAD)" else "Reagan National (DCA)")
        compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().lastOrNull()?.let {
            val box = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size - 1
            compose.onAllNodes(hasSetTextAction())[box].performTextInput(listOf("1:30", "930", "12", "7", "23:59", "abc", "0")[rnd.nextInt(7)]); idle(); check()
        }
        if (rnd.nextBoolean()) tapText(if (rnd.nextBoolean()) "AM" else "PM")
        randomTaps(3)
    }

    private fun settings() {
        if (!menu("Settings")) return
        randomTaps(6)
    }

    // ---- trying to break it
    private val attacks = listOf("rotate / recreate", "widget & notification taps", "hostile typing", "double taps", "weird WMATA data",
        "huge WMATA data", "corrupted settings", "other time zone", "other language", "everything fails at once", "settings flip-flop",
        // extreme
        "app killed and reopened", "broken files on disk", "clock-mangled caches", "giant text, tiny screen", "tap storm",
        "refresh spam", "live trip spam", "molasses network", "memory pressure", "combo")
    private val attacksDone = HashMap<String, Int>()
    private val attacksMenu = listOf("Trip planner", "Live trains", "Train maps", "Saved routes", "Trip history", "Restrooms", "Track work calendar",
        "Bikes", "Metro Police", "Special trains", "Scenic rides", "Train issues", "Airports", "Amtrak, MARC & VRE", "Settings")
    private val hostile = listOf("", " ", "a".repeat(3000), "😀🚇🚆✈️👨‍👩‍👧", "'; DROP TABLE trips; --", "<b>hi</b>", "\n\n\t", "مترو", "１２：３０",
        "-1", "99:99", "0:00", "12:60", "25", "1e9", "nan", "Null ", "%s%n%d", "inf", "\u0000", "‮reversed", "Metro Center".repeat(50))
    private fun recreate() { log += "recreate"; compose.activityRule.scenario.recreate(); idle() }

    private fun mutate(json: String): String {
        val weird = listOf("\"BRD\"", "\"ARR\"", "\"---\"", "\"\"", "null", "\"-5\"", "\"99999\"", "\"DLY\"", "\"1.5\"", "\"😀\"")
        return json
            .replace(Regex("\"Min\":\"[^\"]*\"")) { if (rnd.nextInt(3) == 0) "\"Min\":" + weird[rnd.nextInt(weird.size)] else it.value }
            .replace(Regex("\"Line\":\"[A-Z]{2}\"")) { if (rnd.nextInt(6) == 0) "\"Line\":" + listOf("\"No\"", "\"--\"", "null", "\"ZZ\"", "\"\"")[rnd.nextInt(5)] else it.value }
            .replace(Regex("\"CircuitId\":\\d+")) { if (rnd.nextInt(5) == 0) "\"CircuitId\":" + listOf("-1", "0", "999999", "2147483647")[rnd.nextInt(4)] else it.value }
            .replace(Regex("\"DirectionNum\":\\d")) { if (rnd.nextInt(8) == 0) "\"DirectionNum\":" + listOf("0", "3", "-1")[rnd.nextInt(3)] else it.value }
            .replace(Regex("\"Car\":\"[^\"]*\"")) { if (rnd.nextInt(6) == 0) "\"Car\":" + listOf("null", "\"-\"", "\"88\"")[rnd.nextInt(3)] else it.value }
    }
    private fun res(n: String) = File("src/test/resources/live2/$n").readText()
    // Built once (18 MB of arrivals): building it again for every attack ran the test itself out of memory.
    private val floodOfArrivals by lazy { res("pred_1.json").let { p -> val body = p.substringAfter("[").substringBeforeLast("]"); "{\"Trains\":[" + List(200) { body }.joinToString(",") + "]}" } }

    private fun chaos() {
        val attack = attacks[rnd.nextInt(attacks.size)]
        attacksDone.merge(attack, 1, Int::plus)
        log += "ATTACK $attack"
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val tz = java.util.TimeZone.getDefault(); val locale = java.util.Locale.getDefault()
        try {
            when (attack) {
                "rotate / recreate" -> {
                    if (rnd.nextBoolean()) ride() else randomTaps(4)
                    org.robolectric.RuntimeEnvironment.setQualifiers(if (rnd.nextBoolean()) "+land" else "+port")
                    recreate(); check(); randomTaps(4); recreate(); check()
                }
                "widget & notification taps" -> repeat(6) {
                    val i = android.content.Intent(ctx, MainActivity::class.java)
                    listOf("update", "live", "settings", "", "x".repeat(500), null)[rnd.nextInt(6)]?.let { i.putExtra(com.metrocast.app.notify.Notifier.OPEN, it) }
                    log += "intent ${i.getStringExtra(com.metrocast.app.notify.Notifier.OPEN)?.take(10)}"
                    compose.runOnUiThread { MainActivity::class.java.getDeclaredMethod("onNewIntent", android.content.Intent::class.java).apply { isAccessible = true }.invoke(compose.activity, i) }; idle(); check()
                    if (rnd.nextBoolean()) randomTaps(2)
                    // A refresh is often running when a widget opens the app.
                    if (rnd.nextBoolean()) compose.runOnUiThread { androidx.lifecycle.ViewModelProvider(compose.activity)[com.metrocast.app.ui.MainViewModel::class.java].refresh() }
                }
                "hostile typing" -> {
                    if (!menu(listOf("Trip planner", "Airports", "Amtrak, MARC & VRE", "Settings", "Restrooms")[rnd.nextInt(5)])) return
                    repeat(3) { randomTaps(1)
                        val boxes = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size
                        if (boxes > 0) {
                            val k = rnd.nextInt(boxes); val text = hostile[rnd.nextInt(hostile.size)]
                            log += "type \"${text.take(12)}\""
                            compose.onAllNodes(hasSetTextAction())[k].performTextInput(text); idle(); check()
                            randomTaps(2)
                        }
                    }
                }
                "double taps" -> repeat(5) {
                    val nodes = compose.onAllNodes(hasClickAction() and SemanticsMatcher("enabled") { !it.config.contains(SemanticsProperties.Disabled) }).fetchSemanticsNodes()
                    if (nodes.isEmpty()) return@repeat
                    val n = nodes[rnd.nextInt(nodes.size)]
                    log += "double ${label(n)}"
                    // Two taps in the same frame (an impatient thumb), sometimes plus Back.
                    compose.runOnUiThread {
                        n.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
                        n.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
                        if (rnd.nextInt(3) == 0) compose.activity.onBackPressedDispatcher.onBackPressed()
                    }
                    idle(); check()
                }
                "weird WMATA data" -> {
                    val snap = 1 + rnd.nextInt(5)
                    FakeWmata.bodies = mapOf("GetPrediction" to mutate(res("pred_$snap.json")), "TrainPositions?" to mutate(res("pos_$snap.json")),
                        "Incidents" to listOf("""{"Incidents":[{"IncidentID":"x","Description":"${"Delays. ".repeat(80)}","LinesAffected":"RD;ZZ;;","IncidentType":"Delay","DateUpdated":"not a date"}]}""",
                            """{"Incidents":null}""", """{"Incidents":[{}]}""", """[]""")[rnd.nextInt(4)])
                    home(); compose.runOnUiThread { androidx.lifecycle.ViewModelProvider(compose.activity)[com.metrocast.app.ui.MainViewModel::class.java].refresh() }
                    Thread.sleep(300); idle(); check()
                    repeat(3) { if (rnd.nextBoolean()) randomTaps(3) else menu(listOf("Live trains", "Train maps", "Train issues", "Trip planner")[rnd.nextInt(4)]) }
                    if (rnd.nextBoolean()) ride()
                }
                "huge WMATA data" -> {
                    val one = """{"TrainId":"%d","TrainNumber":"%d","CarCount":8,"DirectionNum":%d,"CircuitId":%d,"DestinationStationCode":"A15","LineCode":"%s","SecondsAtLocation":5,"ServiceType":"Normal"}"""
                    val lines = listOf("RD", "OR", "SV", "BL", "YL", "GR")
                    val pos = (1..3000).joinToString(",", "{\"TrainPositions\":[", "]}") { one.format(it, it, 1 + it % 2, 1000 + rnd.nextInt(3000), lines[it % 6]) }
                    FakeWmata.bodies = mapOf("TrainPositions?" to pos, "GetPrediction" to floodOfArrivals)
                    home(); compose.runOnUiThread { androidx.lifecycle.ViewModelProvider(compose.activity)[com.metrocast.app.ui.MainViewModel::class.java].refresh() }
                    Thread.sleep(800); idle(); check()
                    repeat(3) { menu(listOf("Live trains", "Train maps", "Trip planner")[rnd.nextInt(3)]); randomTaps(3) }
                }
                "corrupted settings" -> {
                    val sp = ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE)
                    val keys = listOf("trips", "saved_routes", "history", "plan_from", "plan_to", "live_from", "live_to", "live_train", "lat", "lon", "loc_name", "theme_mode", "seen_version", "update_found", "widget_1")
                    val e = sp.edit()
                    repeat(4) {
                        val k = keys[rnd.nextInt(keys.size)]; val v = hostile[rnd.nextInt(hostile.size)].take(200)
                        log += "corrupt $k"
                        if (sp.all[k] == null || sp.all[k] is String) e.putString(k, v)
                    }
                    e.commit()
                    recreate(); check()
                    repeat(3) { randomTaps(3) }
                    if (rnd.nextBoolean()) ride()
                }
                "other time zone" -> {
                    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(listOf("Asia/Kolkata", "Pacific/Kiritimati", "America/Los_Angeles", "UTC", "Australia/Lord_Howe")[rnd.nextInt(5)]))
                    log += "tz ${java.util.TimeZone.getDefault().id}"
                    recreate(); check()
                    repeat(2) { if (rnd.nextBoolean()) ride() else randomTaps(4) }
                    menu(listOf("Airports", "Amtrak, MARC & VRE", "Track work calendar", "Trip history")[rnd.nextInt(4)]); randomTaps(4)
                }
                "other language" -> {
                    java.util.Locale.setDefault(listOf(java.util.Locale.GERMANY, java.util.Locale("ar", "EG"), java.util.Locale.FRANCE, java.util.Locale("hi", "IN"), java.util.Locale("tr", "TR"))[rnd.nextInt(5)])
                    log += "locale ${java.util.Locale.getDefault()}"
                    recreate(); check()
                    repeat(3) { if (rnd.nextBoolean()) ride() else { menu(listOf("Airports", "Settings", "Trip planner", "Bikes")[rnd.nextInt(4)]); randomTaps(4) } }
                }
                "everything fails at once" -> {
                    FakeWmata.failing = mapOf("wmata" to java.io.IOException("reset"), "open-meteo" to com.metrocast.app.data.HttpException(503), "amtrak" to java.io.IOException("x"), "gbfs" to java.io.IOException("x"))
                    FakeWmata.slow = mapOf("api" to 300L)
                    repeat(4) { if (rnd.nextBoolean()) ride() else { menu(listOf("Live trains", "Train maps", "Bikes", "Amtrak, MARC & VRE", "Airports", "Train issues")[rnd.nextInt(6)]); randomTaps(4) } }
                    recreate(); check()
                }
                "app killed and reopened" -> {
                    // Android kills the app in the background; it comes back with only its saved screen state.
                    if (rnd.nextBoolean()) ride() else randomTaps(4)
                    log += "process death"
                    compose.runOnUiThread { compose.activity.viewModelStore.clear() }
                    com.metrocast.app.data.StationDirectory.load("{\"Stations\":[]}")
                    com.metrocast.app.data.Repository.lastPositionsDown = false
                    recreate(); check()
                    repeat(3) { randomTaps(3) }
                }
                "broken files on disk" -> {
                    // Cache and data files cut short, emptied, or replaced with junk (a full disk, a bad restore).
                    val files = ctx.filesDir.walkTopDown().filter { it.isFile && it.name != "crash-log.txt" }.toList()
                    files.shuffled(rnd).take(1 + rnd.nextInt(6)).forEach { f ->
                        log += "break ${f.name}"
                        when (rnd.nextInt(4)) {
                            0 -> f.writeText("")
                            1 -> f.writeText(f.readText().let { it.take(it.length / 2) })
                            2 -> f.writeBytes(ByteArray(2048) { rnd.nextInt(256).toByte() })
                            else -> f.delete()
                        }
                    }
                    if (rnd.nextBoolean()) { compose.runOnUiThread { compose.activity.viewModelStore.clear() }; com.metrocast.app.data.StationDirectory.load("{\"Stations\":[]}") }
                    recreate(); check()
                    repeat(3) { if (rnd.nextBoolean()) ride() else { menu(listOf("Live trains", "Train maps", "Restrooms", "Bikes", "Amtrak, MARC & VRE", "Track work calendar", "Trip planner")[rnd.nextInt(7)]); randomTaps(4) } }
                }
                "clock-mangled caches" -> {
                    // Saved times from the far future, the distant past, or nonsense (a phone whose clock was wrong).
                    val sp = ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE)
                    val e = sp.edit()
                    sp.all.keys.filter { it.startsWith("cache_ts_") || it.endsWith("_at") || it.endsWith("At") }.forEach { k ->
                        if (rnd.nextBoolean()) runCatching { e.putLong(k, listOf(Long.MAX_VALUE, Long.MIN_VALUE, -1L, 0L, System.currentTimeMillis() + 400L * 86_400_000, 1L)[rnd.nextInt(6)]) }
                    }
                    e.commit(); log += "mangle clocks"
                    recreate(); check()
                    repeat(3) { if (rnd.nextBoolean()) ride() else randomTaps(4) }
                }
                "giant text, tiny screen" -> {
                    org.robolectric.RuntimeEnvironment.setQualifiers(listOf("w240dp-h320dp", "w320dp-h480dp", "w1280dp-h800dp")[rnd.nextInt(3)])
                    org.robolectric.RuntimeEnvironment.setFontScale(listOf(2.0f, 1.8f, 0.85f)[rnd.nextInt(3)])
                    log += "screen ${ctx.resources.configuration.screenWidthDp}dp, text ×${ctx.resources.configuration.fontScale}"
                    recreate(); check()
                    repeat(4) { if (rnd.nextInt(3) == 0) ride() else { menu(attacksMenu[rnd.nextInt(attacksMenu.size)]); randomTaps(4) } }
                }
                "tap storm" -> repeat(8) {
                    // Ten taps in one frame, on whatever is there — buttons that vanish mid-storm included.
                    val nodes = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
                    if (nodes.isEmpty()) return@repeat
                    val picks = List(10) { nodes[rnd.nextInt(nodes.size)] }
                    log += "storm ${picks.take(3).map { label(it).take(12) }}"
                    compose.runOnUiThread { picks.forEach { n -> runCatching { n.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() }.onFailure { if (it !is IllegalStateException) throw it } } }
                    idle(); check()
                }
                "refresh spam" -> {
                    val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[com.metrocast.app.ui.MainViewModel::class.java]
                    log += "refresh ×60"
                    repeat(60) { i -> compose.runOnUiThread { vm.refresh(userInitiated = i % 3 == 0); if (i % 7 == 0) vm.reloadFromCache() } }
                    idle(); check(); Thread.sleep(500); idle(); check()
                    randomTaps(5)
                }
                "live trip spam" -> repeat(4) {
                    // Start a live trip, end it, start another, back out halfway: fast.
                    ride()
                    if (sees("Start live trip")) repeat(3) { tapText("Start live trip"); if (rnd.nextBoolean()) tapText("End trip"); if (rnd.nextBoolean()) back() }
                    if (rnd.nextBoolean()) tapText("End trip")
                }
                "molasses network" -> {
                    // Everything answers, but slowly: 1.5–4 seconds a request.
                    FakeWmata.slow = mapOf("wmata" to 1_500L + rnd.nextInt(2_500), "open-meteo" to 3_000L, "amtrak" to 4_000L, "gbfs" to 2_000L)
                    repeat(4) { if (rnd.nextBoolean()) ride() else { menu(attacksMenu[rnd.nextInt(attacksMenu.size)]); randomTaps(3) } }
                }
                "memory pressure" -> {
                    log += "trim memory"
                    compose.runOnUiThread {
                        listOf(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE).forEach { compose.activity.onTrimMemory(it); (ctx as android.app.Application).onTrimMemory(it) }
                        compose.activity.onLowMemory()
                    }
                    System.gc(); idle(); check()
                    repeat(3) { randomTaps(4) }
                }
                "combo" -> {
                    // Several attacks at once, without tidying up in between.
                    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Kiritimati"))
                    java.util.Locale.setDefault(java.util.Locale("ar", "EG"))
                    org.robolectric.RuntimeEnvironment.setFontScale(1.8f)
                    FakeWmata.bodies = mapOf("GetPrediction" to mutate(res("pred_${1 + rnd.nextInt(5)}.json")), "TrainPositions?" to mutate(res("pos_${1 + rnd.nextInt(5)}.json")))
                    FakeWmata.slow = mapOf("wmata" to 800L)
                    log += "combo"
                    recreate(); check()
                    repeat(5) { when (rnd.nextInt(4)) { 0 -> ride(); 1 -> { menu(attacksMenu[rnd.nextInt(attacksMenu.size)]); randomTaps(3) }; 2 -> recreate(); else -> randomTaps(4) } ; check() }
                }
                "settings flip-flop" -> {
                    if (!menu("Settings")) return
                    // Every switch on the screen, twice, fast.
                    repeat(2) {
                        compose.onAllNodes(SemanticsMatcher("toggle") { it.config.contains(SemanticsProperties.ToggleableState) }).fetchSemanticsNodes().forEach { n ->
                            log += "toggle ${label(n)}"
                            compose.runOnUiThread { n.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() }
                        }
                        idle(); check()
                    }
                    home(); randomTaps(5)
                }
            }
        } catch (t: Throwable) {
            val root = generateSequence(t) { it.cause }.last()
            val where = root.stackTrace.firstOrNull { it.className.startsWith("com.metrocast.app") && !it.className.contains(".robo.") }
            note("CRASH during \"$attack\": ${root.javaClass.simpleName}: ${root.message?.take(120)} at ${where ?: root.stackTrace.firstOrNull()}")
            traces("CRASH during \"$attack\" after ${log.takeLast(12)}", t)
            CrashLog.clear(ctx)
        } finally {
            java.util.TimeZone.setDefault(tz); java.util.Locale.setDefault(locale)
            FakeWmata.bodies = emptyMap(); FakeWmata.failing = emptyMap(); FakeWmata.slow = emptyMap(); FakeWmata.snapshot = 1; Net.testOnline = true
            // Recover: a clean screen, as if the person reopened the app.
            runCatching {
                if (attack == "rotate / recreate") org.robolectric.RuntimeEnvironment.setQualifiers("+port")
                if (attack == "giant text, tiny screen" || attack == "combo") { org.robolectric.RuntimeEnvironment.setQualifiers(startQualifiers); org.robolectric.RuntimeEnvironment.setFontScale(startFontScale) }
                recreate()
            }
            CrashLog.clear(ctx)
            runCatching { home() }
        }
    }

    private val traceFile get() = System.getProperty("metrocast.simout")?.let { File("$it.traces.txt") }
    private fun traces(title: String, t: Throwable) {
        val sw = java.io.StringWriter(); t.printStackTrace(java.io.PrintWriter(sw))
        traceFile?.appendText("==== [$name] $title\n" + sw.toString().lines().take(80).joinToString("\n") + "\n\n")
    }

    fun run(seconds: Long) {
        // Watchdog: if the phone makes no progress for 20 s (the screen never settles), write down what the
        // main thread keeps doing, five times, two seconds apart: that names the code that loops.
        val main = Thread.currentThread()
        val dog = Thread {
            var last = -1; var still = 0
            try { while (true) {
                Thread.sleep(2_000)
                val n = log.size
                if (n == last) still += 2 else { still = 0; last = n }
                if (still in 20..29) traceFile?.appendText("==== [$name] no progress for ${still}s after ${log.takeLast(12)}\n" +
                    main.stackTrace.take(70).joinToString("\n") { "    at $it" } + "\n" +
                    // …and the app's background work at that moment (a load that never finishes keeps a spinner up).
                    Thread.getAllStackTraces().filter { (t, st) -> t != main && st.any { it.className.startsWith("com.metrocast.app") && !it.className.contains(".robo.") } }
                        .entries.joinToString("\n") { (t, st) -> "  thread ${t.name} (${t.state}):\n" + st.take(25).joinToString("\n") { "      at $it" } } + "\n\n")
            } } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }
        try { runInner(seconds) } finally { dog.interrupt() }
    }

    private var startQualifiers = ""
    private var startFontScale = 1f

    private fun runInner(seconds: Long) {
        compose.waitUntil(10_000) { sees("MetroCast") }
        val c = ApplicationProvider.getApplicationContext<Context>().resources.configuration
        startQualifiers = org.robolectric.RuntimeEnvironment.getQualifiers(); startFontScale = c.fontScale
        Net.testOnline = true
        val end = System.currentTimeMillis() + seconds * 1000
        var rounds = 0
        var stopped: Throwable? = null
        var stoppedAt = ""
        try { while (System.currentTimeMillis() < end) {
            when (rnd.nextInt(18)) {
                in 10..17 -> chaos()
                0, 1 -> ride()
                2 -> trouble()
                3 -> trains()
                4 -> airports()
                5 -> settings()
                else -> { if (rnd.nextInt(3) == 0) menu(listOf("Live trains", "Train maps", "Saved routes", "Trip history", "Restrooms", "Track work calendar", "Bikes", "Metro Police", "Special trains", "Scenic rides", "Train issues")[rnd.nextInt(11)]); randomTaps(8) }
            }
            rounds++
            // Robolectric keeps every dialog ever shown (a real phone frees them on close): forget them, or a long run runs out of memory.
            compose.runOnUiThread { org.robolectric.shadows.ShadowDialog.reset() }
        }
        home()
        check(sees("MetroCast")) { "[$name] couldn't get back home after: ${log.takeLast(15)}" }
        } catch (t: Throwable) {
            stopped = t
            stoppedAt = "last actions: ${log.takeLast(20)}\n      on screen: " + (runCatching { texts().filter { it.isNotBlank() }.take(15).map { it.take(40) } }.getOrNull() ?: "(unreadable)")
        }
        val report = buildString {
            appendLine("[$name] $rounds scenarios, ${log.size} actions, ${log.count { it == "trouble" }} rounds of WMATA trouble")
            appendLine("  rides: ${log.count { it.startsWith("pick ") }} planned, ${log.count { it == "Start live trip" }} started, ${log.count { it.startsWith("I'm on the train") || it == "BOARD" }} boarded, ${log.count { it == "End trip" }} ended")
            val screens = listOf("Trip planner", "Live trains", "Train maps", "Saved routes", "Trip history", "Restrooms", "Track work calendar", "Bikes", "Metro Police", "Special trains", "Scenic rides", "Train issues", "Airports", "Amtrak, MARC & VRE", "Settings").filter { s -> log.any { it == s } }
            if (recoveries.isNotEmpty()) appendLine("  live trains back after trouble: typically ${recoveries.sorted()[recoveries.size / 2]} ms, slowest ${recoveries.max()} ms (${recoveries.size} times)")
            if (slowest.isNotEmpty()) appendLine("    slowest by kind: " + slowest.entries.joinToString { "${kinds[it.key]} ${it.value} ms" })
            if (attacksDone.isNotEmpty()) appendLine("  attacks: " + attacksDone.entries.sortedBy { it.key }.joinToString { "${it.key} ×${it.value}" })
            appendLine("  screens opened: ${screens.size} of 15 · different buttons tapped: ${log.filter { it != "back" && it != "?" }.map { it.take(30) }.toSet().size}")
            stopped?.let { t -> val root = generateSequence(t) { it.cause }.last()
                appendLine("  STOPPED: ${t.message?.take(400)}\n      cause: ${root.javaClass.simpleName}: ${root.message?.take(200)} at ${root.stackTrace.firstOrNull { it.className.startsWith("com.metrocast.app") } ?: root.stackTrace.firstOrNull()}\n      $stoppedAt") }
            if (findings.isEmpty() && stopped == null) appendLine("  no problems seen")
            findings.forEach { (what, where) -> appendLine("  FINDING x${counts[what]}: $what\n      after: $where") }
        }
        println(report)
        System.getProperty("metrocast.simout")?.let { File(it).apply { parentFile?.mkdirs() }.appendText(report) }
        stopped?.let { throw it }
    }

    protected fun go() { val s = System.getProperty("metrocast.sim")?.toLongOrNull(); assumeTrue(s != null); run(s!!) }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SimPhone1PixelLight : SimPhone("Phone 1 · Pixel-size, light", 101) { @Test fun session() = go() }

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-night-xhdpi", fontScale = 1.3f)
class SimPhone2SmallDarkBigText : SimPhone("Phone 2 · small, dark, large text", 202) { @Test fun session() = go() }

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
class SimPhone3TabletLandscape : SimPhone("Phone 3 · tablet, landscape", 303) { @Test fun session() = go() }
