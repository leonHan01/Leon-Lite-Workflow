package com.leon.workflow.messaging;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

/** Existing /api/** interceptor authenticates both reads and writes. */
@RestController
@RequestMapping("/api/device-messages")
public class DeviceMessageController {
    private final DeviceMessageStore store;
    public DeviceMessageController(DeviceMessageStore store) { this.store = store; }

    @GetMapping
    public ResponseEntity<DeviceMessageStore.Page> list(@RequestParam(required = false) Long after,
            @RequestParam(required = false) Long before, @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.page(after, before, limit));
    }

    @PostMapping
    public ResponseEntity<DeviceMessageStore.Message> send(@RequestBody DeviceMessageStore.Send input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.send(input));
    }
}
