package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnippetsHandlerTest {

    private final SnippetsHandler handler = new SnippetsHandler();

    @Test
    void gitUsesTheGivenMessageAndMvnStaysSeparate() {
        List<AlfredResult> git = handler.doHandleAlfredEvent(param("git 修复"));
        List<AlfredResult> mvn = handler.doHandleAlfredEvent(param("mvn"));

        assertEquals(1, git.size());
        assertEquals("git commit & push", git.getFirst().getTitle());
        assertEquals("git add -A\n git commit -m '修复' \n git push\n", git.getFirst().getMsgNeedToCopy());
        assertEquals("clipboardAndPaste", git.getFirst().getType());
        assertEquals("git.png", git.getFirst().getIcon());
        assertEquals("mvn package", mvn.getFirst().getTitle());
        assertEquals("mvn clean install -Dmaven.test.skip=true\n", mvn.getFirst().getMsgNeedToCopy());
        assertEquals("maven.png", mvn.getFirst().getIcon());
    }

    @Test
    void defaultGitMessageAndBothSnippetCommands() {
        assertEquals("git add -A\n git commit -m 'bug fix'\n git push\n",
                handler.doHandleAlfredEvent(param("g")).getFirst().getMsgNeedToCopy());

        List<AlfredResult> all = handler.doHandleAlfredEvent(param(".."));
        List<AlfredResult> alias = handler.doHandleAlfredEvent(param("。。"));

        assertEquals(List.of("git commit & push", "mvn package"), all.stream().map(AlfredResult::getTitle).toList());
        assertEquals(all.stream().map(AlfredResult::getMsgNeedToCopy).toList(),
                alias.stream().map(AlfredResult::getMsgNeedToCopy).toList());
    }

    @Test
    void matchesOnlySnippetPrefixes() {
        assertTrue(handler.matchEvent(param("gi"), false));
        assertTrue(handler.matchEvent(param("m"), false));
        assertTrue(handler.matchEvent(param("..."), false));
        assertFalse(handler.matchEvent(param("gitx"), false));
        assertFalse(handler.matchEvent(param("help"), false));
    }

    private static AlfredParam param(String input) {
        return new AlfredParam(input, List.of(input.split("\\s+")));
    }
}
