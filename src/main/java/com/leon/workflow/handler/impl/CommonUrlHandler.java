package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.bo.AlfredCommonUrl;
import com.leon.workflow.domain.bo.UrlTag;
import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.handler.AbstractEventHandler;
import com.leon.workflow.util.diamond.AlfredDiamondService;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.OPEN_URL;
import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.RUN_SCRIPT;

@Component
public class CommonUrlHandler extends AbstractEventHandler {

    @Resource
    private AlfredDiamondService alfredDiamondService;


    @Override
    public List<AlfredResult> doHandleAlfredEvent(AlfredParam alfredParam) {
        List<AlfredCommonUrl> commonUrls = alfredDiamondService.getCommonUrlList();
        List<AlfredResult> results = new ArrayList<>();

        if (alfredParam.getInstruction().equals("tag") || alfredParam.getInstruction().equals("tags")) {
            List<UrlTag> tags = alfredDiamondService.getTags();
            for (UrlTag tag : tags) {
                AlfredResult alfredResult = AlfredResult.builder().autoComplete(tag.getTag())
                        .type(RUN_SCRIPT).title("[" + tag.getTag() + "] 书签数:" + tag.getCount())
                        .icon("link.png").subtitle("回车查找 tag 下的链接").build();

                results.add(alfredResult);
            }
            return results;
        }

        List<List<AlfredCommonUrl>> filterResultList = new ArrayList<>();
        for (String param : alfredParam.getParams()) {
            List<AlfredCommonUrl> filterResult = filterByParam(commonUrls, param);
            if (CollectionUtils.isEmpty(filterResult)) {
                return new ArrayList<>();
            }
            filterResultList.add(filterResult);
        }

        List<AlfredCommonUrl> filterResult = filterResultList.get(0);
        for (int i = 1; i <= filterResultList.size() - 1; i++) {
            List<AlfredCommonUrl> nextFilterResult = filterResultList.get(i);
            filterResult.retainAll(nextFilterResult);
        }
        Set<String> dump = new HashSet<>();
        for (AlfredCommonUrl commonUrl : filterResult) {
            if (dump.contains(commonUrl.getTitle())) {
                continue;
            }
            dump.add(commonUrl.getTitle());
            AlfredResult alfredResult = AlfredResult.builder().uri(commonUrl.getUrl())
                    .type(OPEN_URL).title(commonUrl.getTitle() + " 🔗").icon("link.png").subtitle(commonUrl.getTags().toString())
                    .build();
            results.add(alfredResult);
        }
        return results;
    }

    private List<AlfredCommonUrl> filterByParam(List<AlfredCommonUrl> commonUrls, String param) {
        if (commonUrls == null || CollectionUtils.isEmpty(commonUrls)) {
            return new ArrayList<>();
        }
        List<AlfredCommonUrl> filterResult = new ArrayList<>();
        for (AlfredCommonUrl commonUrl : commonUrls) {
            if (commonUrl.getTitle().toLowerCase().startsWith(param.toLowerCase())) {
                filterResult.add(commonUrl);
            }
            if (commonUrl.getPinyinList().stream().anyMatch(p -> p.toLowerCase().startsWith(param.toLowerCase()))) {
                filterResult.add(commonUrl);
            }
            if (CollectionUtils.isNotEmpty(commonUrl.getTags())) {
                for (String tag : commonUrl.getTags()) {
                    if (tag.toLowerCase().startsWith(param.toLowerCase())) {
                        filterResult.add(commonUrl);
                        break;
                    }
                }
            }
        }
        return filterResult;
    }

    @Override
    public Boolean matchEvent(AlfredParam param, Boolean alreadyHaveResult) {
        return true;
    }
}
