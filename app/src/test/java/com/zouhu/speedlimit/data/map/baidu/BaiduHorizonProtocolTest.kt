package com.zouhu.speedlimit.data.map.baidu

import com.zouhu.speedlimit.domain.model.LaneDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaiduHorizonProtocolTest {
    @Test
    fun `auth success is parsed`() {
        val event = HorizonProtocol.parse("""{"qt":"auth","data":{"ack":1,"message":"ok","status":0}}""")
        assertEquals(HorizonEvent.Auth(0, "ok"), event)
    }

    @Test
    fun `position and link payloads are parsed`() {
        val position = HorizonProtocol.parse(
            """{"qt":"horizon","data":{"position":{"path_id":8,"link_id":16289313980,"offset":89.15,"speed":13.9,"probability":30},"route_id":"","version":1}}""",
        ) as HorizonEvent.Position
        assertEquals(8, position.position.pathId)
        assertEquals(16289313980L, position.position.linkId)
        assertEquals(1, position.version)

        val links = HorizonProtocol.parse(
            """{"qt":"horizon","data":{"route_id":"","version":1,"max_send_length":2000,"link":[{"path_id":8,"link_id":16289313980,"offset":0,"length":139.2,"distance_to_pos":-89.1,"road_grade":4,"road_name":"新金桥路","lanenum":3,"speed_limit":60,"speed_limit_type":0}]}}""",
        ) as HorizonEvent.Links
        assertEquals(1, links.links.size)
        assertEquals(60, links.links[0].speedLimit)
        assertEquals(0, links.links[0].speedLimitType)
        assertEquals("新金桥路", links.links[0].roadName)
    }

    @Test
    fun `camera payload is parsed`() {
        val event = HorizonProtocol.parse(
            """{"qt":"horizon","data":{"route_id":"","version":2,"camera_list":[{"distance_to_pos":100.3,"type":1,"speed_limit":80,"loc":[116.404,39.915],"linkid":123456789,"offset":300.2,"path_id":8,"angle":86}]}}""",
        ) as HorizonEvent.Cameras
        assertEquals(1, event.cameras.size)
        assertEquals(1, event.cameras[0].type)
        assertEquals(80, event.cameras[0].speedLimit)
        assertEquals(listOf(116.404, 39.915), event.cameras[0].loc)
    }

    @Test
    fun `traffic light payload is parsed`() {
        val event = HorizonProtocol.parse(
            """{"qt":"horizon","ts":1709009041280,"data":{"route_id":"","version":3,"traffic_light":[{"path_id":8,"link_id":16289313980,"offset":120.5,"distance_to_pos":88.2,"timestamp":1723456789000,"on_route":1,"road_flow_direction":"11","simple_eight_direction":"11","comfrom":0,"status":[{"count_down":32,"period":30,"lamp_status":21},{"count_down":3,"period":3,"lamp_status":22},{"count_down":40,"period":38,"lamp_status":23}]}]}}""",
        ) as HorizonEvent.TrafficLights
        assertEquals(3, event.version)
        assertEquals(1709009041280L, event.serverTsMs)
        assertEquals(1, event.lights.size)
        val light = event.lights[0]
        assertEquals(8, light.pathId)
        assertEquals(120.5, light.offset, 0.0)
        assertEquals(88.2, light.distanceToPos, 0.0)
        assertEquals(1723456789000L, light.timestamp)
        assertEquals(3, light.status?.size)
        assertEquals(21, light.status?.get(0)?.lampStatus)
        assertEquals(32, light.status?.get(0)?.countDown)
        assertEquals(30, light.status?.get(0)?.period)
        assertEquals(1, light.onRoute)
        assertEquals("11", light.roadFlowDirection)
        assertEquals("11", light.simpleEightDirection)
        assertEquals(0, light.comfrom)
    }

    @Test
    fun `lane arrows become lane directions`() {
        val arrow = HorizonLaneArrow(
            pathId = 8,
            offset = 180.2,
            direction = "1,13,12",
            laneType = "1,1,1",
        )
        val lanes = HorizonProtocol.laneList(arrow)
        assertEquals(3, lanes.size)
        assertEquals(setOf(LaneDirection.STRAIGHT), lanes[0].directions)
        assertEquals(setOf(LaneDirection.STRAIGHT, LaneDirection.RIGHT), lanes[1].directions)
        assertEquals(setOf(LaneDirection.STRAIGHT, LaneDirection.LEFT), lanes[2].directions)
    }

    @Test
    fun `unknown and malformed messages do not crash`() {
        assertNull(HorizonProtocol.parse("not json"))
        assertTrue(HorizonProtocol.parse("""{"qt":"horizon","data":{}}""") is HorizonEvent.Unknown)
        assertEquals(
            HorizonEvent.Ack(listOf(2, 3)),
            HorizonProtocol.parse("""{"qt":"horizon","data":{"ack":[2,3]}}"""),
        )
    }
}
