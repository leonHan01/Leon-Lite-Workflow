package com.leon.workflow.service;

import com.leon.workflow.dao.entity.WebCollection;
import com.leon.workflow.dao.entity.WebCollectionGroup;
import com.leon.workflow.dao.entity.WebCollectionItem;
import com.leon.workflow.dao.repository.WebCollectionGroupRepository;
import com.leon.workflow.dao.repository.WebCollectionItemRepository;
import com.leon.workflow.dao.repository.WebCollectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.leon.workflow.service.WebCollectionResponses.*;

import static com.leon.workflow.service.Payloads.clip;
import static com.leon.workflow.service.Payloads.longList;
import static com.leon.workflow.service.Payloads.longValue;
import static com.leon.workflow.service.Payloads.normalizeUrl;
import static com.leon.workflow.service.Payloads.requiredString;
import static com.leon.workflow.service.Payloads.stringValue;

@Service
public class WebCollectionService {
    private final WebCollectionRepository collectionRepository;
    private final WebCollectionGroupRepository groupRepository;
    private final WebCollectionItemRepository itemRepository;

    public WebCollectionService(WebCollectionRepository collectionRepository,
                                WebCollectionGroupRepository groupRepository,
                                WebCollectionItemRepository itemRepository) {
        this.collectionRepository = collectionRepository;
        this.groupRepository = groupRepository;
        this.itemRepository = itemRepository;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listCollections() {
        return listCollections(false);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listCollectionDetails() {
        return listCollections(true);
    }

    private List<Map<String, Object>> listCollections(boolean includeGroups) {
        List<WebCollection> collections = collectionRepository.findByDeletedFalseOrderBySortOrderAscIdAsc();
        if (collections.isEmpty()) return List.of();
        List<Long> ids = collections.stream().map(WebCollection::getId).toList();
        Map<Long, List<WebCollectionGroup>> groups = groupRepository
                .findByCollectionIdInAndDeletedFalseOrderBySortOrderAscIdAsc(ids).stream()
                .collect(Collectors.groupingBy(WebCollectionGroup::getCollectionId));
        Map<Long, List<WebCollectionItem>> items = itemRepository
                .findByCollectionIdInAndDeletedFalseOrderBySortOrderAscIdAsc(ids).stream()
                .collect(Collectors.groupingBy(WebCollectionItem::getCollectionId));
        return collections.stream().map(collection -> {
            List<WebCollectionGroup> collectionGroups = groups.getOrDefault(collection.getId(), List.of());
            List<WebCollectionItem> collectionItems = items.getOrDefault(collection.getId(), List.of());
            return includeGroups ? WebCollectionResponses.toCollectionDetail(collection, collectionGroups, collectionItems)
                    : toCollectionSummary(collection, collectionGroups.size(), collectionItems.size());
        }).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getCollection(Long id) {
        return toCollectionDetail(requireCollection(id));
    }

    @Transactional
    public Map<String, Object> createCollection(Map<String, Object> request) {
        LocalDateTime now = LocalDateTime.now();
        WebCollection collection = new WebCollection();
        collection.setName(requiredString(request.get("name"), "集合名称不能为空"));
        collection.setDescription(stringValue(request.get("description")));
        collection.setIcon(clip(stringValue(request.get("icon")), 64));
        collection.setDeleted(false);
        collection.setSortOrder(nextCollectionOrderFront());
        collection.setCreateTime(now);
        collection.setUpdateTime(now);
        WebCollection saved = collectionRepository.save(collection);
        createDefaultGroup(saved.getId(), now);
        return toCollectionDetail(saved);
    }

    @Transactional
    public Map<String, Object> updateCollection(Long id, Map<String, Object> request) {
        WebCollection collection = requireCollection(id);
        collection.setName(requiredString(request.get("name"), "集合名称不能为空"));
        collection.setDescription(stringValue(request.get("description")));
        if (request.containsKey("icon")) {
            collection.setIcon(clip(stringValue(request.get("icon")), 64));
        }
        collection.setUpdateTime(LocalDateTime.now());
        return toCollectionDetail(collectionRepository.save(collection));
    }

    @Transactional
    public void deleteCollection(Long id) {
        WebCollection collection = requireCollection(id);
        LocalDateTime now = LocalDateTime.now();
        collection.setDeleted(true);
        collection.setUpdateTime(now);
        collectionRepository.save(collection);
        for (WebCollectionGroup group : groupRepository.findByCollectionIdAndDeletedFalseOrderBySortOrderAscIdAsc(id)) {
            group.setDeleted(true);
            group.setUpdateTime(now);
            groupRepository.save(group);
        }
        for (WebCollectionItem item : itemRepository.findByCollectionIdAndDeletedFalseOrderBySortOrderAscIdAsc(id)) {
            item.setDeleted(true);
            item.setUpdateTime(now);
            itemRepository.save(item);
        }
    }

    @Transactional
    public Map<String, Object> createGroup(Long collectionId, Map<String, Object> request) {
        requireCollection(collectionId);
        LocalDateTime now = LocalDateTime.now();
        WebCollectionGroup group = new WebCollectionGroup();
        group.setCollectionId(collectionId);
        group.setName(requiredString(request.get("name"), "分组名称不能为空"));
        group.setSortOrder(nextGroupOrder(collectionId));
        group.setDeleted(false);
        group.setCreateTime(now);
        group.setUpdateTime(now);
        WebCollectionGroup saved = groupRepository.save(group);
        touchCollection(collectionId, now);
        return toGroupResponse(saved, List.of());
    }

    @Transactional
    public Map<String, Object> updateGroup(Long collectionId, Long groupId, Map<String, Object> request) {
        WebCollectionGroup group = requireGroup(collectionId, groupId);
        LocalDateTime now = LocalDateTime.now();
        group.setName(requiredString(request.get("name"), "分组名称不能为空"));
        group.setUpdateTime(now);
        WebCollectionGroup saved = groupRepository.save(group);
        touchCollection(collectionId, now);
        return toGroupResponse(saved, itemRepository.findByGroupIdAndDeletedFalseOrderBySortOrderAscIdAsc(groupId));
    }

    @Transactional
    public void deleteGroup(Long collectionId, Long groupId) {
        WebCollectionGroup group = requireGroup(collectionId, groupId);
        LocalDateTime now = LocalDateTime.now();
        group.setDeleted(true);
        group.setUpdateTime(now);
        groupRepository.save(group);
        for (WebCollectionItem item : itemRepository.findByGroupIdAndDeletedFalseOrderBySortOrderAscIdAsc(groupId)) {
            item.setDeleted(true);
            item.setUpdateTime(now);
            itemRepository.save(item);
        }
        touchCollection(collectionId, now);
    }

    @Transactional
    public void reorderCollections(Map<String, Object> request) {
        List<Long> ids = longList(request.get("collectionIds"));
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < ids.size(); i++) {
            WebCollection collection = requireCollection(ids.get(i));
            collection.setSortOrder(i);
            collection.setUpdateTime(now);
            collectionRepository.save(collection);
        }
    }

    @Transactional
    public void reorderGroups(Long collectionId, Map<String, Object> request) {
        requireCollection(collectionId);
        List<Long> ids = longList(request.get("groupIds"));
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < ids.size(); i++) {
            WebCollectionGroup group = requireGroup(collectionId, ids.get(i));
            group.setSortOrder(i);
            group.setUpdateTime(now);
            groupRepository.save(group);
        }
        touchCollection(collectionId, now);
    }

    @Transactional
    public Map<String, Object> createItem(Long collectionId, Map<String, Object> request) {
        requireCollection(collectionId);
        Long groupId = longValue(request.get("groupId"));
        WebCollectionGroup group = requireGroup(collectionId, groupId);
        LocalDateTime now = LocalDateTime.now();
        WebCollectionItem item = new WebCollectionItem();
        item.setCollectionId(collectionId);
        item.setGroupId(group.getId());
        item.setName(requiredString(request.get("name"), "网站名称不能为空"));
        item.setUrl(normalizeUrl(requiredString(request.get("url"), "网站 URL 不能为空")));
        item.setNote(stringValue(request.get("note")));
        item.setSortOrder(nextItemOrder(group.getId()));
        item.setDeleted(false);
        item.setCreateTime(now);
        item.setUpdateTime(now);
        WebCollectionItem saved = itemRepository.save(item);
        touchCollection(collectionId, now);
        return toItemResponse(saved);
    }

    @Transactional
    public Map<String, Object> updateItem(Long collectionId, Long itemId, Map<String, Object> request) {
        WebCollectionItem item = requireItem(collectionId, itemId);
        Long groupId = longValue(request.get("groupId"));
        requireGroup(collectionId, groupId);
        LocalDateTime now = LocalDateTime.now();
        item.setGroupId(groupId);
        item.setName(requiredString(request.get("name"), "网站名称不能为空"));
        item.setUrl(normalizeUrl(requiredString(request.get("url"), "网站 URL 不能为空")));
        item.setNote(stringValue(request.get("note")));
        item.setUpdateTime(now);
        WebCollectionItem saved = itemRepository.save(item);
        touchCollection(collectionId, now);
        return toItemResponse(saved);
    }

    @Transactional
    public void deleteItem(Long collectionId, Long itemId) {
        WebCollectionItem item = requireItem(collectionId, itemId);
        LocalDateTime now = LocalDateTime.now();
        item.setDeleted(true);
        item.setUpdateTime(now);
        itemRepository.save(item);
        touchCollection(collectionId, now);
    }

    @Transactional
    public void reorderItems(Long collectionId, Long groupId, Map<String, Object> request) {
        requireGroup(collectionId, groupId);
        List<Long> ids = longList(request.get("itemIds"));
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < ids.size(); i++) {
            WebCollectionItem item = requireItem(collectionId, ids.get(i));
            if (!groupId.equals(item.getGroupId())) {
                throw new IllegalArgumentException("网页不属于当前分组: " + ids.get(i));
            }
            item.setSortOrder(i);
            item.setUpdateTime(now);
            itemRepository.save(item);
        }
        touchCollection(collectionId, now);
    }

