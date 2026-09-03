package net.dublinux.ignition.web;

import net.dublinux.ignition.config.IgnitionProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(assignableTypes = {PlatformConsoleController.class, ZoneConsoleController.class, RosterController.class})
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
    public Object principal() {
        var p = CurrentPrincipal.get();
        return p == null ? "" : p.toString();
    }
}
