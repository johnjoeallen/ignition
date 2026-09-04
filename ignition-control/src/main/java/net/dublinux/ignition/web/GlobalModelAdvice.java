package net.dublinux.ignition.web;

import java.util.Optional;

import net.dublinux.ignition.config.IgnitionProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(assignableTypes = {PlatformConsoleController.class, ZoneConsoleController.class,
        RosterController.class, UsersController.class})
public class GlobalModelAdvice {

    private final IgnitionProperties props;
    private final Optional<BuildProperties> buildProperties;

    public GlobalModelAdvice(IgnitionProperties props, Optional<BuildProperties> buildProperties) {
        this.props = props;
        this.buildProperties = buildProperties;
    }

    /**
     * What's actually running, shown small in the sidebar — the exact question
     * "did my redeploy take" keeps coming up (an old cached image, a pinned
     * IGN_CONTROL_VERSION, a CI build that hadn't finished yet), and eyeballing
     * a version string beats re-deriving it from docker inspect every time.
     * Empty outside a real container build (dev, tests) — no build-info.properties
     * on the classpath then, so there's nothing false to show instead.
     */
    @ModelAttribute("buildVersion")
    public String buildVersion() {
        return buildProperties.map(BuildProperties::getVersion).orElse("dev");
    }

    @ModelAttribute("buildCommit")
    public String buildCommit() {
        return buildProperties.map(bp -> bp.get("git.commit")).filter(s -> s != null && !s.isBlank())
                .map(s -> s.length() > 7 ? s.substring(0, 7) : s)
                .orElse("");
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
