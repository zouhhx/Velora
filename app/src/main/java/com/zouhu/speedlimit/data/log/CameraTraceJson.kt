package com.zouhu.speedlimit.data.log

import com.zouhu.speedlimit.data.map.baidu.CruiseCallTrace
import com.zouhu.speedlimit.data.settings.SecretRedaction
import com.zouhu.speedlimit.domain.model.GpsSample
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure JSONL formatter for the VA-C01 `camera_list` capture.
 *
 * No Android dependency: [CameraTrace] owns the file, this object owns the schema, so
 * the exact field set can be locked by JVM tests before any road data exists.
 *
 * Deliberately never receives the request URL: the AK lives in a query parameter and
 * must not be written to a file that can be shared off-device.
 */
internal object CameraTraceJson {
    const val VERSION = 1

    /** A single body is capped; the tail is still observable through `raw.truncated`. */
    const val MAX_BODY_CHARS = 262_144

    fun metaLine(
        versionName: String,
        versionCode: Int,
        device: String,
        android: String,
        sdk: Int,
        nowMs: Long,
    ): String = buildJsonObject {
        put("v", VERSION)
        put("kind", "meta")
        put("ts", nowMs)
        put("app", "$versionName($versionCode)")
        put("device", device)
        put("android", android)
        put("sdk", sdk)
    }.toString()

    /**
     * One line per HTTP response.
     *
     * Both the parsed projection and the raw body are kept on purpose:
     * - `resp.routes[].camera_list` is the stable field set the app actually maps;
     * - `raw.body` preserves fields the app does not model yet (for example angle or
     *   server-side filtering hints), so VA-C01 does not need a second drive just to
     *   discover them.
     */
    fun httpLine(trace: CruiseCallTrace, nowMs: Long): String {
        val raw = trace.rawBody
        val truncated = raw.length > MAX_BODY_CHARS
        val body = if (truncated) raw.take(MAX_BODY_CHARS) else raw
        val routes = trace.response.result?.routes.orEmpty()
        return buildJsonObject {
            put("v", VERSION)
            put("kind", "camera_list")
            put("ts", nowMs)
            put("endpoint", trace.endpoint)
            put(
                "req",
                buildJsonObject {
                    put("origin", trace.origin)
                    put("gps_direction", trace.gpsDirection)
                    put("speed_mps", trace.speedMps)
                    put("coord_type", "wgs84")
                    put("ret_coordtype", "gcj02")
                    put("point_count", if (trace.pointList.isBlank()) 0 else trace.pointList.count { it == ';' } + 1)
                    put("point_list", trace.pointList)
                },
            )
            put(
                "resp",
                buildJsonObject {
                    put("status", trace.response.status)
                    put("message", SecretRedaction.redact(trace.response.message))
                    put(
                        "routes",
                        buildJsonArray {
                            routes.forEach { route ->
                                add(
                                    buildJsonObject {
                                        put("route_id", route.route_id)
                                        put("data_version", route.data_version)
                                        put("distance", route.distance)
                                        put("linkids", route.linkids)
                                        put("camera_count", route.camera_list.size)
                                        put(
                                            "camera_list",
                                            buildJsonArray {
                                                route.camera_list.forEach { camera ->
                                                    add(
                                                        buildJsonObject {
                                                            put("type", camera.type)
                                                            put("priority", camera.priority)
                                                            put("position", camera.position)
                                                            put("linkid", camera.linkid)
                                                            put("offset", camera.offset)
                                                            put(
                                                                "speed_limit",
                                                                camera.speed_limit?.let { JsonPrimitive(it) } ?: JsonNull,
                                                            )
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )
                },
            )
            put(
                "raw",
                buildJsonObject {
                    put("bytes", raw.toByteArray(Charsets.UTF_8).size)
                    put("truncated", truncated)
                    put("body", SecretRedaction.redact(body))
                },
            )
        }.toString()
    }

    /**
     * GPS frames are recorded so the evaluator can replay what the app computed *between*
     * two 30 s responses. A camera list alone cannot show that a passed camera is still
     * being treated as "ahead" by the local haversine recalculation.
     */
    fun gpsLine(sample: GpsSample, nowMs: Long): String = buildJsonObject {
        put("v", VERSION)
        put("kind", "gps")
        put("ts", sample.timestampMs)
        put("wall_ts", nowMs)
        put("lat", sample.latitude)
        put("lng", sample.longitude)
        put("has_fix", sample.hasFix)
        put("has_position", sample.hasPosition)
        put("bearing", sample.bearingDeg)
        put("has_bearing", sample.hasBearing)
        put("speed_mps", sample.speedMps)
        put("has_speed", sample.hasSpeed)
        put("accuracy_m", sample.accuracyM)
        put("has_accuracy", sample.hasAccuracy)
        put("source", sample.source.name)
    }.toString()
}
