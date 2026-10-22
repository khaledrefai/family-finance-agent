package ae.familyfinance.core.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** "Today" means today in the UAE, wherever the server runs. Tests can replace this bean with a fixed clock. */
@Configuration
public class ClockConfig {

    public static final ZoneId UAE = ZoneId.of("Asia/Dubai");

    @Bean
    Clock clock() {
        return Clock.system(UAE);
    }
}
