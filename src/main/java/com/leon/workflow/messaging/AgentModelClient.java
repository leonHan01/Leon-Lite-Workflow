package com.leon.workflow.messaging;

import java.util.List;
import java.util.Map;

public interface AgentModelClient {
    String complete(AgentRegistry.Agent agent, String apiKey, List<Map<String,String>> messages) throws Exception;
    /** Only interactive replies may use tools; summaries and titles call complete(). */
    default Reply reply(AgentRegistry.Agent agent, String apiKey, List<Map<String,String>> messages) throws Exception {
        return new Reply(complete(agent,apiKey,messages),List.of());
    }
    record Reply(String text, List<byte[]> images) {}
    class Failure extends Exception { public Failure(String message) { super(message); } }
}
