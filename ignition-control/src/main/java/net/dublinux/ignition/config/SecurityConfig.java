package net.dublinux.ignition.config;

import net.dublinux.ignition.security.TokenAuthenticationFilter;
import net.dublinux.ignition.security.TokenResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, TokenResolver resolver) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/login", "/logout",
                                "/css/**", "/favicon.ico").permitAll()
                        .requestMatchers(HttpMethod.POST, "/deploy", "/undeploy").hasRole("DEPLOY")
                        .requestMatchers("/z/**").hasRole("ZONE")
                        .anyRequest().hasRole("PLATFORM"))
                // SCAFFOLD: CSRF is off. The consoles are behind per-token auth on
                // a trusted admin origin and the CI bridge is bearer-only. Re-enable
                // with proper Thymeleaf form-token wiring before this replaces
                // ign-control.py (DESIGN.md cutover).
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?out"))
                .addFilterBefore(new TokenAuthenticationFilter(resolver),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
