package com.goju.ribs.myrainassist.backtest

import com.goju.ribs.myrainassist.analysis.ForecastState
import com.goju.ribs.myrainassist.analysis.RainForecastResult
import com.goju.ribs.myrainassist.data.LatLon
import com.goju.ribs.myrainassist.data.RadarFrame
import com.goju.ribs.myrainassist.data.RadarLegend
import com.goju.ribs.myrainassist.data.RadarResponse
import com.goju.ribs.myrainassist.geo.QuadMapper
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Replays real KMA radar history through the forecast engines and scores them against what the
 * radar actually showed afterwards. Opt-in (needs network + a few minutes), e.g.:
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*ForecastBacktest*' --rerun \
 *       -Pbacktest.from=202610180600 -Pbacktest.to=202610182000
 *
 * Report: app/build/backtest/report.txt
 *
 * Ground truth for "rain at a location at time T" is the radar itself: a real (>= MIN_BLOB_CELLS
 * connected) echo within the location's 3x3 cell neighborhood (~2km) in the frame closest to T.
 * Each forecast is issued at now = frame tm + [lagMinutes], mirroring the typical KMA publish lag
 * seen on the device (7-8 min), so "raining now" is checked against the frame ~10 min after tm.
 */
class ForecastBacktest {

    interface Engine {
        val name: String
        fun run(frames: List<RadarFrame>, corners: List<LatLon>, locations: List<LatLon>, nowEpochMs: Long): List<RainForecastResult?>
    }

    private class Prediction(val state: ForecastState, val etaMinutes: Int?)

    @Test
    fun backtest() {
        val from = System.getProperty("backtest.from")
        assumeTrue("backtest not requested (-Pbacktest.from=yyyyMMddHHmm)", from != null)
        val to = System.getProperty("backtest.to") ?: KmaFrameSource.TM_FORMAT.format(LocalDateTime.now())
        val lagMinutes = System.getProperty("backtest.lagMinutes")?.toInt() ?: 8
        val source = KmaFrameSource(File(System.getProperty("backtest.cacheDir")))
        val reportDir = File(System.getProperty("backtest.reportDir")).apply { mkdirs() }

        val engines = BacktestEngines.all()
        val mapper = QuadMapper.KMA_COMPOSITE
        val tms = tmRange(from!!, to)
        val locationCells = LOCATIONS.map { loc ->
            mapper.inverseMap(loc)!!.let { uv -> intArrayOf((uv.v * (GRID - 1)).roundToInt(), (uv.u * (GRID - 1)).roundToInt()) }
        }

        // truth[tmIndex][locIndex]; null = frame missing
        val truth = arrayOfNulls<BooleanArray>(tms.size)
        val predictions = engines.associate { it.name to arrayOfNulls<Array<Prediction?>>(tms.size) }
        val timings = engines.associate { it.name to LongArray(1) }
        val window = ArrayDeque<RadarFrame>()

        tms.forEachIndexed { i, tm ->
            val frame = source.load(tm) ?: return@forEachIndexed
            truth[i] = truthFor(frame, locationCells)
            window.addLast(frame)
            while (window.size > FRAME_WINDOW || frame.epochMinute - window.first().epochMinute > 20) window.removeFirst()
            if (window.size < 2) return@forEachIndexed
            val now = (frame.epochMinute + lagMinutes) * 60_000L
            engines.forEach { engine ->
                val started = System.nanoTime()
                val results = engine.run(window.toList(), CORNERS, LOCATIONS, now)
                timings.getValue(engine.name)[0] += System.nanoTime() - started
                predictions.getValue(engine.name)[i] = Array(LOCATIONS.size) { l -> results[l]?.let { Prediction(it.state, it.etaMinutes) } }
            }
        }

        val report = StringBuilder()
        report.appendLine("Backtest $from..$to  lag=${lagMinutes}min  frames=${truth.count { it != null }}/${tms.size}  locations=${LOCATIONS.size}")
        engines.forEach { engine ->
            report.appendLine()
            report.append(score(engine.name, predictions.getValue(engine.name), truth, tms, lagMinutes))
            report.appendLine("  compute time: ${timings.getValue(engine.name)[0] / 1_000_000} ms total")
        }
        File(reportDir, "report.txt").writeText(report.toString())
        println(report)
    }

