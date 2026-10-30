package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.handler.AbstractEventHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.COPY_TO_CLIPBOARD;
import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.COPY_TO_CLIPBOARD_AND_PASTE;

@Component
public class LeetCodeNameHandler extends AbstractEventHandler {
    @Override
    public List<AlfredResult> doHandleAlfredEvent(AlfredParam alfredParam) {
        List<AlfredResult> results = new ArrayList<>();

        if (alfredParam.getOriginParams().contains("leetcode")) {
            String[] paramArray1 = alfredParam.getOriginParams().toLowerCase().split("/");
            String[] paramArray2 = paramArray1[4].split("-");
            StringBuilder title = new StringBuilder();
            for (int i = 0; i < paramArray2.length; i++) {
                String name = paramArray2[i];
                title.append(name.substring(0, 1).toUpperCase() + name.substring(1));
            }
            results.add(AlfredResult.builder()
                    .msgNeedToCopy(title.toString()).title(title.toString()).subtitle("leetcode")
                    .type(COPY_TO_CLIPBOARD_AND_PASTE).build());
        } else if (alfredParam.getOriginParams().contains("-")) {
            String[] paramArray = alfredParam.getOriginParams().toLowerCase().split("-");
            StringBuilder title = new StringBuilder();
            for (int i = 0; i < paramArray.length; i++) {
                String name = paramArray[i];
                title.append(name.substring(0, 1).toUpperCase() + name.substring(1));
            }
            results.add(AlfredResult.builder()
                    .msgNeedToCopy(title.toString()).title(title.toString()).subtitle("leetcode")
                    .type(COPY_TO_CLIPBOARD_AND_PASTE).build());
        }


        return results;
    }

    public static void main(String[] args) {
        String work = "https://leetcode.cn/problems/sort-list/description/";

    }

    @Override
    public Boolean matchEvent(AlfredParam param, Boolean alreadyHaveResult) {
        if (param.getOriginParams().contains("leetcode")) {
            return true;
        }
        if (param.getOriginParams().contains("-")) {
            return true;
        }
        return false;
    }
}
