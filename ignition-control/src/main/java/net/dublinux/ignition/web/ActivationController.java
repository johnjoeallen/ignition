package net.dublinux.ignition.web;

import java.util.Map;

import net.dublinux.ignition.auth.AccountService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Enumeration-safe activation-mail requests. */
@RestController
public class ActivationController {

    private static final Map<String, String> GENERIC_RESPONSE = Map.of(
            "message", "If the account can receive an activation link, one has been sent.");

    private final AccountService accounts;

    public ActivationController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/api/activation/resend")
    public Map<String, String> resend(@RequestParam String email) {
        accounts.resendActivation(email);
        return GENERIC_RESPONSE;
    }
}
