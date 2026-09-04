package net.dublinux.ignition.web;

import net.dublinux.ignition.auth.AccountService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Self-signup, email verification, and password reset — all unauthenticated. */
@Controller
public class SignupController {

    private final AccountService accounts;

    public SignupController(AccountService accounts) {
        this.accounts = accounts;
    }

    // --- signup -----------------------------------------------------------

    @GetMapping("/signup")
    public String signupForm() {
        return "signup";
    }

    @PostMapping("/signup")
    public String signup(@RequestParam String email, Model model) {
        try {
            accounts.signup(email);
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return "signup";
        }
        model.addAttribute("sent", true);
        return "signup";
    }

    // --- activation -----------------------------------------------------

    @GetMapping("/activate")
    public String activateForm(@RequestParam String token, Model model) {
        model.addAttribute("token", token);
        return "activate";
    }

    @PostMapping("/activate")
    public String activate(@RequestParam String token,
                           @RequestParam String password,
                           @RequestParam String password2,
                           Model model) {
        model.addAttribute("token", token);
        if (!password.equals(password2)) {
            model.addAttribute("error", "passwords don't match");
            return "activate";
        }
        try {
            var u = accounts.activate(token, password);
            return "redirect:/login?" + (u.status().name().equals("ACTIVE") ? "activated" : "pending");
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return "activate";
        }
    }

    // --- reset --------------------------------------------------------

    @GetMapping("/forgot")
    public String forgotForm() {
        return "forgot";
    }

    @PostMapping("/forgot")
    public String forgot(@RequestParam String email, Model model) {
        accounts.requestReset(email);
        model.addAttribute("sent", true);
        return "forgot";
    }

    @GetMapping("/reset")
    public String resetForm(@RequestParam String token, Model model) {
        model.addAttribute("token", token);
        return "reset";
    }

    @PostMapping("/reset")
    public String reset(@RequestParam String token,
                        @RequestParam String password,
                        @RequestParam String password2,
                        Model model) {
        model.addAttribute("token", token);
        if (!password.equals(password2)) {
            model.addAttribute("error", "passwords don't match");
            return "reset";
        }
        try {
            accounts.resetPassword(token, password);
            return "redirect:/login?reset";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return "reset";
        }
    }
}
