package cn.lighttap.app

import org.json.JSONArray
import org.json.JSONObject

object ConfigCodec {
    fun encode(config: ClickConfig): String = JSONObject().apply {
        put("version", 1)
        put("mode", config.mode.name)
        put("groupIntervalMs", config.groupIntervalMs)
        put("stopMode", config.stopMode.name)
        put("roundLimit", config.roundLimit)
        put("durationMs", config.durationMs)
        put("points", JSONArray().apply { config.points.forEach { p -> put(JSONObject().apply { put("id", p.id); put("x", p.x.toDouble()); put("y", p.y.toDouble()); put("intervalMs", p.intervalMs) }) } })
        config.screen?.let { s -> put("screen", JSONObject().apply { put("width", s.width); put("height", s.height); put("densityDpi", s.densityDpi); put("rotation", s.rotation) }) }
    }.toString()
    fun decode(raw: String): ClickConfig {
        val obj = JSONObject(raw)
        require(obj.getInt("version") == 1) { "Unsupported configuration version" }
        val array = obj.getJSONArray("points")
        require(array.length() <= 10)
        val points = (0 until array.length()).map { index ->
            val p = array.getJSONObject(index)
            ClickPoint(p.getLong("id"), p.getDouble("x").toFloat(), p.getDouble("y").toFloat(), p.getLong("intervalMs"))
        }
        val s = obj.optJSONObject("screen")?.let { ScreenSpec(it.getInt("width"), it.getInt("height"), it.getInt("densityDpi"), it.getInt("rotation")) }
        val result = ClickConfig(ClickMode.valueOf(obj.getString("mode")), points, obj.getLong("groupIntervalMs"), StopMode.valueOf(obj.getString("stopMode")), obj.getLong("roundLimit"), obj.getLong("durationMs"), s)
        require(points.all { it.x.isFinite() && it.y.isFinite() && it.intervalMs in 10..60_000 })
        require(points.map { it.id }.distinct().size == points.size)
        require(result.groupIntervalMs in 10..60_000 && result.roundLimit in 1..1_000_000 && result.durationMs in 1_000..86_400_000)
        return result
    }
}