package com.leon.workflow.controller;

import com.leon.workflow.service.ToolboxItemService;
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
@RequestMapping("/api/toolbox")
public class ToolboxItemController {
    private final ToolboxItemService toolboxItemService;

    public ToolboxItemController(ToolboxItemService toolboxItemService) {
        this.toolboxItemService = toolboxItemService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listItems() {
        return ok(toolboxItemService.listItems());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createItem(@RequestBody Map<String, Object> request) {
        return ok(toolboxItemService.createItem(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateItem(@PathVariable Long id,
                                                          @RequestBody Map<String, Object> request) {
        return ok(toolboxItemService.updateItem(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteItem(@PathVariable Long id) {
        toolboxItemService.deleteItem(id);
        return ok("deleted");
    }
}
