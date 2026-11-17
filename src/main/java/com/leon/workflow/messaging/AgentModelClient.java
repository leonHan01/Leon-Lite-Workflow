package com.leon.workflow.messaging;

import java.util.List;
import java.util.Map;

public interface AgentModelClient {
    String complete(AgentRegistry.Agent agent, String apiKey, List<Map<String,String>> messages) throws Exception;
    class Failure extends Exception { public Failure(String message) { super(message); } }
}
