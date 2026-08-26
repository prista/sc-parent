package com.drm.sandbox.manager.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Configuration
public class SecurityConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests((authorize) -> authorize
                        .anyRequest().hasRole("MANAGER"))
                .oauth2Login(Customizer.withDefaults())
                .build();
    }

    /**
     * Custom {@link OAuth2UserService} that loads the OIDC user and flattens all
     * of their roles into a single authority list.
     *
     * <p>The default {@link OidcUserService} already resolves the authorities from the
     * token's {@code scope} and any mapped claims. On top of that, we additionally read
     * the {@code groups} claim, keep only the entries prefixed with {@code ROLE_}, and map
     * each one to a {@link SimpleGrantedAuthority}. Both sources are then merged into one
     * immutable list so that Spring Security can evaluate a single set of roles.</p>
     */
    @Bean
    public OAuth2UserService<OidcUserRequest, OidcUser> oAuth2UserService() {
        var oidcUserService = new OidcUserService();
        return userRequest -> {
            // Load the user via the default OIDC service (validates the ID token).
            var oidcUser = oidcUserService.loadUser(userRequest);

            // Merge two streams of authorities into one list:
            //   1) authorities already resolved from the ID token,
            //   2) roles taken from the "groups" claim (kept only if prefixed with ROLE_).
            var authorities =
                    Stream.concat(oidcUser.getAuthorities().stream(),
                                    Optional.ofNullable(oidcUser.getClaimAsStringList("groups"))
                                            .orElseGet(List::of)  // no groups claim -> empty list
                                            .stream()
                                            .filter(role -> role.startsWith("ROLE_"))
                                            .map(SimpleGrantedAuthority::new)
                                            .map(GrantedAuthority.class::cast))
                            .toList();  // collect into an immutable list

            // Rebuild the OidcUser with the combined authority list.
            return new DefaultOidcUser(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo());
        };
    }
}
