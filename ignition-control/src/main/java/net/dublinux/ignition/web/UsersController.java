package net.dublinux.ignition.web;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import net.dublinux.ignition.auth.AccountService;
import net.dublinux.ignition.auth.AppUser;
import net.dublinux.ignition.auth.AppUserRepository;
import net.dublinux.ignition.auth.CurrentUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Platform-wide account management: everyone with an Ignition login, their
 * status, and the platform-admin flag. Team membership (who's a member/admin
 * of a given team) is on that team's own console instead — see
 * {@link ZoneConsoleController}.
 */
@Controller
public class UsersController {

    private final AppUserRepository users;
    private final AccountService accounts;
    private final CurrentUser currentUser;

    public UsersController(AppUserRepository users, AccountService accounts, CurrentUser currentUser) {
        this.users = users;
        this.accounts = accounts;
        this.currentUser = currentUser;
    }

    @GetMapping("/users")
    public String users(Model model) {
        List<AppUser> all = users.findAll().stream()
                .sorted(Comparator.comparing(AppUser::email))
                .toList();
        model.addAttribute("users", all);
        model.addAttribute("currentUserId", currentUser.get().map(AppUser::id).orElse(null));
        return "users";
    }

    @PostMapping("/users/invite")
    public String invite(@RequestParam String email) {
        try {
            AppUser u = accounts.invite(email);
            return redirect(u.email() + " invited — they'll get an activation email");
        } catch (RuntimeException e) {
            return redirect(e.getMessage());
        }
    }

    @PostMapping("/users/{id}/approve")
    public String approve(@PathVariable UUID id) {
        return act(id, () -> accounts.approve(id), "approved — activation email sent");
    }

    @PostMapping("/users/{id}/admin")
    public String setAdmin(@PathVariable UUID id, @RequestParam boolean value) {
        UUID actingUserId = currentUser.get().map(AppUser::id).orElse(null);
        return act(id, () -> accounts.setPlatformAdmin(id, value, actingUserId),
                value ? "made a platform admin" : "platform admin revoked");
    }

    @PostMapping("/users/{id}/disable")
    public String setDisabled(@PathVariable UUID id, @RequestParam boolean value) {
        return act(id, () -> accounts.setDisabled(id, value), value ? "disabled" : "re-enabled");
    }

    private String act(UUID id, Runnable action, String okMsg) {
        try {
            action.run();
            return redirect(okMsg);
        } catch (RuntimeException e) {
            return redirect(e.getMessage());
        }
    }

    private static String redirect(String msg) {
        return "redirect:/users?m=" + java.net.URLEncoder.encode(msg, java.nio.charset.StandardCharsets.UTF_8);
    }
}
