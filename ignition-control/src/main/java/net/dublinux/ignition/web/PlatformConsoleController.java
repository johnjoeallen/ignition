package net.dublinux.ignition.web;

import java.util.List;
import java.util.Map;

import net.dublinux.ignition.app.AppService;
import net.dublinux.ignition.auth.CurrentUser;
import net.dublinux.ignition.auth.ZoneAccessService;
import net.dublinux.ignition.node.Node;
import net.dublinux.ignition.node.NodeService;
import net.dublinux.ignition.provisioning.ProvisioningService;
import net.dublinux.ignition.zone.ZoneService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The platform-admin views on the one console, at the bare {@code <BASE_DOMAIN>}.
 * Nodes (register / drain / remove), zones (provision / move / destroy), apps
 * (stop). The roster (bulk create/destroy) and a "sweep now" button land next
 * (DESIGN.md step 7).
 */
@Controller
public class PlatformConsoleController {

    private final NodeService nodes;
    private final ZoneService zones;
    private final AppService apps;
    private final ProvisioningService provisioning;
    private final CurrentUser currentUser;
    private final ZoneAccessService access;

    public PlatformConsoleController(NodeService nodes, ZoneService zones, AppService apps,
                                     ProvisioningService provisioning, CurrentUser currentUser,
                                     ZoneAccessService access) {
        this.nodes = nodes;
        this.zones = zones;
        this.apps = apps;
        this.provisioning = provisioning;
        this.currentUser = currentUser;
        this.access = access;
    }

    @GetMapping("/nodes")
    public String nodes(Model model) {
        List<NodeRow> nodeRows = nodes.list().stream().map(n -> {
            var a = nodes.allocation(n.name());
            return new NodeRow(n, a.cpus(), a.memGb(), a.zones());
        }).toList();
        model.addAttribute("nodes", nodeRows);
        return "nodes";
    }

    /**
     * The landing page every login redirects to — has to make sense for
     * whoever's signed in, not just a platform admin. A platform admin gets
     * the full Teams management page; anyone else (this is the only page
     * they can reach that isn't a specific team's own console) gets a plain
     * list of the teams they're actually on, since they have no reason to
     * see or manage every team on the platform.
     */
    @GetMapping("/")
    public String home(Model model) {
        if (currentUser.isPlatformAdmin()) {
            return teamsAdmin(model);
        }
        var userId = currentUser.get().map(u -> u.id()).orElse(null);
        model.addAttribute("myZones", userId == null ? List.of() : access.zonesFor(userId));
        return "my-teams";
    }

    private String teamsAdmin(Model model) {
        List<NodeRow> nodeRows = nodes.list().stream().map(n -> {
            var a = nodes.allocation(n.name());
            return new NodeRow(n, a.cpus(), a.memGb(), a.zones());
        }).toList();
        Map<String, ProvisioningService.Status> provisioningRows = new java.util.LinkedHashMap<>();
        zones.list().forEach(z ->
                provisioning.status(z.slug()).ifPresent(s -> provisioningRows.put(z.slug(), s)));
        model.addAttribute("nodeNames", nodeRows.stream().map(r -> r.node().name()).toList());
        model.addAttribute("zones", zones.list());
        model.addAttribute("provisioning", provisioningRows);
        return "teams";
    }

    @GetMapping("/zones/new")
    public String newZone() {
        return "zone-form";
    }

    @PostMapping("/zones")
    public String createZone(@RequestParam String slug,
                             @RequestParam(required = false, defaultValue = "") String node,
                             @RequestParam(required = false, defaultValue = "") String label,
                             Model model) {
        try {
            var creator = currentUser.get().map(u -> u.id()).orElse(null);
            provisioning.submit(slug.strip(), node.strip(), label.strip(), creator);
            return "redirect:/?m=provisioning+" + slug.strip();
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
            return "zone-form";
        }
    }

    @GetMapping("/zones/{slug}/status")
    @ResponseBody
    public ResponseEntity<Map<String, String>> zoneStatus(@PathVariable String slug) {
        return provisioning.status(slug)
                .map(s -> ResponseEntity.ok(Map.of("state", s.state().name(), "message", s.message())))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/zones/{slug}/destroy")
    public String destroyZone(@PathVariable String slug) {
        try {
            zones.destroy(slug, false);
            return "redirect:/?m=" + slug + "+destroyed";
        } catch (RuntimeException e) {
            return "redirect:/?m=" + enc(e.getMessage());
        }
    }

    @PostMapping("/zones/{slug}/move")
    public String moveZone(@PathVariable String slug, @RequestParam String node) {
        try {
            zones.prepareMove(slug, node.strip());
            provisioning.submit(slug, node.strip(), "", null);
            return "redirect:/?m=moving+" + slug + "+to+" + node.strip();
        } catch (RuntimeException e) {
            return "redirect:/?m=" + enc(e.getMessage());
        }
    }

    @PostMapping("/apps/{zone}/{name}/delete")
    public String stopApp(@PathVariable String zone, @PathVariable String name) {
        try {
            apps.undeploy(zone, name);
            return "redirect:/z?z=" + enc(zone) + "&m=app+" + name + "+stopped";
        } catch (RuntimeException e) {
            return "redirect:/z?z=" + enc(zone) + "&m=" + enc(e.getMessage());
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8);
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
            return "redirect:/nodes?m=node+" + name + "+registered";
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
            return "node-form";
        }
    }

    @GetMapping("/nodes/{name}/edit")
    public String editNode(@PathVariable String name, Model model) {
        model.addAttribute("node", nodes.get(name));
        return "node-edit";
    }

    @PostMapping("/nodes/{name}")
    public String updateNode(@PathVariable String name,
                             @RequestParam String dockerHost,
                             @RequestParam double cpus,
                             @RequestParam double memGb,
                             @RequestParam(required = false, defaultValue = "") String labels,
                             Model model) {
        try {
            List<String> labelList = labels.isBlank() ? List.of() : List.of(labels.split("\\s*,\\s*"));
            nodes.update(name, dockerHost, cpus, memGb, labelList);
            return "redirect:/nodes?m=node+" + name + "+updated";
        } catch (RuntimeException e) {
            model.addAttribute("node", nodes.get(name));
            model.addAttribute("error", e.getMessage());
            return "node-edit";
        }
    }

    @PostMapping("/nodes/{name}/drain")
    public String drain(@PathVariable String name) {
        nodes.setState(name, Node.State.DRAINING);
        return "redirect:/nodes?m=" + name + "+draining";
    }

    @PostMapping("/nodes/{name}/undrain")
    public String undrain(@PathVariable String name) {
        nodes.setState(name, Node.State.ACTIVE);
        return "redirect:/nodes?m=" + name + "+active";
    }

    @PostMapping("/nodes/{name}/delete")
    public String delete(@PathVariable String name, Model model) {
        try {
            nodes.remove(name);
            return "redirect:/nodes?m=" + name + "+removed";
        } catch (RuntimeException e) {
            return "redirect:/nodes?m=" + java.net.URLEncoder.encode(e.getMessage(),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** A node plus its current allocation, for the table. */
    public record NodeRow(Node node, double allocCpus, double allocMemGb, int zoneCount) {}
}
