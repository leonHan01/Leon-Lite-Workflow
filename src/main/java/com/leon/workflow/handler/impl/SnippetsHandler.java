package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.handler.AbstractEventHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.COPY_TO_CLIPBOARD_AND_PASTE;

@Component
@Slf4j
public class SnippetsHandler extends AbstractEventHandler {
    private static String SHOW_ALL_SNIPPETS = "..";

    private static String SHOW_ALL_SNIPPETS_ALIAS = "。。";

    private static String GIT_COMMIT = "git";

    private static String MVN_PACKAGE = "mvn";

    @Override
    public List<AlfredResult> doHandleAlfredEvent(AlfredParam alfredParam) {
        String instruction = alfredParam.getInstruction();
        boolean showAllSnippets = false;
        if (SHOW_ALL_SNIPPETS.equals(instruction) || SHOW_ALL_SNIPPETS_ALIAS.equals(instruction)) {
            showAllSnippets = true;
        }
        if (instruction.startsWith(SHOW_ALL_SNIPPETS)) {
            instruction = instruction.replace(".", "");
        }
        if (instruction.startsWith(SHOW_ALL_SNIPPETS_ALIAS)) {
            instruction = instruction.replace("。", "");
        }
        List<AlfredResult> results = new ArrayList<>();

        if (GIT_COMMIT.startsWith(instruction) || showAllSnippets) {
            results.addAll(gitCommitCmd(alfredParam));
        }
        if (MVN_PACKAGE.startsWith(instruction) || showAllSnippets) {
            results.addAll(mvnPackage());
        }

        return results;
    }

    private List<AlfredResult> gitCommitCmd(AlfredParam alfredParam) {
        List<AlfredResult> results = new ArrayList<>();
        String param = alfredParam.getParamsWithoutInstruction();
        StringBuilder cmd = new StringBuilder();
        cmd.append("git add -A").append("\n");
        if (StringUtils.isNotBlank(param)) {
            cmd.append(" git commit -m '" + param + "' ").append("\n");
        } else {
            cmd.append(" git commit -m 'bug fix'").append("\n");
        }
        cmd.append(" git push").append("\n");
        String subTitle = cmd.toString();

        results.add(AlfredResult.builder().title("git commit & push").msgNeedToCopy(cmd.toString())
                        .subtitle(subTitle.replace("\n", "&"))
                        .autoComplete(GIT_COMMIT).icon("git.png")
                        .type(COPY_TO_CLIPBOARD_AND_PASTE).build());
        return results;
    }

    private List<AlfredResult> mvnPackage() {
        List<AlfredResult> results = new ArrayList<>();
        StringBuilder cmd = new StringBuilder();
        String cmdStr = "mvn clean install -Dmaven.test.skip=true";
        cmd.append(cmdStr).append("\n");

        results.add(AlfredResult.builder().title("mvn package").msgNeedToCopy(cmd.toString()).subtitle(cmdStr)
                .icon("maven.png").autoComplete(MVN_PACKAGE).type(COPY_TO_CLIPBOARD_AND_PASTE).build());

        return results;
    }

    @Override
    public Boolean matchEvent(AlfredParam param, Boolean alreadyHaveResult) {
        String instruction = param.getInstruction();
        if (instruction.startsWith(SHOW_ALL_SNIPPETS) || param.getInstruction().startsWith(SHOW_ALL_SNIPPETS_ALIAS)) {
            return true;
        }

        if (GIT_COMMIT.startsWith(instruction) || MVN_PACKAGE.startsWith(instruction)) {
            return true;
        }
        return false;
    }
}
