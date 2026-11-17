package com.leon.workflow.messaging;

import com.example.helloservice.HelloServiceApplication;
import com.leon.workflow.ApiTokenInterceptor;
import com.leon.workflow.service.BrowserSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=HelloServiceApplication.class,properties={
    "spring.datasource.url=jdbc:h2:mem:agent-avatar-test;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop",
    "leon.workflows.scheduling-enabled=false","leon.agents.dispatch-enabled=false",
    "leon.workflows.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "leon.auth.required=true","leon.auth.admin-origin=https://example.test",
    "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class AgentAvatarIntegrationTest {
    @Autowired WebApplicationContext context; @Autowired AgentAvatarService avatars; @Autowired AgentRegistry agents;
    @Autowired BrowserSessionService sessions; @Autowired ApiTokenInterceptor auth; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
    @TempDir Path temporary;
    private MockMvc mvc(){return MockMvcBuilders.webAppContextSetup(context).build();}
    private static final String AUTH="Bearer integration-test-token";
    private Cookie cookie(){return new Cookie(BrowserSessionService.COOKIE_NAME,sessions.create(false,auth.fingerprint()));}
    @BeforeEach void clean(){jdbc.update("DELETE FROM chat_agent");jdbc.update("DELETE FROM chat_agent_avatar");}
    private byte[] image(int width,int height,String format) throws IOException {
        var image=new BufferedImage(width,height,format.equals("jpg")?BufferedImage.TYPE_INT_RGB:BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)image.setRGB(x,y,0xffff6688);
        var output=new ByteArrayOutputStream();ImageIO.write(image,format,output);return output.toByteArray();
    }
    private MockMultipartFile file(byte[] bytes){return new MockMultipartFile("file","portrait.png","image/png",bytes);}

    @Test void catalogContainsThirtyOriginalPresetsAndRequiresAuthentication() throws Exception {
        assertEquals(30,avatars.catalog().size());assertTrue(avatars.catalog().stream().allMatch(AgentAvatarService.Avatar::builtin));
        mvc().perform(get("/api/agent-avatars")).andExpect(status().isUnauthorized());
        mvc().perform(get("/api/agent-avatars/alfred")).andExpect(status().isUnauthorized());
        mvc().perform(get("/api/agent-avatars").header("Authorization",AUTH)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(30));
        for(var avatar:avatars.catalog()) {
            byte[] original=new ClassPathResource("agent-avatars/"+avatar.id()+".png").getInputStream().readAllBytes();
            assertArrayEquals(original,avatars.image(avatar.id()));assertNotNull(ImageIO.read(new ByteArrayInputStream(original)));
        }
        mvc().perform(get("/api/agent-avatars/squidward").header("Authorization",AUTH)).andExpect(status().isOk())
            .andExpect(content().contentType("image/png")).andExpect(header().string("X-Content-Type-Options","nosniff"));
    }
    @Test void uploadingRequiresAdminCookieAndWriteOrigin() throws Exception {
        var image=file(image(80,40,"png"));Cookie cookie=cookie();
        mvc().perform(multipart("/api/agent-admin/avatars").file(image)).andExpect(status().isUnauthorized());
        mvc().perform(multipart("/api/agent-admin/avatars").file(image).header("Authorization",AUTH)).andExpect(status().isForbidden());
        mvc().perform(multipart("/api/agent-admin/avatars").file(image).cookie(cookie)).andExpect(status().isForbidden());
        mvc().perform(multipart("/api/agent-admin/avatars").file(image).cookie(cookie).header("Origin","https://wrong.test").header("X-Requested-With","XMLHttpRequest")).andExpect(status().isForbidden());
        mvc().perform(multipart("/api/agent-admin/avatars").file(image).cookie(cookie).header("Origin","https://example.test").header("X-Requested-With","XMLHttpRequest"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.builtin").value(false));
        assertEquals(31,avatars.catalog().size());
    }
    @Test void uploadsNormalizeWithoutDistortionPreserveAlphaAndDeduplicate() throws Exception {
        byte[] png=image(80,40,"png");var uploaded=avatars.upload(file(png));
        assertTrue(uploaded.id().matches("upload-[a-f0-9]{64}"));
        var thumbnail=ImageIO.read(new ByteArrayInputStream(avatars.image(uploaded.id())));
        assertEquals(512,thumbnail.getWidth());assertEquals(512,thumbnail.getHeight());
        assertEquals(0,thumbnail.getRGB(256,0)>>>24);assertEquals(255,thumbnail.getRGB(256,256)>>>24);
        assertEquals(uploaded.id(),avatars.upload(file(png)).id());assertEquals(31,avatars.catalog().size());
        var jpeg=avatars.upload(new MockMultipartFile("file","photo.jpg","image/jpeg",image(40,80,"jpg")));
        assertNotNull(ImageIO.read(new ByteArrayInputStream(avatars.image(jpeg.id()))));
        mvc().perform(get("/api/agent-avatars/"+uploaded.id()).header("Authorization",AUTH)).andExpect(status().isOk()).andExpect(content().bytes(avatars.image(uploaded.id())));
    }
    @Test void rejectsCorruptUnsupportedOversizedAndUnsafeReferences() throws Exception {
        for(byte[] bytes:List.of(new byte[0],"<svg onload='alert(1)'/>".getBytes(),new byte[5*1024*1024+1],image(4097,1,"png"))) {
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->avatars.upload(file(bytes)));
        }
        for(String id:List.of("../application.properties","https://example.test/photo.png","unknown","upload-x"))assertFalse(avatars.exists(id));
        mvc().perform(get("/api/agent-avatars/unknown").header("Authorization",AUTH)).andExpect(status().isNotFound());
        assertEquals(30,avatars.catalog().size());
    }
    @Test void agentSelectionPersistsAndLegacyUpdatesKeepAvatar() throws Exception {
        var created=agents.save(null,new AgentRegistry.Edit("助手","helper","openai","test-model","",true,"test-only-key",null));
        assertEquals("alfred",created.avatarId());
        var avatar=avatars.upload(file(image(24,24,"png")));
        var updated=agents.save(created.id(),new AgentRegistry.Edit(created.name(),created.handle(),created.provider(),created.model(),"",true,"",created.version(),avatar.id()));
        assertEquals(avatar.id(),updated.avatarId());
        var legacy=agents.save(updated.id(),new AgentRegistry.Edit(updated.name(),updated.handle(),updated.provider(),updated.model(),"",true,"",updated.version()));
        assertEquals(avatar.id(),legacy.avatarId());assertEquals("test-only-key",agents.apiKey(created.id()));
        mvc().perform(get("/api/agents").header("Authorization",AUTH)).andExpect(status().isOk()).andExpect(jsonPath("$[0].avatarId").value(avatar.id()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->agents.save(legacy.id(),new AgentRegistry.Edit(legacy.name(),legacy.handle(),legacy.provider(),legacy.model(),"",true,"",legacy.version(),"https://example.test/tracker")));
        assertEquals(avatar.id(),agents.get(legacy.id()).avatarId());
    }
    @Test void uploadsSurviveDatabaseCloseAndReopen() throws Exception {
        String url="jdbc:h2:file:"+temporary.resolve("avatars");
        var firstJdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        var first=new AgentAvatarService(firstJdbc);first.initialize();var uploaded=first.upload(file(image(20,10,"png")));byte[] saved=first.image(uploaded.id());firstJdbc.execute("SHUTDOWN");
        var reopenedJdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));var reopened=new AgentAvatarService(reopenedJdbc);reopened.initialize();
        assertTrue(reopened.exists(uploaded.id()));assertArrayEquals(saved,reopened.image(uploaded.id()));assertEquals(31,reopened.catalog().size());reopenedJdbc.execute("SHUTDOWN");
    }
}