    /**
     * Prints what the current engine says right now across the peninsula — handy for picking a
     * debug location (DebugCommandReceiver) where something interesting is happening:
     *   ./gradlew :app:testDebugUnitTest --tests '*ForecastBacktest.probeNow*' --rerun -Pbacktest.probe=1
     */
    @Test
    fun probeNow() {
        assumeTrue("probe not requested (-Pbacktest.probe=1)", System.getProperty("backtest.probe") != null)
        val source = KmaFrameSource(File(System.getProperty("backtest.cacheDir")))
        var t = LocalDateTime.now().withSecond(0).withNano(0).let { it.withMinute(it.minute / 5 * 5) }
        val frames = mutableListOf<RadarFrame>()
        repeat(12) {
            if (frames.size < FRAME_WINDOW) source.load(KmaFrameSource.TM_FORMAT.format(t))?.let { frames.add(0, it) }
            t = t.minusMinutes(5)
        }
        val latest = frames.last()
        val now = System.currentTimeMillis()
        val out = StringBuilder("probe at ${LocalDateTime.now()} latest tm=${latest.tm} frames=${frames.map { it.tm }}\n")
        var lat = 34.6
        while (lat <= 38.4) {
            var lon = 126.2
            while (lon <= 129.4) {
                val r = com.goju.ribs.myrainassist.analysis.ForecastEngine.computeForecast(RadarResponse(CORNERS, frames), LatLon(lat, lon), now)
                if (r != null && r.state != ForecastState.NONE) {
                    out.append("%.2f,%.2f %s eta=%s end=%s mmh=%s\n".format(lat, lon, r.state, r.etaMinutes, r.rainEndMinutes, r.intensityMmh))
                }
                lon += 0.15
            }
            lat += 0.15
        }
        File(File(System.getProperty("backtest.reportDir")).apply { mkdirs() }, "probe.txt").writeText(out.toString())
    }

    /**
     * Writes the exact WebView payload (RainForecastResult.toJson) the app would push for a location
     * right now — for checking the map drawing in a browser without the phone:
     *   ./gradlew :app:testDebugUnitTest --tests '*ForecastBacktest.dumpForecast*' --rerun -Pbacktest.dumpAt=37.0,127.9
     * Output: app/build/backtest/forecast.json
     */
    @Test
    fun dumpForecast() {
        val at = System.getProperty("backtest.dumpAt")
        assumeTrue("dump not requested (-Pbacktest.dumpAt=lat,lon)", at != null)
        val (lat, lon) = at!!.split(",").map { it.trim().toDouble() }
        val source = KmaFrameSource(File(System.getProperty("backtest.cacheDir")))
        var t = LocalDateTime.now().withSecond(0).withNano(0).let { it.withMinute(it.minute / 5 * 5) }
        val frames = mutableListOf<RadarFrame>()
        repeat(12) {
            if (frames.size < FRAME_WINDOW) source.load(KmaFrameSource.TM_FORMAT.format(t))?.let { frames.add(0, it) }
            t = t.minusMinutes(5)
        }
        val result = com.goju.ribs.myrainassist.analysis.ForecastEngine.computeForecast(RadarResponse(CORNERS, frames), LatLon(lat, lon), System.currentTimeMillis())!!
        File(File(System.getProperty("backtest.reportDir")).apply { mkdirs() }, "forecast.json").writeText(result.toJson().toString(1))
    }

