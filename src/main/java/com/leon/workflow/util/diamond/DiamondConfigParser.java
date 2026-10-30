package com.leon.workflow.util.diamond;

import com.alibaba.fastjson.JSON;
import com.github.promeg.pinyinhelper.Pinyin;
import com.leon.workflow.dao.entity.LeonDiamond;
import com.leon.workflow.domain.bo.AlfredCommonUrl;
import com.leon.workflow.domain.bo.AlfredKV;
import com.leon.workflow.domain.bo.UrlTag;
import com.leon.workflow.util.PinyinUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.*;

/** Parses stored configuration without accessing persistence or publishing cache state. */
@Component
public class DiamondConfigParser {
    public record UrlConfig(List<AlfredCommonUrl> urls, List<UrlTag> tags) {}

    /**
     * kv 格式： key: value<url>
     */
    public List<AlfredKV> parseKeyValues(List<LeonDiamond> kvDiamondListTmp) {
        if (CollectionUtils.isEmpty(kvDiamondListTmp)) {
            return List.of();
        }

        List<AlfredKV> alfredKVList = new ArrayList<>();
        for (LeonDiamond kvDiamond : kvDiamondListTmp) {
            if (kvDiamond.getDiamondValue() == null) continue;
            String[] kvArray = kvDiamond.getDiamondValue().split("\\n");
            String secondDiamondTitle = null;
            String secondDefaultIcon = null;
            for (String kv : kvArray) {
                if (kv.startsWith("===-二级菜单:")) {
                    String[] array = kv.split("-");
                    secondDiamondTitle = array[1];
                    if (array.length >= 3) {
                        secondDefaultIcon = array[2];
                    } else {
                        secondDefaultIcon = null;
                    }
                }
                if (kv.startsWith("===") || StringUtils.isBlank(kv)) {
                    continue;
                }
                if (!kv.contains(":")) {
                    throw new IllegalArgumentException("KV 格式错误");
                }
                AlfredKV alfredKV = new AlfredKV();
                alfredKV.setFirstDiamond(kvDiamond.getDiamondKey());
                alfredKV.setSecondDiamond(secondDiamondTitle);
                alfredKV.setIcon(kvDiamond.getDefaultIcon());
                if (StringUtils.isNotBlank(secondDefaultIcon)) {
                    alfredKV.setIcon(secondDefaultIcon);
                }

                int split = kv.indexOf(":");
                String key = kv.substring(0, split);
                String value = kv.substring(split + 1);
                alfredKV.setKey(key);
                if (value.contains("<") && value.contains(">")) {
                    alfredKV.setUrl(section(value, '<', '>'));
                }

                if (value.contains("[") && value.contains("]")) {
                    String tagStr = section(value, '[', ']');
                    String[] tagArray = tagStr.split(",");
                    for (String tag : tagArray) {
                        alfredKV.getTags().add(tag.trim());
                    }
                }
                if (StringUtils.isNotBlank(secondDiamondTitle)) {
                    alfredKV.getTags().add(secondDiamondTitle);
                }
                alfredKV.getTags().add(kvDiamond.getDiamondKey());
                if (StringUtils.isNotEmpty(kvDiamond.getDefaultTags())) {
                    String[] tagArray = kvDiamond.getDefaultTags().split(",");
                    for (String tag : tagArray) {
                        alfredKV.getTags().add(tag.trim());
                    }
                }

                if (value.contains("{") && value.contains("}")) {
                    String tagStr = section(value, '{', '}');
                    String[] cmdArray = tagStr.split(",");
                    for (String cmd : cmdArray) {
                        alfredKV.getCmds().add(cmd.trim());
                    }
                }

                if (value.contains("[") || value.contains("<") || value.contains("{")) {
                    int urlStart = value.indexOf("<") == -1 ? value.length() : value.indexOf("<");
                    int tagStart = value.indexOf("[") == -1 ? value.length() : value.indexOf("[");
                    int cmdStart = value.indexOf("{") == -1 ? value.length() : value.indexOf("{");
                    int valueEnd = min(urlStart, tagStart, cmdStart);
                    alfredKV.setValue(value.substring(0, valueEnd));
                } else {
                    alfredKV.setValue(value);
                }

                alfredKV.getTags().add(alfredKV.getKey());
                if (StringUtils.isNotBlank(alfredKV.getValue())) {
                    alfredKV.getTags().add(alfredKV.getValue());
                }
                List<String> pinyin = getPinyin(alfredKV.getTags());
                alfredKV.setPinyin(pinyin);
                alfredKVList.add(alfredKV);
            }
        }
        return List.copyOf(alfredKVList);
    }

