package ae.familyfinance.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Core backend (modular monolith). Each top-level package is a module:
 * family, auth, requests, money. Later weeks add ledger, budgets, rules, wallet, goals, meals, audit.
 */
@SpringBootApplication
public class CoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }
}
