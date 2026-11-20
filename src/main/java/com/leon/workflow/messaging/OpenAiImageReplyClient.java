package com.leon.workflow.messaging;

import com.leon.workflow.ai.OpenAiExecutor;
import com.leon.workflow.automation.WorkflowModels;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Stateless conversational image tool. No provider-side conversation or public image URLs. */
final class OpenAiImageReplyClient {
    private final ObjectMapper json;
    private final HttpClient http;
    private final WorkflowModels models;
    private final OpenAiExecutor executor;
    private final ChatImageService images;
    OpenAiImageReplyClient(ObjectMapper json,HttpClient http,WorkflowModels models,OpenAiExecutor executor,ChatImageService images){
        this.json=json;this.http=http;this.models=models;this.executor=executor;this.images=images;
    }
    AgentModelClient.Reply reply(AgentRegistry.Agent agent,String key,List<Map<String,String>> messages) throws Exception {
        if(!agent.provider().equals("openai")) throw failure("允许绘图需要使用支持图片生成工具的 OpenAI 对话模型");
        var body=new LinkedHashMap<String,Object>();
        body.put("model",agent.model());body.put("input",input(messages));body.put("store",false);body.put("stream",false);
        body.put("max_output_tokens",8192);body.put("max_tool_calls",1);body.put("parallel_tool_calls",false);
        body.put("tools",List.of(Map.of("type","image_generation","model",agent.imageModel(),"quality","medium","output_format","jpeg")));
        body.put("tool_choice","auto");
        body.put("instructions","你可以调用图片生成工具。仅在最新用户消息要求实际生成或修改图片时调用；讨论图片、翻译、总结和普通聊天直接用文字回答。需要绘图时实际调用工具，不要只描述画法或声称已经生成。历史图片只作为参考。每次最多生成一张图片。");
        String user=AgentUsageContext.user();boolean mac=executor.enabled();
        int status;tools.jackson.databind.JsonNode payload;
        if(mac){
            Map<String,Object> result;
            try {result=executor.executeResponses(body,key,240000,user);}
            catch(IllegalArgumentException unavailable){throw failure(unavailable.getMessage());}
            status=((Number)result.get("status")).intValue();payload=json.valueToTree(result.get("body"));
        } else {
            var request=HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/responses"))
                    .timeout(Duration.ofSeconds(230)).header("Authorization","Bearer "+key).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body),StandardCharsets.UTF_8)).build();
            HttpResponse<byte[]> response;
            try {response=http.send(request,info->new OfficialAgentModelClient.LimitedBody(OpenAiExecutor.IMAGE_RESULT_LIMIT));}
            catch(HttpConnectTimeoutException timeout){throw failure("连接 OpenAI 超时，请检查网络后手动重试");}
            catch(HttpTimeoutException timeout){throw failure("等待 OpenAI 绘图回复超时，请稍后手动重试");}
            status=response.statusCode();payload=status>=200&&status<300?json.readTree(response.body()):json.createObjectNode();
        }
        if(status<200||status>=300) throw failure(switch(status){
            case 400 -> "图片工具请求未被接受，请确认对话模型支持 Responses 图片生成工具、绘图模型可用且账号已获权限";
            case 401,403 -> "图片工具认证失败，请检查 Agent 的 OpenAI API Key 和模型权限";
            case 429 -> "OpenAI 限流或额度不足，请稍后重试";
            default -> "OpenAI 图片工具请求失败（HTTP "+status+"）";
        });
        if(!mac) models.recordOpenAiResponseUsage(agent.model(),json.convertValue(payload,Map.class),agent.imageModel(),user);
        if(!"completed".equals(payload.path("status").asText())) throw failure("OpenAI 未完成本次回复，请稍后手动重试");
        var text=new StringBuilder();var pictures=new ArrayList<byte[]>();
        for(var item:payload.path("output")){
            if("message".equals(item.path("type").asText())){
                for(var part:item.path("content")){
                    String type=part.path("type").asText();
                    if(type.equals("output_text")||type.equals("refusal")){
                        if(!text.isEmpty())text.append('\n');text.append(part.path(type.equals("refusal")?"refusal":"text").asText(""));
                    }
                }
            }else if("image_generation_call".equals(item.path("type").asText())){
                if(!"completed".equals(item.path("status").asText())||pictures.size()>=ChatImageService.MAX_PER_MESSAGE) throw failure("图片生成未完成或返回图片过多，请重试");
                String encoded=item.path("result").asText("");
                if(encoded.isEmpty()||encoded.length()>ChatImageService.MAX_GENERATED*4L/3+4)throw failure("生成图片为空或过大，请降低尺寸后重试");
                try {
                    byte[] bytes=Base64.getDecoder().decode(encoded);
                    if(bytes.length==0||bytes.length>ChatImageService.MAX_GENERATED)throw new IllegalArgumentException();
                    pictures.add(bytes);
                }catch(IllegalArgumentException invalid){throw failure("生成图片数据无效，请重试");}
            }
        }
        String answer=text.toString().strip();if(!key.isEmpty())answer=answer.replace(key,"[已隐藏凭据]");
        if(answer.isBlank()&&!pictures.isEmpty())answer="已生成图片。";
        if(answer.isBlank()||answer.length()>8000)throw failure("模型回复为空或超过 8000 字符，请缩短问题后重试");
        return new AgentModelClient.Reply(answer,List.copyOf(pictures));
    }
    private List<Map<String,Object>> input(List<Map<String,String>> messages) throws AgentModelClient.Failure {
        var source=images==null?messages.stream().map(m->Map.<String,Object>of("role",m.get("role"),"content",m.getOrDefault("content",""))).toList():images.modelMessages(messages,true);
        var result=new ArrayList<Map<String,Object>>();
        for(var message:source){
            String role=(String)message.get("role");Object content=message.get("content");
            if(content instanceof List<?> parts){
                var converted=new ArrayList<Map<String,Object>>();
                for(var raw:parts){
                    var part=(Map<?,?>)raw;
                    if("text".equals(part.get("type"))) converted.add(Map.of("type","input_text","text",part.get("text")));
                    else if("image_url".equals(part.get("type"))) converted.add(Map.of("type","input_image","image_url",((Map<?,?>)part.get("image_url")).get("url"),"detail","auto"));
                }
                result.add(Map.of("role",role,"content",converted));
            }else result.add(Map.of("role",role,"content",content));
        }
        return List.copyOf(result);
    }
    private static AgentModelClient.Failure failure(String message){return new AgentModelClient.Failure(message);}
}
