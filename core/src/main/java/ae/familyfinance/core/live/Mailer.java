package ae.familyfinance.core.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Plain-text email to a parent. Only on when SMTP_HOST is set (Spring Boot then makes the JavaMailSender);
 * without it emails are skipped and the bell and web push still work.
 */
@Component
public class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final String from;
    private final String appUrl;
    private final boolean configured;

    public Mailer(ObjectProvider<JavaMailSender> sender, @Value("${app.mail.from:}") String from,
                  @Value("${app.public-url:}") String appUrl, @Value("${spring.mail.host:}") String host) {
        this.sender = sender;
        this.configured = !host.isBlank() && !from.isBlank();
        this.from = from;
        this.appUrl = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
    }

    public boolean enabled() {
        return configured && sender.getIfAvailable() != null;
    }

    public boolean send(String to, String title, String body, String link) {
        JavaMailSender mail = sender.getIfAvailable();
        if (!configured || mail == null || to == null || to.isBlank() || to.endsWith(".local")) {
            return false; // the demo family's seeded addresses end in .local and are never mailed
        }
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(to);
        m.setSubject(title);
        m.setText(body + "\n\n" + (appUrl.isBlank() ? "" : "Open the app: " + appUrl + link + "\n\n")
                + "Family Finance Coach (demo). You get this because email alerts are on in Family, Notifications.\n");
        try {
            mail.send(m);
            return true;
        } catch (MailException e) {
            log.warn("Email to a parent failed: {}", e.getMessage());
            return false;
        }
    }
}
