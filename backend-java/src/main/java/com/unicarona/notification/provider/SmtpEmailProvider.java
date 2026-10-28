package com.unicarona.notification.provider;

import com.unicarona.notification.config.EmailSettings;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.mail.javamail.JavaMailSenderImpl;

public final class SmtpEmailProvider implements EmailProvider {
    private final JavaMailSenderImpl sender;
    private final String from;
    private final com.unicarona.notification.template.AuthEmailTemplate template;
    public SmtpEmailProvider(EmailSettings settings) {
        from=settings.from();template=new com.unicarona.notification.template.AuthEmailTemplate(settings);sender=new JavaMailSenderImpl();sender.setHost(settings.host());sender.setPort(settings.port());
        if(!settings.user().isEmpty()) { sender.setUsername(settings.user());sender.setPassword(settings.password()); }
        var properties=sender.getJavaMailProperties();properties.setProperty("mail.smtp.auth",Boolean.toString(!settings.user().isEmpty()));
        properties.setProperty("mail.smtp.ssl.enable",Boolean.toString(settings.secure()));
        properties.setProperty("mail.smtp.ssl.checkserveridentity","true");
        properties.setProperty("mail.smtp.starttls.enable",Boolean.toString(!settings.secure()));
        // Never downgrade to clear text on a remote SMTP host.
        properties.setProperty("mail.smtp.starttls.required",Boolean.toString(!settings.secure()&&!java.util.Set.of("localhost","127.0.0.1","::1").contains(settings.host())));
        properties.setProperty("mail.smtp.connectiontimeout","5000");properties.setProperty("mail.smtp.timeout","10000");properties.setProperty("mail.smtp.writetimeout","10000");
    }
    @Override public void send(Message input) throws MessagingException {
        var mail=new MimeMessage(sender.getSession()) {
            @Override protected void updateMessageID() throws MessagingException { setHeader("Message-ID","<"+input.id()+"@"+new InternetAddress(from).getAddress().split("@",2)[1]+">"); }
        };
        mail.setFrom(new InternetAddress(from));mail.setRecipient(jakarta.mail.Message.RecipientType.TO,new InternetAddress(input.to()));
        var content=template.render(input);
        mail.setSubject(content.subject(),StandardCharsets.UTF_8.name());
        var alternatives=new jakarta.mail.internet.MimeMultipart("alternative");
        var plain=new jakarta.mail.internet.MimeBodyPart();plain.setText(content.text(),StandardCharsets.UTF_8.name());alternatives.addBodyPart(plain);
        var html=new jakarta.mail.internet.MimeBodyPart();html.setContent(content.html(),"text/html; charset=UTF-8");alternatives.addBodyPart(html);mail.setContent(alternatives);
        sender.send(mail);
    }
}
