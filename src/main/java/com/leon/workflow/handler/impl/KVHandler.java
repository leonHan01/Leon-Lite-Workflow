package com.leon.workflow.handler.impl;

import com.leon.workflow.constant.KVCmd;
import com.leon.workflow.domain.bo.AlfredKV;
import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.handler.AbstractEventHandler;
import com.leon.workflow.util.diamond.AlfredDiamondService;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.*;

// 格式介绍：Key：必填 value选填，<url>代表要跳转的url，用<>分割，[tag1] 代表标签，用[]分割, {cmd1}代表额外拓展命令，用 {} 分割
@Component
public class KVHandler extends AbstractEventHandler {

    private final AlfredDiamondService alfredDiamondService;

    public KVHandler(AlfredDiamondService alfredDiamondService) {
        this.alfredDiamondService = alfredDiamondService;
    }

    @Override
    public List<AlfredResult> doHandleAlfredEvent(AlfredParam alfredParam) {
        List<AlfredResult> results = new ArrayList<>();
        List<AlfredKV> alfredKVList = alfredDiamondService.getAlfredKVList();
        List<String> params = alfredParam.getParams();
        List<AlfredKV> tempKV = alfredKVList;
        for (String param : params) {
            tempKV = filter(tempKV, param);
        }

        for (AlfredKV alfredKV : tempKV) {
            results.add(assembleAlfredResult(alfredKV));
        }
        return results;
    }

    private List<AlfredKV> filter(List<AlfredKV> alfredKVList, String param) {
        if (CollectionUtils.isEmpty(alfredKVList)) {
            return new ArrayList<>();
        }
        List<AlfredKV> results = new ArrayList<>();
        for (AlfredKV alfredKV : alfredKVList) {
            if (meetTagsAndPinyin(alfredKV, param.toLowerCase())) {
                results.add(alfredKV);
            }
        }
        return results;
    }

    private AlfredResult assembleAlfredResult(AlfredKV alfredKV) {
        AlfredResult result = AlfredResult.builder().uri(alfredKV.getUrl()).icon(alfredKV.getIcon()).build();
        if (StringUtils.isBlank(alfredKV.getValue())) {
            result.setTitle(alfredKV.getKey());
        } else {
            result.setTitle(alfredKV.getKey() + ":" + alfredKV.getValue());
        }
        if (StringUtils.isNotBlank(result.getUri())) {
            result.setType(OPEN_URL);
        } else {
            if (StringUtils.isNotBlank(alfredKV.getValue())) {
                result.setMsgNeedToCopy(alfredKV.getValue().trim());
            } else {
                result.setMsgNeedToCopy(alfredKV.getKey());
            }
            result.setType(COPY_TO_CLIPBOARD);
        }

        if (CollectionUtils.isNotEmpty(alfredKV.getCmds())) {
            for (String cmd : alfredKV.getCmds()) {
                if (cmd.equals(KVCmd.PASTE)) {
                    result.setType(COPY_TO_CLIPBOARD_AND_PASTE);
                    continue;
                }
                if (cmd.endsWith(KVCmd.ICON)) {
                    result.setIcon(cmd);
                    continue;
                }
            }
        }
        if (CollectionUtils.isNotEmpty(alfredKV.getTags())) {
            result.setSubtitle(alfredKV.getTags().toString());
        }
        return result;
    }

    private boolean meetTagsAndPinyin(AlfredKV alfredKV, String lowerCase) {
        List<String> tags = alfredKV.getTags();
        List<String> pinyinList = alfredKV.getPinyin();
        if (CollectionUtils.isEmpty(tags)) {
            return false;
        }
        for (String tag : tags) {
            if (tag.toLowerCase().startsWith(lowerCase)) {
                return true;
            }
        }
        for (String pinyin : pinyinList) {
            if (pinyin.startsWith(lowerCase)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Boolean matchEvent(AlfredParam param, Boolean alreadyHaveResult) {
        return true;
    }
}