    private fun score(
        name: String,
        predictions: Array<Array<Prediction?>?>,
        truth: Array<BooleanArray?>,
        tms: List<String>,
        lagMinutes: Int,
    ): String {
        // Index of the frame whose tm is closest to `now` (= tm[i] + lag): frames are 5 min apart.
        val nowOffset = ((lagMinutes + 2) / 5)
        fun truthAt(i: Int, l: Int): Boolean? = truth.getOrNull(i)?.get(l)

        // First frame index >= start where it rains at l, scanning up to maxFrames ahead; null if dry throughout (or unknown).
        fun onsetIndex(start: Int, l: Int, maxFrames: Int): Int? {
            for (j in start..minOf(start + maxFrames, tms.size - 1)) if (truthAt(j, l) == true) return j
            return null
        }

        var activeTp = 0; var activeFp = 0; var activeFn = 0; var truthRainy = 0
        var incomingCount = 0; var incomingHit = 0; var incomingFalse = 0
        val etaErrors = mutableListOf<Int>()
        val falseActive = mutableListOf<String>()

        for (i in tms.indices) {
            val preds = predictions[i] ?: continue
            val nowIdx = i + nowOffset
            for (l in LOCATIONS.indices) {
                val p = preds[l] ?: continue
                val rainingNow = truthAt(nowIdx, l) ?: continue
                if (rainingNow) truthRainy++
                when (p.state) {
                    ForecastState.ACTIVE -> if (rainingNow || truthAt(nowIdx - 1, l) == true || truthAt(nowIdx + 1, l) == true) activeTp++ else {
                        activeFp++
                        if (falseActive.size < 8) falseActive += "${tms[i]} ${LOCATIONS[l]}"
                    }
                    ForecastState.INCOMING -> {
                        if (rainingNow) continue
                        incomingCount++
                        val eta = p.etaMinutes ?: 0
                        val onset = onsetIndex(nowIdx, l, (eta + 20) / 5)
                        if (onset == null) incomingFalse++ else {
                            incomingHit++
                            val trueEta = (onset - i) * 5 - lagMinutes
                            etaErrors += trueEta - eta
                        }
                    }
                    ForecastState.NONE -> if (rainingNow) activeFn++
                }
            }
        }

        // Onset events: dry for the previous 3 frames (15 min), raining now. Did the engine warn
        // (INCOMING/ACTIVE) in a forecast issued before the onset, and how early?
        var onsets = 0; var warned = 0; var warned10 = 0
        val leads = mutableListOf<Int>()
        for (l in LOCATIONS.indices) {
            for (j in 3 until tms.size) {
                if (truthAt(j, l) != true) continue
                if ((1..3).any { truthAt(j - it, l) != false }) continue
                onsets++
                // Forecasts issued at now = tm[i] + lag with now <= tm[j]; earliest one that warned.
                var bestLead: Int? = null
                for (i in (j - 8).coerceAtLeast(0)..j) {
                    val leadMinutes = (j - i) * 5 - lagMinutes
                    if (leadMinutes < 0) continue
                    val p = predictions[i]?.get(l) ?: continue
                    if (p.state != ForecastState.NONE) { bestLead = leadMinutes; break }
                }
                if (bestLead != null) {
                    warned++
                    leads += bestLead
                    if (bestLead >= 10) warned10++
                }
            }
        }

        fun pct(a: Int, b: Int) = if (b == 0) "-" else "${(100.0 * a / b).roundToInt()}%"
        fun median(xs: List<Int>) = if (xs.isEmpty()) "-" else xs.sorted()[xs.size / 2].toString()
        return buildString {
            appendLine("[$name]")
            appendLine("  ACTIVE  : precision ${pct(activeTp, activeTp + activeFp)} ($activeTp/${activeTp + activeFp}), recall ${pct(activeTp, truthRainy)} (rainy samples $truthRainy, NONE-while-raining $activeFn)")
            appendLine("  INCOMING: $incomingCount alerts, hit ${pct(incomingHit, incomingCount)}, false $incomingFalse, ETA error (true-pred) median ${median(etaErrors)} min, |err| median ${median(etaErrors.map { abs(it) })} min")
            appendLine("  ONSETS  : $onsets events, warned before onset ${pct(warned, onsets)}, >=10min ahead ${pct(warned10, onsets)}, median lead ${median(leads)} min")
            if (falseActive.isNotEmpty()) appendLine("  false ACTIVE samples: ${falseActive.joinToString("; ")}")
        }
    }

