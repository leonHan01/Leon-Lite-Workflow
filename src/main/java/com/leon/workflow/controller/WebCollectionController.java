package com.leon.workflow.controller;

import com.leon.workflow.service.WebCollectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.leon.workflow.controller.ApiResponses.ok;

@RestController
@RequestMapping("/api/web-collections")
public class WebCollectionController {
    private final WebCollectionService webCollectionService;

    public WebCollectionController(WebCollectionService webCollectionService) {
        this.webCollectionService = webCollectionService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listCollections() {
        return ok(webCollectionService.listCollections());
    }

    @GetMapping("/full")
    public ResponseEntity<Map<String, Object>> listCollectionDetails() {
        return ok(webCollectionService.listCollectionDetails());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getCollection(@PathVariable Long id) {
        return ok(webCollectionService.getCollection(id));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createCollection(@RequestBody Map<String, Object> request) {
        return ok(webCollectionService.createCollection(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateCollection(@PathVariable Long id,
                                                                @RequestBody Map<String, Object> request) {
        return ok(webCollectionService.updateCollection(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteCollection(@PathVariable Long id) {
        webCollectionService.deleteCollection(id);
        return ok("deleted");
    }

    @PutMapping("/reorder")
    public ResponseEntity<Map<String, Object>> reorderCollections(@RequestBody Map<String, Object> request) {
        webCollectionService.reorderCollections(request);
        return ok("saved");
    }

    @PostMapping("/{id}/groups")
    public ResponseEntity<Map<String, Object>> createGroup(@PathVariable Long id,
                                                           @RequestBody Map<String, Object> request) {
        return ok(webCollectionService.createGroup(id, request));
    }

    @PutMapping("/{id}/groups/{groupId}")
    public ResponseEntity<Map<String, Object>> updateGroup(@PathVariable Long id,
                                                           @PathVariable Long groupId,
                                                           @RequestBody Map<String, Object> request) {
        return ok(webCollectionService.updateGroup(id, groupId, request));
    }

    @DeleteMapping("/{id}/groups/{groupId}")
    public ResponseEntity<Map<String, Object>> deleteGroup(@PathVariable Long id,
                                                           @PathVariable Long groupId) {
        webCollectionService.deleteGroup(id, groupId);
        return ok("deleted");
    }

    @PutMapping("/{id}/groups/reorder")
    public ResponseEntity<Map<String, Object>> reorderGroups(@PathVariable Long id,
                                                             @RequestBody Map<String, Object> request) {
        webCollectionService.reorderGroups(id, request);
        return ok("saved");
    }

    @PostMapping("/{id}/items")
    public ResponseEntity<Map<String, Object>> createItem(@PathVariable Long id,
                                                          @RequestBody Map<String, Object> request) {
        return ok(webCollectionService.createItem(id, request));
    }

    @PutMapping("/{id}/items/{itemId}")
    public ResponseEntity<Map<String, Object>> updateItem(@PathVariable Long id,
                                                          @PathVariable Long itemId,
                                                          @RequestBody Map<String, Object> request) {
        return ok(webCollectionService.updateItem(id, itemId, request));
    }

    @DeleteMapping("/{id}/items/{itemId}")
    public ResponseEntity<Map<String, Object>> deleteItem(@PathVariable Long id,
                                                          @PathVariable Long itemId) {
        webCollectionService.deleteItem(id, itemId);
        return ok("deleted");
    }

    @PutMapping("/{id}/groups/{groupId}/items/reorder")
    public ResponseEntity<Map<String, Object>> reorderItems(@PathVariable Long id,
                                                            @PathVariable Long groupId,
                                                            @RequestBody Map<String, Object> request) {
        webCollectionService.reorderItems(id, groupId, request);
        return ok("saved");
    }
}
