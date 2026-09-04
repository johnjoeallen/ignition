package net.dublinux.ignition.web;

import net.dublinux.ignition.config.IgnitionProperties;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(assignableTypes = {PlatformConsoleController.class, ZoneConsoleController.class,
        RosterController.class, UsersController.class, LogsController.class})
public class GlobalModelAdvice {

    private final IgnitionProperties props;

    public GlobalModelAdvice(IgnitionProperties props) {
        this.props = props;
    }

    @ModelAttribute("baseDomain")
    public String baseDomain() {
        return props.getBaseDomain();
    }

    @ModelAttribute("principal")
    public String principal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "" : auth.getName();
    }

    /** Nodes/Teams/Users are platform-admin only — the shell hides them from a plain team member. */
    @ModelAttribute("isPlatformAdmin")
    public boolean isPlatformAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("PLATFORM_ADMIN"));
    }
}
