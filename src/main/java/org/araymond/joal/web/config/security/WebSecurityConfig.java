package org.araymond.joal.web.config.security;

import org.araymond.joal.web.annotations.ConditionalOnWebUi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Created by raymo on 29/07/2017.
 */
@ConditionalOnWebUi
@EnableWebSecurity
@Configuration
public class WebSecurityConfig {
    private final boolean shouldDisableFrameOptions;

    public WebSecurityConfig(
            @Value("${joal.iframe.enabled:false}") final boolean shouldDisableFrameOptions
    ) {
        this.shouldDisableFrameOptions = shouldDisableFrameOptions;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        if (this.shouldDisableFrameOptions) {
            http.headers().frameOptions().disable();
        }

    return http
        .csrf().disable()
        .httpBasic().disable()
        .formLogin().disable()
        .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
        .authorizeRequests()
        .antMatchers("/").permitAll()
        .antMatchers("/*").permitAll()
        .antMatchers("/assets/**").permitAll()
        .antMatchers("/ws").permitAll()
        .antMatchers("/api/client-generator/**").permitAll()
        .anyRequest().denyAll()
        .and().build();
    }

    // Provide an empty UserDetailService to prevent spring from injecting a default one with a valid random password.
    @Bean
    public InMemoryUserDetailsManager userDetailsService() {
        return new InMemoryUserDetailsManager();
    }

}
