package io.github.xpertsavenue.ridevault.driver.igpsport

import kotlin.test.Test
import kotlin.test.assertEquals

class StatusProtocolTest {
    @Test
    fun devStatusDecodesLiveValues() {
        val rt = ProtoWriter().apply {
            varint(1, 8333)      // mm/s
            varint(3, 3_723_000) // ms
            varint(4, 1_250_000) // cm
            varint(6, 142)
            varint(8, 210)
            varint(12, (-250L))  // negative Steigung (int32, vorzeichenerweitert)
        }.out.toByteArray()
        val cycling = ProtoWriter().apply { varint(1, 1) }.out.toByteArray()
        val msg = ProtoWriter().apply {
            varint(1, 13); varint(2, 2)
            bytes(3, cycling)
            bytes(5, rt)
            varint(7, 1)
        }.out.toByteArray()
        val st = DevStatusMsg.decode(msg)
        assertEquals(1, st.cyclingStatus)
        assertEquals(1, st.naviStatus)
        assertEquals(8333L, st.rt?.speed)
        assertEquals(3_723_000L, st.rt?.ridingTime)
        assertEquals(1_250_000L, st.rt?.distance)
        assertEquals(142, st.rt?.heartRate)
        assertEquals(210, st.rt?.power)
        assertEquals(-250, st.rt?.slope)
    }

    @Test
    fun factoryBatteryAndMemory() {
        val battery = ProtoWriter().apply { varint(1, 3900); varint(2, 87) }.out.toByteArray()
        val memory = ProtoWriter().apply { varint(1, 1000); varint(2, 640) }.out.toByteArray()
        val b = ProtoWriter().apply { varint(1, 11); varint(2, 6); bytes(6, battery) }.out.toByteArray()
        val m = ProtoWriter().apply { varint(1, 11); varint(2, 5); bytes(5, memory) }.out.toByteArray()
        assertEquals(3900 to 87, FactoryMsg.decodeBattery(b))
        assertEquals(1000L to 640L, FactoryMsg.decodeMemory(m))
    }

    @Test
    fun renameAndEmptyUseEncoding() {
        val info = RouteInfoMsg(id = 5, fileType = 2, name = "Neu", totalDistanceM = 0)
        val rename = RoutePlanMsg.decode(RoutePlanMsg(RouteOp.RENAME, listOf(info), lineIds = listOf("5.gpx")).encode())
        assertEquals(RouteOp.RENAME, rename.operation)
        assertEquals(listOf("5.gpx"), rename.lineIds)
        assertEquals("Neu", rename.infos.single().name)
    }
}
