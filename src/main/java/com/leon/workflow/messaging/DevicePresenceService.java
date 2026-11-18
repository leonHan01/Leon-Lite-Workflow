package com.leon.workflow.messaging;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;

/** Presence is ephemeral and based exclusively on authenticated server-received heartbeats. */
@Service
public class DevicePresenceService {
    public static final int TIMEOUT_SECONDS=45;
    private final Clock clock;
    private final Map<String,Device> devices=new HashMap<>();
    public DevicePresenceService() { this(Clock.systemUTC()); }
    DevicePresenceService(Clock clock) { this.clock=clock; }
    public record Heartbeat(String deviceId,String name,String platform) {}
    public record Device(String deviceId,String name,String platform,long lastSeenAt) {}
    public synchronized Device heartbeat(Heartbeat input) {
        if(input==null) throw DeviceMessageStore.failure(HttpStatus.BAD_REQUEST,"设备信息不能为空");
        String id=DeviceMessageStore.uuid(input.deviceId()),name=input.name()==null?"":input.name().strip();
        if(name.isBlank()||name.length()>60||name.codePoints().anyMatch(Character::isISOControl)) throw DeviceMessageStore.failure(HttpStatus.BAD_REQUEST,"设备名称应为 1–60 字");
        if(!Set.of("macos","android").contains(input.platform()==null?"":input.platform())) throw DeviceMessageStore.failure(HttpStatus.BAD_REQUEST,"设备平台无效");
        long now=clock.millis();prune(now);
        if(!devices.containsKey(id)&&devices.size()>=1024) throw DeviceMessageStore.failure(HttpStatus.TOO_MANY_REQUESTS,"在线设备数量已达上限");
        var device=new Device(id,name,input.platform(),now);devices.put(id,device);return device;
    }
    public synchronized Map<String,Object> online() {
        prune(clock.millis());var rows=devices.values().stream().sorted(Comparator.comparingLong(Device::lastSeenAt).reversed().thenComparing(Device::deviceId)).toList();
        return Map.of("onlineCount",rows.size(),"devices",rows,"timeoutSeconds",TIMEOUT_SECONDS);
    }
    private void prune(long now) { devices.values().removeIf(d->now-d.lastSeenAt()>=TIMEOUT_SECONDS*1000L); }
}
