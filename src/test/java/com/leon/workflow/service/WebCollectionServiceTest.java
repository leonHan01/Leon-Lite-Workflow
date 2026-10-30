package com.leon.workflow.service;

import com.leon.workflow.dao.entity.WebCollection;
import com.leon.workflow.dao.entity.WebCollectionGroup;
import com.leon.workflow.dao.repository.WebCollectionGroupRepository;
import com.leon.workflow.dao.repository.WebCollectionItemRepository;
import com.leon.workflow.dao.repository.WebCollectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.leon.workflow.dao.entity.WebCollectionItem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebCollectionServiceTest {
    @Mock
    private WebCollectionRepository collectionRepository;
    @Mock
    private WebCollectionGroupRepository groupRepository;
    @Mock
    private WebCollectionItemRepository itemRepository;

    @InjectMocks
    private WebCollectionService webCollectionService;

    @Test
    void createCollectionPersistsIconAndDefaultGroup() {
        when(collectionRepository.findByDeletedFalseOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        when(collectionRepository.save(any())).thenAnswer(invocation -> {
            WebCollection collection = invocation.getArgument(0);
            collection.setId(3L);
            return collection;
        });
        WebCollectionGroup group = new WebCollectionGroup();
        group.setId(1L);
        group.setName("默认分组");
        when(groupRepository.findByCollectionIdAndDeletedFalseOrderBySortOrderAscIdAsc(3L)).thenReturn(List.of(group));
        when(itemRepository.findByCollectionIdAndDeletedFalseOrderBySortOrderAscIdAsc(3L)).thenReturn(List.of());

        Map<String, Object> created = webCollectionService.createCollection(Map.of(
                "name", "科技动态",
                "description", "资讯",
                "icon", "newspaper"
        ));

        assertEquals("科技动态", created.get("name"));
        assertEquals("newspaper", created.get("icon"));
        verify(groupRepository).save(any(WebCollectionGroup.class));
    }

    @Test
    void updateCollectionChangesIcon() {
        WebCollection collection = new WebCollection();
        collection.setId(3L);
        collection.setName("科技动态");
        collection.setDeleted(false);
        when(collectionRepository.findById(3L)).thenReturn(Optional.of(collection));
        when(collectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(groupRepository.findByCollectionIdAndDeletedFalseOrderBySortOrderAscIdAsc(3L)).thenReturn(List.of());
        when(itemRepository.findByCollectionIdAndDeletedFalseOrderBySortOrderAscIdAsc(3L)).thenReturn(List.of());

        Map<String, Object> updated = webCollectionService.updateCollection(3L, Map.of(
                "name", "科技动态",
                "description", "",
                "icon", "rocket"
        ));

        assertEquals("rocket", updated.get("icon"));
        verify(collectionRepository).save(eq(collection));
    }

    @Test
    void deleteCollectionSoftDeletesGroupsAndItems() {
        WebCollection collection = liveCollection(3L);
        WebCollectionGroup group = liveGroup(1L, 3L);
        WebCollectionItem item = liveItem(9L, 3L, 1L, "https://a.com");
        when(collectionRepository.findById(3L)).thenReturn(Optional.of(collection));
        when(groupRepository.findByCollectionIdAndDeletedFalseOrderBySortOrderAscIdAsc(3L)).thenReturn(List.of(group));
        when(itemRepository.findByCollectionIdAndDeletedFalseOrderBySortOrderAscIdAsc(3L)).thenReturn(List.of(item));

        webCollectionService.deleteCollection(3L);

        assertTrue(collection.getDeleted());
        assertTrue(group.getDeleted());
        assertTrue(item.getDeleted());
    }

    @Test
    void createGroupAndItemNormalizeUrl() {
        WebCollection collection = liveCollection(3L);
        when(collectionRepository.findById(3L)).thenReturn(Optional.of(collection));
        when(groupRepository.findByCollectionIdAndDeletedFalseOrderBySortOrderAscIdAsc(3L)).thenReturn(List.of());
        when(groupRepository.save(any())).thenAnswer(invocation -> {
            WebCollectionGroup group = invocation.getArgument(0);
            group.setId(2L);
            return group;
        });

        Map<String, Object> group = webCollectionService.createGroup(3L, Map.of("name", "文档"));
        assertEquals("文档", group.get("name"));
        assertEquals(3L, group.get("collectionId"));

        WebCollectionGroup savedGroup = liveGroup(2L, 3L);
        when(groupRepository.findById(2L)).thenReturn(Optional.of(savedGroup));
        when(itemRepository.findByGroupIdAndDeletedFalseOrderBySortOrderAscIdAsc(2L)).thenReturn(List.of());
        when(itemRepository.save(any())).thenAnswer(invocation -> {
            WebCollectionItem item = invocation.getArgument(0);
            item.setId(9L);
            return item;
        });

        Map<String, Object> created = webCollectionService.createItem(3L, Map.of(
                "groupId", 2L,
                "name", "飞书",
                "url", "www.feishu.cn",
                "note", "文档"
        ));
        assertEquals("https://www.feishu.cn", created.get("url"));
        assertEquals("飞书", created.get("name"));
    }

    @Test
    void reorderItemsRejectsItemFromAnotherGroup() {
        WebCollectionGroup group = liveGroup(1L, 3L);
        WebCollectionItem item = liveItem(9L, 3L, 2L, "https://a.com");
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
        when(itemRepository.findById(9L)).thenReturn(Optional.of(item));

        assertThrows(IllegalArgumentException.class, () ->
                webCollectionService.reorderItems(3L, 1L, Map.of("itemIds", List.of(9L))));
    }

    @Test
    void missingCollectionThrows() {
        when(collectionRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> webCollectionService.getCollection(99L));
    }

    @Test
    void listCollectionsMapsSummaries() {
        WebCollection collection = liveCollection(3L);
        when(collectionRepository.findByDeletedFalseOrderBySortOrderAscIdAsc()).thenReturn(List.of(collection));
        when(groupRepository.findByCollectionIdInAndDeletedFalseOrderBySortOrderAscIdAsc(List.of(3L))).thenReturn(List.of());
        when(itemRepository.findByCollectionIdInAndDeletedFalseOrderBySortOrderAscIdAsc(List.of(3L))).thenReturn(List.of());

        List<Map<String, Object>> list = webCollectionService.listCollections();
        assertEquals(1, list.size());
        assertEquals("集合", list.get(0).get("name"));
        assertEquals(0, list.get(0).get("itemCount"));
    }

    @Test
    void listCollectionDetailsIncludesGroups() {
        WebCollection collection = liveCollection(3L);
        WebCollectionGroup group = liveGroup(1L, 3L);
        WebCollectionItem item = liveItem(9L, 3L, 1L, "https://a.com");
        when(collectionRepository.findByDeletedFalseOrderBySortOrderAscIdAsc()).thenReturn(List.of(collection));
        when(groupRepository.findByCollectionIdInAndDeletedFalseOrderBySortOrderAscIdAsc(List.of(3L))).thenReturn(List.of(group));
        when(itemRepository.findByCollectionIdInAndDeletedFalseOrderBySortOrderAscIdAsc(List.of(3L))).thenReturn(List.of(item));

        List<Map<String, Object>> list = webCollectionService.listCollectionDetails();
        assertEquals(1, list.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) list.get(0).get("groups");
        assertEquals("默认分组", groups.get(0).get("name"));
        assertEquals(1, ((List<?>) groups.get(0).get("items")).size());
    }

    @Test
    void updateCollectionKeepsIconWhenOmitted() {
        WebCollection collection = liveCollection(3L);
        collection.setIcon("newspaper");
        when(collectionRepository.findById(3L)).thenReturn(Optional.of(collection));
        when(collectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(groupRepository.findByCollectionIdAndDeletedFalseOrderBySortOrderAscIdAsc(3L)).thenReturn(List.of());
        when(itemRepository.findByCollectionIdAndDeletedFalseOrderBySortOrderAscIdAsc(3L)).thenReturn(List.of());

        Map<String, Object> updated = webCollectionService.updateCollection(3L, Map.of(
                "name", "科技动态",
                "description", "资讯"
        ));

        assertEquals("newspaper", updated.get("icon"));
    }

    @Test
    void createCollectionRejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> webCollectionService.createCollection(Map.of("name", "  ")));
    }

    @Test
    void updateAndDeleteGroup() {
        WebCollection collection = liveCollection(3L);
        WebCollectionGroup group = liveGroup(2L, 3L);
        WebCollectionItem item = liveItem(9L, 3L, 2L, "https://a.com");
        when(collectionRepository.findById(3L)).thenReturn(Optional.of(collection));
        when(groupRepository.findById(2L)).thenReturn(Optional.of(group));
        when(groupRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(itemRepository.findByGroupIdAndDeletedFalseOrderBySortOrderAscIdAsc(2L)).thenReturn(List.of(item));

        Map<String, Object> updated = webCollectionService.updateGroup(3L, 2L, Map.of("name", "文档"));
        assertEquals("文档", updated.get("name"));

        webCollectionService.deleteGroup(3L, 2L);
        assertTrue(group.getDeleted());
        assertTrue(item.getDeleted());
    }

    @Test
    void updateAndDeleteItem() {
        WebCollection collection = liveCollection(3L);
        WebCollectionGroup group = liveGroup(2L, 3L);
        WebCollectionItem item = liveItem(9L, 3L, 1L, "https://a.com");
        when(collectionRepository.findById(3L)).thenReturn(Optional.of(collection));
        when(groupRepository.findById(2L)).thenReturn(Optional.of(group));
        when(itemRepository.findById(9L)).thenReturn(Optional.of(item));
        when(itemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> updated = webCollectionService.updateItem(3L, 9L, Map.of(
                "groupId", 2L,
                "name", "飞书",
                "url", "https://www.feishu.cn",
                "note", "文档"
        ));
        assertEquals(2L, updated.get("groupId"));
        assertEquals("飞书", updated.get("name"));

        webCollectionService.deleteItem(3L, 9L);
        assertTrue(item.getDeleted());
    }

    @Test
    void reorderCollectionsAndGroups() {
        WebCollection first = liveCollection(1L);
        WebCollection second = liveCollection(2L);
        WebCollectionGroup groupA = liveGroup(10L, 1L);
        WebCollectionGroup groupB = liveGroup(11L, 1L);
        when(collectionRepository.findById(1L)).thenReturn(Optional.of(first));
        when(collectionRepository.findById(2L)).thenReturn(Optional.of(second));
        when(groupRepository.findById(10L)).thenReturn(Optional.of(groupA));
        when(groupRepository.findById(11L)).thenReturn(Optional.of(groupB));

        webCollectionService.reorderCollections(Map.of("collectionIds", List.of(2L, 1L)));
        assertEquals(0, second.getSortOrder());
        assertEquals(1, first.getSortOrder());

        webCollectionService.reorderGroups(1L, Map.of("groupIds", List.of(11L, 10L)));
        assertEquals(0, groupB.getSortOrder());
        assertEquals(1, groupA.getSortOrder());
    }

    @Test
    void reorderItemsUpdatesSortOrder() {
        WebCollection collection = liveCollection(3L);
        WebCollectionGroup group = liveGroup(1L, 3L);
        WebCollectionItem first = liveItem(8L, 3L, 1L, "https://a.com");
        WebCollectionItem second = liveItem(9L, 3L, 1L, "https://b.com");
        when(collectionRepository.findById(3L)).thenReturn(Optional.of(collection));
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
        when(itemRepository.findById(8L)).thenReturn(Optional.of(first));
        when(itemRepository.findById(9L)).thenReturn(Optional.of(second));

        webCollectionService.reorderItems(3L, 1L, Map.of("itemIds", List.of(9L, 8L)));
        assertEquals(0, second.getSortOrder());
        assertEquals(1, first.getSortOrder());
    }

    @Test
    void createItemRequiresGroup() {
        WebCollection collection = liveCollection(3L);
        when(collectionRepository.findById(3L)).thenReturn(Optional.of(collection));
        assertThrows(IllegalArgumentException.class, () ->
                webCollectionService.createItem(3L, Map.of("name", "飞书", "url", "https://www.feishu.cn")));
    }

    @Test
    void deletedCollectionIsMissing() {
        WebCollection collection = liveCollection(3L);
        collection.setDeleted(true);
        when(collectionRepository.findById(3L)).thenReturn(Optional.of(collection));
        assertThrows(IllegalArgumentException.class, () -> webCollectionService.getCollection(3L));
    }

    @Test
    void bulkDetailsKeepCollectionAndGroupOwnershipWithOneReadPerTable() {
        when(collectionRepository.findByDeletedFalseOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(liveCollection(3L), liveCollection(4L)));
        when(groupRepository.findByCollectionIdInAndDeletedFalseOrderBySortOrderAscIdAsc(List.of(3L, 4L)))
                .thenReturn(List.of(liveGroup(10L, 3L), liveGroup(11L, 3L), liveGroup(20L, 4L)));
        when(itemRepository.findByCollectionIdInAndDeletedFalseOrderBySortOrderAscIdAsc(List.of(3L, 4L)))
                .thenReturn(List.of(liveItem(1L, 3L, 11L, "https://first.example"),
                        liveItem(2L, 4L, 20L, "https://other.example"), liveItem(3L, 3L, 11L, "https://last.example")));

        List<Map<String, Object>> details = webCollectionService.listCollectionDetails();
        assertEquals(List.of(3L, 4L), details.stream().map(row -> row.get("id")).toList());
        assertEquals(2, details.getFirst().get("groupCount"));
        assertEquals(2, details.getFirst().get("itemCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) details.getFirst().get("groups");
        assertEquals(List.of(10L, 11L), groups.stream().map(row -> row.get("id")).toList());
        assertTrue(((List<?>) groups.getFirst().get("items")).isEmpty());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) groups.get(1).get("items");
        assertEquals(List.of(1L, 3L), items.stream().map(row -> row.get("id")).toList());
        verify(collectionRepository).findByDeletedFalseOrderBySortOrderAscIdAsc();
        verify(groupRepository).findByCollectionIdInAndDeletedFalseOrderBySortOrderAscIdAsc(List.of(3L, 4L));
        verify(itemRepository).findByCollectionIdInAndDeletedFalseOrderBySortOrderAscIdAsc(List.of(3L, 4L));
        org.mockito.Mockito.verifyNoMoreInteractions(collectionRepository, groupRepository, itemRepository);
    }

    private WebCollection liveCollection(long id) {
        WebCollection collection = new WebCollection();
        collection.setId(id);
        collection.setName("集合");
        collection.setDeleted(false);
        collection.setSortOrder(0);
        return collection;
    }

    private WebCollectionGroup liveGroup(long id, long collectionId) {
        WebCollectionGroup group = new WebCollectionGroup();
        group.setId(id);
        group.setCollectionId(collectionId);
        group.setName("默认分组");
        group.setDeleted(false);
        group.setSortOrder(0);
        return group;
    }

    private WebCollectionItem liveItem(long id, long collectionId, long groupId, String url) {
        WebCollectionItem item = new WebCollectionItem();
        item.setId(id);
        item.setCollectionId(collectionId);
        item.setGroupId(groupId);
        item.setName("页");
        item.setUrl(url);
        item.setDeleted(false);
        item.setSortOrder(0);
        return item;
    }
}
