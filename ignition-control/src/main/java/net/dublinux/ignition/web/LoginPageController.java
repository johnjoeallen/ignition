package net.dublinux.ignition.web;

import net.dublinux.ignition.auth.BootstrapService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Renders the sign-in page (the POST is handled by Spring Security form login). */
@Controller
public class LoginPageController {

    private final BootstrapService bootstrap;

    public LoginPageController(BootstrapService bootstrap) {
        this.bootstrap = bootstrap;
    }

    @GetMapping("/login")
    public String login() {
        return bootstrap.pending() ? "redirect:/setup" : "login";
    }
}
