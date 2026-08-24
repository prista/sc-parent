package com.drm.sandbox.manager.security;

import com.drm.sandbox.manager.entity.Authority;
import com.drm.sandbox.manager.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
public class MUserDetailService implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * Loads the user with the given username and adapts it to Spring Security's
     * {@link UserDetails} representation.
     *
     * @param username the username identifying the user to load
     * @return a fully populated {@link UserDetails} for the requested user
     * @throws UsernameNotFoundException if no user matches the given username
     */
    @Override
    // Keeps the persistence context open so the lazy {@code authorities} collection
    // can be initialized here instead of fetching it eagerly via @ManyToMany(fetch = EAGER).
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(final String username) throws UsernameNotFoundException {
        return this.userRepository.findByUsername(username)
                .map(user -> User.builder()
                        .username(user.getUsername())
                        .password(user.getPassword())
                        // Map each persisted Authority to a Spring Security granted authority.
                        .authorities(user.getAuthorities().stream()
                                .map(Authority::getAuthority)
                                .map(SimpleGrantedAuthority::new).toList())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("User %s not found".formatted(username)));
    }
}