    private WebCollection requireCollection(Long id) {
        return collectionRepository.findById(id)
                .filter(collection -> !Boolean.TRUE.equals(collection.getDeleted()))
                .orElseThrow(() -> new IllegalArgumentException("网页集合不存在: " + id));
    }

    private WebCollectionGroup requireGroup(Long collectionId, Long groupId) {
        if (groupId == null) {
            throw new IllegalArgumentException("分组不能为空");
        }
        return groupRepository.findById(groupId)
                .filter(group -> collectionId.equals(group.getCollectionId()) && !Boolean.TRUE.equals(group.getDeleted()))
                .orElseThrow(() -> new IllegalArgumentException("分组不存在: " + groupId));
    }

    private WebCollectionItem requireItem(Long collectionId, Long itemId) {
        if (itemId == null) {
            throw new IllegalArgumentException("网页不能为空");
        }
        return itemRepository.findById(itemId)
                .filter(item -> collectionId.equals(item.getCollectionId()) && !Boolean.TRUE.equals(item.getDeleted()))
                .orElseThrow(() -> new IllegalArgumentException("网页不存在: " + itemId));
    }

    private void createDefaultGroup(Long collectionId, LocalDateTime now) {
        WebCollectionGroup group = new WebCollectionGroup();
        group.setCollectionId(collectionId);
        group.setName("默认分组");
        group.setSortOrder(0);
        group.setDeleted(false);
        group.setCreateTime(now);
        group.setUpdateTime(now);
        groupRepository.save(group);
    }

