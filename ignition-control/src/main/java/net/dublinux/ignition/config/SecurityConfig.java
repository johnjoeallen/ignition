package net.dublinux.ignition.config;

import net.dublinux.ignition.auth.IgnitionUserDetailsService;
import net.dublinux.ignition.security.DeployTokenFilter;
import net.dublinux.ignition.security.ZoneAuthorizationManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    DaoAuthenticationProvider authenticationProvider(IgnitionUserDetailsService uds, PasswordEncoder enc) {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider(uds);
        p.setPasswordEncoder(enc);
        return p;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, DeployTokenFilter deployFilter,
                                    ZoneAuthorizationManager zoneAuthz) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/error",
                                "/login", "/logout", "/setup", "/setup/**",
                                "/signup", "/activate", "/forgot", "/reset",
                                "/css/**", "/js/**", "/vendor/**", "/img/**", "/favicon.ico").permitAll()
                        .requestMatchers(HttpMethod.POST, "/deploy", "/undeploy").hasRole("DEPLOY")
                        // The landing page every login redirects to — has to be reachable by
                        // anyone signed in (not just platform admins), or a member with no
                        // platform-admin flag would authenticate fine and then immediately hit
                        // this filter chain's catch-all as a 403, which looks exactly like a
                        // failed login. PlatformConsoleController itself branches the content by
                        // role (admin Teams list vs. "your teams" for everyone else).
                        .requestMatchers("/").authenticated()
                        // The team console + its actions — any member reaches their own team
                        // (checked against the {slug} path variable, see
                        // ZoneAuthorizationManager); a platform admin reaches everything else.
                        // Zone-*management* actions (status/destroy/move) stay under /zones/{slug}/...
                        // too but keep distinct final segments, so they fall through to the
                        // PLATFORM_ADMIN catch-all below untouched.
                        .requestMatchers("/zones/{slug}").access(zoneAuthz)
                        .requestMatchers("/zones/{slug}/members/**", "/zones/{slug}/apps/**",
                                "/zones/{slug}/repos/**", "/zones/{slug}/runner/**").access(zoneAuthz)
                        .requestMatchers("/roster/**", "/sweep").hasAuthority("PLATFORM_ADMIN")
                        .anyRequest().hasAuthority("PLATFORM_ADMIN"))
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login?error"))
                .logout(out -> out.logoutUrl("/logout").logoutSuccessUrl("/login?out"))
                .csrf(csrf -> csrf.ignoringRequestMatchers("/deploy", "/undeploy"))
                .httpBasic(AbstractHttpConfigurer::disable)
                .addFilterBefore(deployFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
