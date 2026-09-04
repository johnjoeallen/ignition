package net.dublinux.ignition.web;

import net.dublinux.ignition.logging.RecentLogService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Platform-admin only, via the catch-all in SecurityConfig — see RecentLogService. */
@Controller
public class LogsController {

    private final RecentLogService logs;

    public LogsController(RecentLogService logs) {
        this.logs = logs;
    }

    @GetMapping("/logs")
    public String logs(Model model) {
        model.addAttribute("lines", logs.recent());
        return "logs";
    }
}