    private void touchCollection(Long collectionId, LocalDateTime now) {
        WebCollection collection = requireCollection(collectionId);
        collection.setUpdateTime(now);
        collectionRepository.save(collection);
    }

    private Integer nextCollectionOrderFront() {
        return collectionRepository.findByDeletedFalseOrderBySortOrderAscIdAsc()
                .stream()
                .map(WebCollection::getSortOrder)
                .filter(order -> order != null)
                .min(Comparator.naturalOrder())
                .map(order -> order - 1)
                .orElse(0);
    }

    private Integer nextGroupOrder(Long collectionId) {
        return groupRepository.findByCollectionIdAndDeletedFalseOrderBySortOrderAscIdAsc(collectionId)
                .stream()
                .map(WebCollectionGroup::getSortOrder)
                .max(Comparator.naturalOrder())
                .orElse(-1) + 1;
    }

    private Integer nextItemOrder(Long groupId) {
        return itemRepository.findByGroupIdAndDeletedFalseOrderBySortOrderAscIdAsc(groupId)
                .stream()
                .map(WebCollectionItem::getSortOrder)
                .max(Comparator.naturalOrder())
                .orElse(-1) + 1;
    }

    private Map<String, Object> toCollectionDetail(WebCollection collection) {
        List<WebCollectionGroup> groups = groupRepository.findByCollectionIdAndDeletedFalseOrderBySortOrderAscIdAsc(collection.getId());
        List<WebCollectionItem> items = itemRepository.findByCollectionIdAndDeletedFalseOrderBySortOrderAscIdAsc(collection.getId());
        return WebCollectionResponses.toCollectionDetail(collection, groups, items);
    }
}
