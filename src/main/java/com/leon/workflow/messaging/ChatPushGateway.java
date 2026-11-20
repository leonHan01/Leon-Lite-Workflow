package com.leon.workflow.messaging;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.*;

/** Provider credentials stay on the server. Unconfigured transports make no network requests. */
@Service
public class ChatPushGateway {
    private final ObjectMapper json;private final String keyFile,keyId,teamId,topic,vivoId,vivoKey,vivoSecret;private final int vivoMode;
    private final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    private String appleJwt,vivoAuth;private long appleExpiry,vivoExpiry;
    public ChatPushGateway(ObjectMapper json,
        @Value("${leon.push.apns.key-file:}") String keyFile,@Value("${leon.push.apns.key-id:}") String keyId,
        @Value("${leon.push.apns.team-id:}") String teamId,@Value("${leon.push.apns.topic:com.leon.ios}") String topic,
        @Value("${leon.push.vivo.app-id:}") String vivoId,@Value("${leon.push.vivo.app-key:}") String vivoKey,
        @Value("${leon.push.vivo.app-secret:}") String vivoSecret,@Value("${leon.push.vivo.mode:0}") int vivoMode){
        this.json=json;this.keyFile=keyFile;this.keyId=keyId;this.teamId=teamId;this.topic=topic;this.vivoId=vivoId;this.vivoKey=vivoKey;this.vivoSecret=vivoSecret;this.vivoMode=vivoMode;
    }
    public boolean available(String provider){return switch(provider){case "apns"->!keyFile.isBlank()&&!keyId.isBlank()&&!teamId.isBlank();case "vivo"->vivoId.matches("[0-9]+")&&!vivoKey.isBlank()&&!vivoSecret.isBlank();default->false;};}
    public enum Result { SENT,RETRY,INVALID }
    public Result send(String provider,String token,String environment,String device,ChatPushService.Event event,String account){
        if(!available(provider))return Result.RETRY;
        try{return provider.equals("apns")?apple(token,environment,device,event,account):vivo(token,device,event,account);}
        catch(Exception ignored){return Result.RETRY;} // Never log a token, JWT, response body or private message.
    }
    private Result apple(String token,String environment,String device,ChatPushService.Event event,String account)throws Exception{
        String host=environment.equals("production")?"https://api.push.apple.com":"https://api.sandbox.push.apple.com";
        var payload=Map.of("aps",Map.of("alert",Map.of("title",event.title(),"body",event.text()),"sound","default","thread-id",event.room()),"accountId",account,"roomId",event.room(),"messageId",event.id());
        var req=HttpRequest.newBuilder(URI.create(host+"/3/device/"+token)).timeout(Duration.ofSeconds(10))
            .header("authorization","bearer "+appleToken()).header("apns-topic",topic).header("apns-push-type","alert").header("apns-priority","10")
            .header("apns-expiration",String.valueOf(System.currentTimeMillis()/1000+86400))
            .header("apns-id",UUID.nameUUIDFromBytes((device+":"+event.id()).getBytes(StandardCharsets.UTF_8)).toString())
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload))).build();
        var response=http.send(req,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()==200)return Result.SENT;if(response.statusCode()==403){appleExpiry=0;return Result.RETRY;}
        String reason=json.readTree(response.body()).path("reason").asText();
        return response.statusCode()==410||Set.of("BadDeviceToken","DeviceTokenNotForTopic","Unregistered").contains(reason)?Result.INVALID:Result.RETRY;
    }
    private synchronized String appleToken()throws Exception{
        long now=System.currentTimeMillis()/1000;if(appleJwt!=null&&now<appleExpiry)return appleJwt;
        String header=base64(json.writeValueAsBytes(Map.of("alg","ES256","kid",keyId))),body=base64(json.writeValueAsBytes(Map.of("iss",teamId,"iat",now)));
        String pem=Files.readString(Path.of(keyFile)).replaceAll("-----[^-]+-----","").replaceAll("\\s","");
        var key=KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        var signature=Signature.getInstance("SHA256withECDSAinP1363Format");signature.initSign(key);signature.update((header+"."+body).getBytes(StandardCharsets.US_ASCII));
        appleJwt=header+"."+body+"."+base64(signature.sign());appleExpiry=now+3000;return appleJwt;
    }
    private Result vivo(String token,String device,ChatPushService.Event event,String account)throws Exception{
        var payload=new HashMap<String,Object>();payload.put("appId",Long.parseLong(vivoId));payload.put("regId",token);payload.put("notifyType",1);
        payload.put("title",event.title());payload.put("content",event.text());payload.put("timeToLive",86400);payload.put("skipType",4);
        payload.put("clientCustomMap",Map.of("accountId",account,"roomId",event.room(),"messageId",String.valueOf(event.id())));
        payload.put("skipContent",intent(event,account));payload.put("foregroundShow",false);payload.put("profileId",account);payload.put("classification",1);payload.put("category","IM");payload.put("pushMode",vivoMode);
        payload.put("requestId",UUID.nameUUIDFromBytes((device+":"+event.id()).getBytes(StandardCharsets.UTF_8)).toString());
        var response=vivoRequest("/message/send",payload,vivoToken());int result=response.path("result").asInt(-1);
        if(result==0)return Result.SENT;if(result==10000||result==10001)vivoExpiry=0;
        return result==10302?Result.INVALID:Result.RETRY;
    }
    static String intent(ChatPushService.Event event,String account){
        return "intent://leon/notification#Intent;scheme=leon;package=com.leon.app;component=com.leon.app/.MainActivity;S.roomId="+encode(event.room())+";S.accountId="+encode(account)+";l.messageId="+event.id()+";end";
    }
    private synchronized String vivoToken()throws Exception{
        long now=System.currentTimeMillis();if(vivoAuth!=null&&now<vivoExpiry)return vivoAuth;
        String sign=HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest((vivoId+vivoKey+now+vivoSecret).getBytes(StandardCharsets.UTF_8)));
        var value=vivoRequest("/message/auth",Map.of("appId",Long.parseLong(vivoId),"appKey",vivoKey,"timestamp",now,"sign",sign),null);
        if(value.path("result").asInt(-1)!=0||value.path("authToken").asText().isBlank())throw new IllegalStateException("provider unavailable");
        vivoAuth=value.path("authToken").asText();vivoExpiry=now+3600000;return vivoAuth;
    }
    private tools.jackson.databind.JsonNode vivoRequest(String path,Object body,String auth)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("https://api-push.vivo.com.cn"+path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
        if(auth!=null)request.header("authToken",auth);
        var response=http.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200||response.body().length()>65536)throw new IllegalStateException("provider unavailable");return json.readTree(response.body());
    }
    private static String base64(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
}
