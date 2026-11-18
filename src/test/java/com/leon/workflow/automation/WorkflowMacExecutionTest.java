package com.leon.workflow.automation;

import com.leon.workflow.ai.OpenAiExecutor;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.leon.workflow.automation.WorkflowJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowMacExecutionTest {
    @Test void builtInOpenAiUsesMacWithoutResolvingOrCallingTheBlockedServerRoute() throws Exception {
        var executor=mock(OpenAiExecutor.class);when(executor.enabled()).thenReturn(true);
        var body=Map.<String,Object>of("model","gpt-6-luna","stream",false);
        when(executor.execute(body,"test-key",120000)).thenReturn(Map.of("status",200,"body",Map.of("choices",List.of())));
        var credentials=mock(WorkflowCredentials.class);var http=new WorkflowHttp(credentials,"",executor);
        try {
            var response=http.executeResolved(Map.of("url",WorkflowAiModel.OPENAI_API,"method","POST","headers",Map.of("Authorization","Bearer test-key"),"body",body,"timeoutMs",120000),new WorkflowPrivacy(),240000);
            var json=object(object(((List<?>)response.get("items")).getFirst()).get("json"));
            assertEquals(true,json.get("usageRecorded"));assertEquals(200,json.get("status"));
            verify(executor).execute(body,"test-key",120000);verifyNoInteractions(credentials);
        } finally {http.close();}
    }
}
