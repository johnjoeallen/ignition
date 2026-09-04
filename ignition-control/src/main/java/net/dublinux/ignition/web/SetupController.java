package net.dublinux.ignition.web;

import net.dublinux.ignition.auth.AccountService;
import net.dublinux.ignition.auth.BootstrapService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** First-run only: create the platform admin from the logged bootstrap code. */
@Controller
public class SetupController {

    private final BootstrapService bootstrap;
    private final AccountService accounts;

    public SetupController(BootstrapService bootstrap, AccountService accounts) {
        this.bootstrap = bootstrap;
        this.accounts = accounts;
    }

    @GetMapping("/setup")
    public String form() {
        requirePending();
        return "setup";
    }

    @PostMapping("/setup")
    public String submit(@RequestParam String code,
                         @RequestParam String email,
                         @RequestParam String password,
                         @RequestParam String password2,
                         Model model) {
        requirePending();
        if (!bootstrap.codeMatches(code)) {
            return error(model, "wrong setup code — check the container logs");
        }
        if (!password.equals(password2)) {
            return error(model, "passwords don't match");
        }
        try {
            accounts.createFirstAdmin(email, password);
        } catch (RuntimeException e) {
            return error(model, e.getMessage());
        }
        return "redirect:/login?created";
    }

    private void requirePending() {
        if (!bootstrap.pending()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private String error(Model model, String msg) {
        model.addAttribute("error", msg);
        return "setup";
    }
}