    private List<String> getPinyin(List<String> list) {
        if (CollectionUtils.isEmpty(list)) {
            return new ArrayList<>();
        }
        List<String> pinyinList = new ArrayList<>();
        for (String s : list) {
            String titlePinyin = PinyinUtil.getPinyin(s);
            if (StringUtils.isBlank(titlePinyin) || titlePinyin.equals(s)) {
                continue;
            }
            pinyinList.add(titlePinyin);
            List<String> abb = getAbbreviation(s);
            if (CollectionUtils.isNotEmpty(abb)) {
                pinyinList.addAll(abb);
            }
        }
        return pinyinList;
    }

    private List<String> getAbbreviation(String s) {
        String titlePinyin = Pinyin.toPinyin(s, ",").toLowerCase();
        if (StringUtils.isBlank(titlePinyin)) {
            return new ArrayList<>();
        }
        String[] pinyinArray = titlePinyin.split(",");
        StringBuilder pinyinTitle = new StringBuilder();
        StringBuilder pinyinTitleWithFirstPinyinComplete = new StringBuilder();

        int index = 0;
        for (String pinyin : pinyinArray) {
            if (pinyin.isEmpty()) continue;
            pinyinTitle.append(pinyin.charAt(0));
            if (index == 0) {
                // 首个汉字拼音允许全拼，增加提示
                pinyinTitleWithFirstPinyinComplete.append(pinyin);
            } else {
                pinyinTitleWithFirstPinyinComplete.append(pinyin.charAt(0));
            }
            index++;
        }
        List<String> result = new ArrayList<>();
        result.add(pinyinTitle.toString());
        result.add(pinyinTitleWithFirstPinyinComplete.toString());
        return result;
    }

    private int min(int a, int b, int c) {
        int min = Math.min(a, b);
        return Math.min(min, c);
    }

    private String section(String value, char open, char close) {
        int start = value.indexOf(open);
        int end = value.indexOf(close);
        if (end <= start) throw new IllegalArgumentException("KV 格式错误");
        return value.substring(start + 1, end);
    }

    public UrlConfig parseUrls(String value) {
        if (value == null) return new UrlConfig(List.of(), List.of());
        List<AlfredCommonUrl> urls;
        try {
            urls = JSON.parseArray(value, AlfredCommonUrl.class);
            if (urls == null || urls.stream().anyMatch(url -> url == null || url.getName() == null || url.getName().isBlank())) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("WebUrlDiamond 必须是包含 name 的 JSON 数组");
        }
        Map<String, Integer> tagMap = new HashMap<>();
        for (AlfredCommonUrl url : urls) {
            String[] urlArray = url.getName().trim().split(" ");
            String title = urlArray[0];
            url.setTitle(title);

            String titlePinyin = PinyinUtil.getPinyin(title);
            url.getTags().add(title);
            if (!titlePinyin.equals(title)) {
                url.getPinyinList().add(titlePinyin);
            }
            List<String> titleAbb = getAbbreviation(title);
            if (CollectionUtils.isNotEmpty(titleAbb)) {
                url.getPinyinList().addAll(titleAbb);
            }

            if (urlArray.length >= 2) {
                for (int i = 1; i < urlArray.length; i++) {
                    String tag = urlArray[i].toLowerCase();
                    String pinyin = PinyinUtil.getPinyin(tag);
                    if (!pinyin.equals(tag)) {
                        url.getPinyinList().add(pinyin);
                    }
                    List<String> abb = getAbbreviation(tag);
                    if (CollectionUtils.isNotEmpty(abb)) {
                        url.getPinyinList().addAll(abb);
                    }
                    if (tagMap.containsKey(tag)) {
                        tagMap.put(tag, tagMap.get(tag) + 1);
                    } else {
                        tagMap.put(tag, 1);
                    }
                    url.getTags().add(urlArray[i].toLowerCase());
                }
            }
        }
        List<UrlTag> tagList = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : tagMap.entrySet()) {
            UrlTag urlTag = new UrlTag();
            urlTag.setTag(entry.getKey());
            urlTag.setCount(entry.getValue());
            tagList.add(urlTag);
        }

        tagList.sort((o1, o2) -> o2.getCount().compareTo(o1.getCount()));
        return new UrlConfig(List.copyOf(urls), List.copyOf(tagList));
    }


}
