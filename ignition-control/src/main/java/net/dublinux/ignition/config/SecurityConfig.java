package net.dublinux.ignition.config;

import net.dublinux.ignition.auth.IgnitionUserDetailsService;
import net.dublinux.ignition.security.DeployTokenFilter;
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
    SecurityFilterChain filterChain(HttpSecurity http, DeployTokenFilter deployFilter) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/error",
                                "/login", "/logout", "/setup", "/setup/**",
                                "/signup", "/activate", "/forgot", "/reset",
                                "/css/**", "/vendor/**", "/img/**", "/favicon.ico").permitAll()
                        .requestMatchers(HttpMethod.POST, "/deploy", "/undeploy").hasRole("DEPLOY")
                        // AUTH-DESIGN step 6 opens /z to zone members; for now platform-only.
                        .requestMatchers("/z/**", "/roster/**", "/sweep").hasAuthority("PLATFORM_ADMIN")
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