    private fun truthFor(frame: RadarFrame, locationCells: List<IntArray>): BooleanArray {
        val member = realEchoMask(frame)
        return BooleanArray(locationCells.size) { l ->
            val (r, c) = locationCells[l]
            (-1..1).any { dr -> (-1..1).any { dc ->
                val rr = r + dr; val cc = c + dc
                rr in 0 until frame.gridHeight && cc in 0 until frame.gridWidth && member[rr * frame.gridWidth + cc]
            } }
        }
    }

    /** Cells belonging to a connected echo of >= MIN_BLOB_CELLS — independent of either engine's code. */
    private fun realEchoMask(frame: RadarFrame): BooleanArray {
        val w = frame.gridWidth; val h = frame.gridHeight
        fun present(i: Int) = (frame.grid[i].toInt() and 0xFF) <= RadarLegend.RAIN_THRESHOLD_INDEX
        val label = IntArray(w * h) { -1 }
        val member = BooleanArray(w * h)
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (label[start] != -1 || !present(start)) continue
            var top = 0; stack[top++] = start; label[start] = start
            val cells = mutableListOf<Int>()
            while (top > 0) {
                val i = stack[--top]; cells += i
                val r = i / w; val c = i % w
                for (dr in -1..1) for (dc in -1..1) {
                    val rr = r + dr; val cc = c + dc
                    if (rr !in 0 until h || cc !in 0 until w) continue
                    val n = rr * w + cc
                    if (label[n] == -1 && present(n)) { label[n] = start; stack[top++] = n }
                }
            }
            if (cells.size >= MIN_BLOB_CELLS) cells.forEach { member[it] = true }
        }
        return member
    }

    private fun tmRange(from: String, to: String): List<String> {
        var t = LocalDateTime.parse(from, KmaFrameSource.TM_FORMAT)
        val end = LocalDateTime.parse(to, KmaFrameSource.TM_FORMAT)
        val out = mutableListOf<String>()
        while (!t.isAfter(end)) { out += KmaFrameSource.TM_FORMAT.format(t); t = t.plusMinutes(5) }
        return out
    }

    companion object {
        private const val GRID = 1453
        private const val FRAME_WINDOW = 4
        private const val MIN_BLOB_CELLS = 9

        // Same corners the server publishes (my-bot-server-app/src/rain-radar/radar-geo.ts).
        val CORNERS = listOf(
            LatLon(30.8101038494, 121.3322516155),
            LatLon(40.1670385352, 120.609116658),
            LatLon(40.0701181652, 133.0225827684),
            LatLon(30.7283967663, 132.0821758282),
        )

        // A coarse grid over the peninsula plus the device's usual location.
        val LOCATIONS: List<LatLon> = buildList {
            add(LatLon(37.621, 127.157))
            var lat = 35.0
            while (lat <= 37.81) {
                var lon = 126.6
                while (lon <= 129.01) { add(LatLon(lat, lon)); lon += 0.6 }
                lat += 0.4
            }
        }
    }
}

/** Engines under comparison. The legacy engine is frozen; the current one is whatever ships. */
object BacktestEngines {
    fun all(): List<ForecastBacktest.Engine> = listOf(legacy(), current())

    private fun current() = object : ForecastBacktest.Engine {
        override val name = "current"
        override fun run(frames: List<RadarFrame>, corners: List<LatLon>, locations: List<LatLon>, nowEpochMs: Long): List<RainForecastResult?> {
            val response = RadarResponse(corners, frames)
            return locations.map { com.goju.ribs.myrainassist.analysis.ForecastEngine.computeForecast(response, it, nowEpochMs) }
        }
    }

    private fun legacy() = object : ForecastBacktest.Engine {
        override val name = "legacy (pre-v2)"
        override fun run(frames: List<RadarFrame>, corners: List<LatLon>, locations: List<LatLon>, nowEpochMs: Long): List<RainForecastResult?> {
            val engine = com.goju.ribs.myrainassist.backtest.legacy.ForecastEngine
            val analysis = engine.analyze(RadarResponse(corners, frames)) ?: return locations.map { null }
            return locations.map { engine.forecastFor(analysis, it, nowEpochMs) }
        }
    }
}
