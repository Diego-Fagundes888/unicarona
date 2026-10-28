package com.unicarona.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.notification.config.EmailSettings;
import com.unicarona.notification.provider.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class EmailProviderTest {
    @TempDir Path directory;
    @Test void privacyFooterUsesThePublicReaderAndHonorsAnExplicitConfiguredAddress(){
        var settings=new EmailSettings(new MockEnvironment().withProperty("APP_ORIGIN","https://example.test"));
        assertEquals("https://example.test/legal.html?document=privacy",settings.privacyUrl());
        var explicit=new EmailSettings(new MockEnvironment().withProperty("APP_ORIGIN","https://example.test").withProperty("EMAIL_PRIVACY_URL","https://example.test/retained-policy"));
        assertEquals("https://example.test/retained-policy",explicit.privacyUrl());
    }
    @Test void closureNoticesDistinguishRequestedCancelledPlatformAndCompletedWithoutPromisingTotalErasure()throws Exception {
        var settings=new EmailSettings(new MockEnvironment().withProperty("APP_ORIGIN","https://example.test"));var template=new com.unicarona.notification.template.AuthEmailTemplate(settings);
        for(String phase:List.of("requested","platform","cancelled","completed")){
            boolean completed=phase.equals("completed");var input=new EmailProvider.Message(UUID.randomUUID(),"synthetic@example.test","closure_"+phase,completed?settings.privacyUrl():"https://example.test/login.html");var content=template.renderClosure(input,java.time.Instant.parse("2026-11-01T12:00:00Z"),"privacy@example.test",java.time.Instant.parse("2026-10-25T12:00:00Z"));
            assertTrue(content.text().contains("aviso essencial"));assertFalse(content.text().contains("link expira"));assertFalse(content.text().contains("todos os seus dados"));assertFalse(content.html().contains("<script"));
            if(completed){assertTrue(content.text().contains("privacy@example.test"));assertTrue(content.text().contains("acesso restrito"));assertTrue(content.text().contains("não é uma quitação"));assertTrue(content.html().contains("Consultar privacidade"));assertTrue(content.text().contains("registrado em 25/10/2026 às 09:00"));}
            if(phase.equals("requested")||phase.equals("platform"))assertTrue(content.text().contains("01/11/2026 às 09:00"));
            if(phase.equals("cancelled"))assertTrue(content.text().contains("não foram reativadas"));
            if(phase.equals("platform"))assertTrue(content.text().contains("peça revisão"));
            assertEquals(content,template.render(new EmailProvider.Message(input.id(),input.to(),input.kind(),input.url(),"",content)));
            if(Boolean.getBoolean("unicarona.syntheticMailPreview")){
                Path working=Path.of("").toAbsolutePath().normalize();assertEquals("backend-java",working.getFileName().toString());Path root=working.getParent().resolve(".impeccable/review/privacy/closure-emails").normalize();assertTrue(root.startsWith(working.getParent()));Files.createDirectories(root);Files.writeString(root.resolve("closure_"+phase+".html"),content.html(),StandardCharsets.UTF_8);
            }
        }
        assertThrows(IllegalArgumentException.class,()->template.render(new EmailProvider.Message(UUID.randomUUID(),"synthetic@example.test","closure_completed","https://attacker.example.test/privacy")));
    }
    @Test void identityEmailsDistinguishConfirmationFromSecurityNoticesAndRejectWrongLinks(){
        var settings=new EmailSettings(new MockEnvironment().withProperty("APP_ORIGIN","https://example.test"));var template=new com.unicarona.notification.template.AuthEmailTemplate(settings);
        var confirmation=template.render(new EmailProvider.Message(UUID.randomUUID(),"synthetic@example.test","change_email","https://example.test/login.html#alterar-email?token="+"a".repeat(43)));
        assertTrue(confirmation.text().contains("30 minutos"));assertTrue(confirmation.html().contains("Confirmar novo e-mail"));assertTrue(confirmation.text().contains("entrar na conta atual"));
        for(String kind:List.of("email_change_notice","email_changed_notice")){
            var notice=template.render(new EmailProvider.Message(UUID.randomUUID(),"synthetic@example.test",kind,"https://example.test/login.html"));assertTrue(notice.text().contains("aviso de segurança"));assertFalse(notice.text().contains("link expira"));assertFalse(notice.html().contains("<script"));
        }
        assertThrows(IllegalArgumentException.class,()->template.render(new EmailProvider.Message(UUID.randomUUID(),"synthetic@example.test","change_email","https://other.example.test/login.html#alterar-email?token="+"a".repeat(43))));
        assertThrows(IllegalArgumentException.class,()->template.render(new EmailProvider.Message(UUID.randomUUID(),"synthetic@example.test","change_email","https://example.test/login.html#verificar?token="+"a".repeat(43))));
    }
    @Test void mailboxRetriesUseOnePrivateFileAndRejectPublicPath() throws Exception {
        var json=new ObjectMapper();Path root=directory.resolve("mail"),publicRoot=directory.resolve("public");Files.createDirectories(publicRoot);
        assertThrows(IllegalStateException.class,() -> new DevelopmentEmailProvider(publicRoot.resolve("mail"),publicRoot,json));
        var provider=new DevelopmentEmailProvider(root,publicRoot,json);var message=new EmailProvider.Message(UUID.randomUUID(),"synthetic@example.test","verify_email","https://example.test/synthetic");provider.send(message);provider.send(message);
        try(var files=Files.list(root)) { assertEquals(1,files.count()); }
        assertEquals(json.valueToTree(message),json.readTree(Files.readAllBytes(root.resolve(message.id()+".json"))));
        assertEquals("EmailMessage[redacted]",message.toString());
        if(FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve(message.id()+".json"))));
    }
    @Test void providerConfigurationRejectsUnsupportedMissingSmtpAndDevelopmentInProduction() {
        assertThrows(IllegalStateException.class,() -> new EmailSettings(new MockEnvironment().withProperty("EMAIL_PROVIDER","unknown")));
        assertThrows(IllegalStateException.class,() -> new EmailSettings(new MockEnvironment().withProperty("EMAIL_PROVIDER","smtp")));
        assertThrows(IllegalStateException.class,() -> new EmailSettings(new MockEnvironment().withProperty("NODE_ENV","production")));
        assertThrows(IllegalStateException.class,() -> new EmailSettings(new MockEnvironment().withProperty("SMTP_SECURE","yes")));
    }
    @Test void mailboxAliasIntoPublicFrontendIsRejectedBeforeAnyLinkIsWritten() throws Exception {
        Path publicRoot=directory.resolve("public"),alias=directory.resolve("mail-alias");Files.createDirectories(publicRoot);
        if(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) {
            var command=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-Command","New-Item -ItemType Junction -Path $env:TEST_MAIL_ALIAS -Target $env:TEST_MAIL_PUBLIC | Out-Null");
            command.environment().put("TEST_MAIL_ALIAS",alias.toString());command.environment().put("TEST_MAIL_PUBLIC",publicRoot.toString());var process=command.start();assertTrue(process.waitFor(15,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        } else Files.createSymbolicLink(alias,publicRoot);
        try {
            var provider=new DevelopmentEmailProvider(alias,publicRoot,new ObjectMapper());assertThrows(IllegalStateException.class,() -> provider.send(new EmailProvider.Message(UUID.randomUUID(),"synthetic@example.test","verify_email","https://example.test/synthetic")));
            try(var files=Files.list(publicRoot)) { assertEquals(0,files.count()); }
        } finally { Files.deleteIfExists(alias); }
    }
    @Test void smtpUsesStableMessageIdUtf8SubjectsAndOriginalTextThroughLoopbackOnly() throws Exception {
        try(var listener=new ServerSocket()) {
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(),0));listener.setSoTimeout(10000);
            var env=new MockEnvironment().withProperty("EMAIL_PROVIDER","smtp").withProperty("SMTP_HOST",listener.getInetAddress().getHostAddress()).withProperty("SMTP_PORT",Integer.toString(listener.getLocalPort())).withProperty("MAIL_FROM","Synthetic Sender <sender@example.test>").withProperty("APP_ORIGIN","https://example.test");
            var provider=new SmtpEmailProvider(new EmailSettings(env));UUID id=UUID.randomUUID();
            try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                var capture=executor.submit(() -> {
                    var messages=new ArrayList<String>();
                    for(int i=0;i<2;i++) try(var socket=listener.accept()) {
                        socket.setSoTimeout(10000);var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));var writer=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.US_ASCII));
                        reply(writer,"220 localhost synthetic SMTP");
                        for(String line;(line=reader.readLine())!=null;) {
                            if(line.equals("DATA")) {
                                reply(writer,"354 continue");var body=new StringBuilder();for(String data;(data=reader.readLine())!=null&&!data.equals(".");) body.append(data.startsWith("..")?data.substring(1):data).append("\r\n");messages.add(body.toString());reply(writer,"250 queued");
                            } else if(line.equals("QUIT")) { reply(writer,"221 closing");break; }
                            else reply(writer,"250 localhost");
                        }
                    }
                    return messages;
                });
                for(String kind:List.of("verify_email","reset_password")) provider.send(new EmailProvider.Message(id,"recipient@example.test",kind,"https://example.test/login.html#"+(kind.equals("verify_email")?"verificar":"redefinir")+"?token="+"a".repeat(43)));
                var messages=capture.get(10,TimeUnit.SECONDS);assertEquals(2,messages.size());
                for(int i=0;i<2;i++) {
                    var mail=new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(messages.get(i).getBytes(StandardCharsets.US_ASCII)));
                    assertEquals("<"+id+"@example.test>",mail.getMessageID());assertEquals(i==0?"Confirme seu e-mail no UniCarona":"Recupere seu acesso ao UniCarona",mail.getSubject());
                    var parts=(jakarta.mail.Multipart)mail.getContent(); assertEquals(2,parts.getCount()); assertTrue(parts.getBodyPart(0).isMimeType("text/plain")); assertTrue(parts.getBodyPart(1).isMimeType("text/html")); assertTrue(parts.getBodyPart(0).getContent().toString().contains("https://example.test/login.html#")); assertTrue(parts.getBodyPart(1).getContent().toString().contains("UniCarona"));
                    assertEquals("recipient@example.test",((jakarta.mail.internet.InternetAddress)mail.getAllRecipients()[0]).getAddress());
                }
            }
        }
    }
    private static void reply(BufferedWriter writer,String text) throws IOException { writer.write(text+"\r\n");writer.flush(); }
}
