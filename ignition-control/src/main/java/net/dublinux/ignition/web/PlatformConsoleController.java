package net.dublinux.ignition.web;

import java.util.List;

import net.dublinux.ignition.app.AppService;
import net.dublinux.ignition.node.Node;
import net.dublinux.ignition.node.NodeService;
import net.dublinux.ignition.zone.ZoneService;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.stereotype.Controller;

/**
 * The platform-admin console at {@code admin.<BASE_DOMAIN>}. Read views are
 * live; node registration is the first write vertical slice. Zone
 * create / move / destroy and roster land with {@code ProvisioningService}.
 */
@Controller
public class PlatformConsoleController {

    private final NodeService nodes;
    private final ZoneService zones;
    private final AppService apps;

    public PlatformConsoleController(NodeService nodes, ZoneService zones, AppService apps) {
        this.nodes = nodes;
        this.zones = zones;
        this.apps = apps;
    }

    @GetMapping("/")
    public String platform(Model model) {
        List<NodeRow> nodeRows = nodes.list().stream().map(n -> {
            var a = nodes.allocation(n.name());
            return new NodeRow(n, a.cpus(), a.memGb(), a.zones());
        }).toList();
        model.addAttribute("nodes", nodeRows);
        model.addAttribute("zones", zones.list());
        model.addAttribute("apps", apps.list());
        return "platform";
    }

    @GetMapping("/nodes/new")
    public String newNode() {
        return "node-form";
    }

    @PostMapping("/nodes")
    public String registerNode(@RequestParam String name,
                               @RequestParam String dockerHost,
                               @RequestParam double cpus,
                               @RequestParam double memGb,
                               @RequestParam(required = false, defaultValue = "") String labels,
                               Model model) {
        try {
            List<String> labelList = labels.isBlank() ? List.of() : List.of(labels.split("\\s*,\\s*"));
            nodes.register(name, dockerHost, cpus, memGb, labelList);
            return "redirect:/?m=node+" + name + "+registered";
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
            return "node-form";
        }
    }

    @PostMapping("/nodes/{name}/drain")
    public String drain(@org.springframework.web.bind.annotation.PathVariable String name) {
        nodes.setState(name, Node.State.DRAINING);
        return "redirect:/?m=" + name + "+draining";
    }

    @PostMapping("/nodes/{name}/undrain")
    public String undrain(@org.springframework.web.bind.annotation.PathVariable String name) {
        nodes.setState(name, Node.State.ACTIVE);
        return "redirect:/?m=" + name + "+active";
    }

    @PostMapping("/nodes/{name}/delete")
    public String delete(@org.springframework.web.bind.annotation.PathVariable String name, Model model) {
        try {
            nodes.remove(name);
            return "redirect:/?m=" + name + "+removed";
        } catch (RuntimeException e) {
            return "redirect:/?m=" + java.net.URLEncoder.encode(e.getMessage(),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** A node plus its current allocation, for the table. */
    public record NodeRow(Node node, double allocCpus, double allocMemGb, int zoneCount) {}
}
