package com.leon.workflow.service;

import com.leon.workflow.dao.entity.WebCollection;
import com.leon.workflow.dao.entity.WebCollectionGroup;
import com.leon.workflow.dao.entity.WebCollectionItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Pure response mapping; never loads entities or opens transactions. */
final class WebCollectionResponses {
    private WebCollectionResponses() {}

    static Map<String, Object> toCollectionSummary(WebCollection collection, int groupCount, int itemCount) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", collection.getId());
        result.put("name", collection.getName());
        result.put("description", collection.getDescription());
        result.put("icon", collection.getIcon() == null ? "" : collection.getIcon());
        result.put("groupCount", groupCount);
        result.put("itemCount", itemCount);
        result.put("sortOrder", collection.getSortOrder());
        result.put("createTime", collection.getCreateTime());
        result.put("updateTime", collection.getUpdateTime());
        return result;
    }

    static Map<String, Object> toCollectionDetail(WebCollection collection, List<WebCollectionGroup> groups,
                                                   List<WebCollectionItem> items) {
        Map<String, Object> result = toCollectionSummary(collection, groups.size(), items.size());
        Map<Long, List<WebCollectionItem>> byGroup = items.stream().filter(item -> item.getGroupId() != null)
                .collect(Collectors.groupingBy(WebCollectionItem::getGroupId));
        result.put("groups", groups.stream()
                .map(group -> toGroupResponse(group, byGroup.getOrDefault(group.getId(), List.of()))).toList());
        return result;
    }

    static Map<String, Object> toGroupResponse(WebCollectionGroup group, List<WebCollectionItem> items) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", group.getId());
        result.put("collectionId", group.getCollectionId());
        result.put("name", group.getName());
        result.put("sortOrder", group.getSortOrder());
        result.put("createTime", group.getCreateTime());
        result.put("updateTime", group.getUpdateTime());
        result.put("items", items.stream().map(WebCollectionResponses::toItemResponse).toList());
        return result;
    }

    static Map<String, Object> toItemResponse(WebCollectionItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.getId());
        result.put("collectionId", item.getCollectionId());
        result.put("groupId", item.getGroupId());
        result.put("name", item.getName());
        result.put("url", item.getUrl());
        result.put("note", item.getNote());
        result.put("sortOrder", item.getSortOrder());
        result.put("createTime", item.getCreateTime());
        result.put("updateTime", item.getUpdateTime());
        return result;
    }
}
