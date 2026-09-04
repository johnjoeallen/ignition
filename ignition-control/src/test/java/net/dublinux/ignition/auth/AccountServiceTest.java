package net.dublinux.ignition.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import net.dublinux.ignition.auth.AppUser.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AccountServiceTest {

    private final Map<UUID, AppUser> userStore = new ConcurrentHashMap<>();
    private final Map<String, AuthToken> tokenStore = new ConcurrentHashMap<>();
    private AppUserRepository users;
    private AuthTokenRepository tokens;
    private MailService mail;
    private AccountService svc;

    @BeforeEach
    void setUp() {
        users = mock(AppUserRepository.class);
        tokens = mock(AuthTokenRepository.class);
        mail = mock(MailService.class);
        PasswordEncoder pw = mock(PasswordEncoder.class);
        when(pw.encode(anyString())).thenAnswer(i -> "{enc}" + i.getArgument(0));

        when(users.save(any(AppUser.class))).thenAnswer(i -> {
            AppUser u = i.getArgument(0);
            userStore.put(u.id(), u);
            return u;
        });
        when(users.findById(any(UUID.class))).thenAnswer(i -> Optional.ofNullable(userStore.get(i.getArgument(0))));
        when(users.findByEmailIgnoreCase(anyString())).thenAnswer(i -> userStore.values().stream()
                .filter(u -> u.email().equalsIgnoreCase(i.getArgument(0))).findFirst());
        when(users.existsByEmailIgnoreCase(anyString())).thenAnswer(i -> userStore.values().stream()
                .anyMatch(u -> u.email().equalsIgnoreCase(i.getArgument(0))));
        when(users.findAll()).thenAnswer(i -> List.copyOf(userStore.values()));

        when(tokens.save(any(AuthToken.class))).thenAnswer(i -> {
            AuthToken t = i.getArgument(0);
            tokenStore.put(t.userId() + ":" + t.purpose(), t);
            return t;
        });
        doNothing().when(tokens).deleteByUserIdAndPurpose(any(), any());

        svc = new AccountService(users, tokens, mail, pw);
    }

    @Test
    void signupFilesRequestWithNoMailYet() {
        svc.signup("Alex@example.com");
        assertThat(userStore.values()).singleElement()
                .satisfies(u -> {
                    assertThat(u.email()).isEqualTo("alex@example.com");
                    assertThat(u.status()).isEqualTo(Status.PENDING_APPROVAL);
                    assertThat(u.isPreapproved()).isFalse();
                });
        // nothing goes out until a platform admin approves the request
        verify(mail, never()).sendActivation(anyString(), anyString());
    }

    @Test
    void approveSendsActivationAndPreapproves() {
        AppUser u = new AppUser("alex@example.com", Status.PENDING_APPROVAL, false, false);
        userStore.put(u.id(), u);
        svc.approve(u.id());
        assertThat(u.status()).isEqualTo(Status.PENDING_VERIFICATION);
        assertThat(u.isPreapproved()).isTrue();
        verify(mail).sendActivation(eq("alex@example.com"), anyString());
    }

    @Test
    void signupForExistingAddressSendsNothing() {
        userStore.put(UUID.randomUUID(),
                new AppUser("dup@example.com", Status.ACTIVE, false, false));
        svc.signup("dup@example.com");
        verify(mail, never()).sendActivation(anyString(), anyString());
    }

    @Test
    void invitedUserActivatesStraightToActive() {
        svc.invite("inv@example.com");
        AppUser u = userStore.values().iterator().next();
        u.activate("{enc}pw");
        assertThat(u.status()).isEqualTo(Status.ACTIVE);
    }

    @Test
    void unapprovedActivationLandsInPendingApproval() {
        // AppUser.activate()'s own defensive fallback — not reachable through
        // AccountService today (approve() always sets preapproved before a
        // PENDING_VERIFICATION user can get this far), but the entity
        // shouldn't silently activate an unapproved account if that ever changes.
        AppUser u = new AppUser("s@example.com", Status.PENDING_VERIFICATION, false, false);
        u.activate("{enc}pw");
        assertThat(u.status()).isEqualTo(Status.PENDING_APPROVAL);
    }

    @Test
    void rejectsShortPassword() {
        assertThatThrownBy(() -> svc.activate("whatever", "short"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lastPlatformAdminCannotBeDemoted() {
        AppUser admin = new AppUser("boss@example.com", Status.ACTIVE, true, true);
        userStore.put(admin.id(), admin);
        // acted on by someone else (a null actor here) — otherwise the self-revocation
        // guard below would fire first and mask what this test is actually checking.
        assertThatThrownBy(() -> svc.setPlatformAdmin(admin.id(), false, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("last platform admin");
    }

    @Test
    void cannotRevokeYourOwnPlatformAdmin() {
        AppUser admin = new AppUser("boss@example.com", Status.ACTIVE, true, true);
        AppUser other = new AppUser("deputy@example.com", Status.ACTIVE, true, true);
        userStore.put(admin.id(), admin);
        userStore.put(other.id(), other);
        // another admin exists, so this isn't the last-admin case — it's still refused.
        assertThatThrownBy(() -> svc.setPlatformAdmin(admin.id(), false, admin.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("your own");
    }
}
