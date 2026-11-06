package cn.lighttap.app

import org.json.JSONArray
import org.json.JSONObject

object ConfigCodec {
    fun encode(config: ClickConfig): String = JSONObject().apply {
        require(config.structureError() == null)
        put("version", 2); put("mode", config.mode.name)
        put("groupIntervalMs", config.groupIntervalMs); put("stopMode", config.stopMode.name)
        put("roundLimit", config.roundLimit); put("durationMs", config.durationMs)
        put("startDelayMs", config.startDelayMs); put("roundDelayMs", config.roundDelayMs)
        put("keepScreenOn", config.keepScreenOn); put("volumeKeys", config.volumeKeys)
        put("overlayOpacity", config.overlayOpacity); put("markerSizeDp", config.markerSizeDp)
        put("points", JSONArray().apply { config.points.forEach { p -> put(JSONObject().apply {
            put("id", p.id); put("x", p.x.toDouble()); put("y", p.y.toDouble()); put("intervalMs", p.intervalMs)
            put("type", p.type.name); put("durationMs", p.durationMs)
            put("endX", p.endX.toDouble()); put("endY", p.endY.toDouble())
            put("repeatCount", p.repeatCount); put("enabled", p.enabled); put("label", p.label)
        }) } })
        config.screen?.let { s -> put("screen", JSONObject().apply { put("width", s.width); put("height", s.height); put("densityDpi", s.densityDpi); put("rotation", s.rotation) }) }
    }.toString()
    fun decode(raw: String): ClickConfig {
        require(raw.length <= 512_000) { "配置文件过大" }
        val obj = JSONObject(raw)
        require(obj.getInt("version") in 1..2) { "不支持此配置版本" }
        val array = obj.getJSONArray("points")
        require(array.length() <= MAX_ACTIONS) { "动作过多" }
        val points = (0 until array.length()).map { index ->
            val p = array.getJSONObject(index)
            val x = p.getDouble("x").toFloat(); val y = p.getDouble("y").toFloat()
            ClickPoint(p.getLong("id"), x, y, p.getLong("intervalMs"),
                ActionType.valueOf(p.optString("type", "TAP")), p.optLong("durationMs", 50),
                p.optDouble("endX", x.toDouble()).toFloat(), p.optDouble("endY", y.toDouble()).toFloat(),
                p.optInt("repeatCount", 1), p.optBoolean("enabled", true), p.optString("label", ""))
        }
        val s = obj.optJSONObject("screen")?.let { ScreenSpec(it.getInt("width"), it.getInt("height"), it.getInt("densityDpi"), it.getInt("rotation")) }
        val result = ClickConfig(ClickMode.valueOf(obj.getString("mode")), points, obj.getLong("groupIntervalMs"), StopMode.valueOf(obj.getString("stopMode")), obj.getLong("roundLimit"), obj.getLong("durationMs"), s,
            obj.optLong("startDelayMs", 0), obj.optLong("roundDelayMs", 0), obj.optBoolean("keepScreenOn", false), obj.optBoolean("volumeKeys", false), obj.optInt("overlayOpacity", 100), obj.optInt("markerSizeDp", 44))
        require(result.structureError() == null) { result.structureError() ?: "配置无效" }
        return result
    }
}

data class ClickProfile(val id: String, val name: String, val config: ClickConfig)
object ProfileCodec {
    fun encode(profiles: List<ClickProfile>): String = JSONObject().apply {
        require(profiles.size <= 30)
        put("format", "lighttap-profiles"); put("version", 1)
        put("profiles", JSONArray().apply { profiles.forEach { p ->
            require(p.name.isNotBlank() && p.name.length <= 40)
            put(JSONObject().apply { put("id", p.id); put("name", p.name); put("config", JSONObject(ConfigCodec.encode(p.config))) })
        } })
    }.toString()
    fun decode(raw: String): List<ClickProfile> {
        require(raw.length <= 2_000_000) { "方案文件过大" }
        val obj = JSONObject(raw)
        require(obj.getString("format") == "lighttap-profiles" && obj.getInt("version") == 1) { "不是支持的轻点方案文件" }
        val array = obj.getJSONArray("profiles"); require(array.length() <= 30) { "最多 30 套方案" }
        val result = (0 until array.length()).map { i ->
            val p = array.getJSONObject(i); val name = p.getString("name").trim(); val id = p.getString("id")
            require(name.isNotBlank() && name.length <= 40 && id.length in 1..100) { "方案名称或编号无效" }
            ClickProfile(id, name, ConfigCodec.decode(p.getJSONObject("config").toString()))
        }
        require(result.map { it.id }.distinct().size == result.size) { "方案编号重复" }
        return result
    }
}
