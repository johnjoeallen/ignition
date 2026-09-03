package net.dublinux.ignition.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.dublinux.ignition.security.IgnitionPrincipal;
import net.dublinux.ignition.security.TokenAuthenticationFilter;
import net.dublinux.ignition.security.TokenResolver;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class LoginController {

    private final TokenResolver resolver;

    public LoginController(TokenResolver resolver) {
        this.resolver = resolver;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @PostMapping("/login")
    public String submit(@RequestParam String token,
                         HttpServletRequest request,
                         HttpServletResponse response) {
        return resolver.resolve(token).map(principal -> {
            request.getSession(true).setAttribute(TokenAuthenticationFilter.SESSION_KEY, token);
            Cookie cookie = new Cookie(TokenAuthenticationFilter.COOKIE_NAME, token);
            cookie.setPath("/");
            cookie.setHttpOnly(true);
            cookie.setSecure(request.isSecure());
            response.addCookie(cookie);
            return principal.kind() == IgnitionPrincipal.Kind.ZONE ? "redirect:/z" : "redirect:/";
        }).orElse("redirect:/login?error");
    }
}
