package net.dublinux.ignition.app;

/**
 * An app's {@code compose.yaml} can't be deployed as written — a disallowed key
 * ({@code privileged}, a bind mount, a blocked image, …), a missing or ambiguous
 * {@code ignition.web} service, or malformed YAML. Maps to HTTP 422; the message
 * is safe to show the team (it names the service/key and points at
 * {@code compose.override.yaml}).
 */
public class ComposeSpecException extends RuntimeException {
    public ComposeSpecException(String message) {
        super(message);
    }
}
