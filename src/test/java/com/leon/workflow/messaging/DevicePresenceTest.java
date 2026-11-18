package com.leon.workflow.messaging;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DevicePresenceTest {
    static final class TestClock extends Clock {
        long now=100000;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}
    }
    @Test void devicesDeduplicateRefreshAndExpireAtServerTimeout() {
        var clock=new TestClock();var service=new DevicePresenceService(clock);String mac=UUID.randomUUID().toString(),phone=UUID.randomUUID().toString();
        service.heartbeat(new DevicePresenceService.Heartbeat(mac,"Mac","macos"));service.heartbeat(new DevicePresenceService.Heartbeat(phone,"手机","android"));assertEquals(2,service.online().get("onlineCount"));
        clock.now+=30000;service.heartbeat(new DevicePresenceService.Heartbeat(mac,"改名的 Mac","macos"));assertEquals(2,service.online().get("onlineCount"));
        clock.now+=15000;assertEquals(1,service.online().get("onlineCount"));assertEquals("改名的 Mac",((List<DevicePresenceService.Device>)service.online().get("devices")).getFirst().name());
        clock.now+=30000;assertEquals(0,service.online().get("onlineCount"));
        assertEquals(0,new DevicePresenceService(clock).online().get("onlineCount"));
    }
    @Test void invalidAndBrowserIdentitiesCannotBecomeOnlineDevices() {
        var service=new DevicePresenceService();String id=UUID.randomUUID().toString();
        for(var input:List.of(new DevicePresenceService.Heartbeat("bad","Mac","macos"),new DevicePresenceService.Heartbeat(id," ","macos"),new DevicePresenceService.Heartbeat(id,"x\ny","macos"),new DevicePresenceService.Heartbeat(id,"x".repeat(61),"macos"),new DevicePresenceService.Heartbeat(id,"浏览器","web")))assertThrows(Exception.class,()->service.heartbeat(input));
        assertEquals(0,service.online().get("onlineCount"));
    }
}
