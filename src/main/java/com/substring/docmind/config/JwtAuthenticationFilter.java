package com.substring.docmind.config;

import com.substring.docmind.repository.UserRepository;
import com.substring.docmind.service.CustomUserDetail;
import com.substring.docmind.service.CustomUserDetailService;
import com.substring.docmind.service.JwtService;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates requests carrying a valid {@code Authorization: Bearer <jwt>} header.
 * Requests with a missing, expired or invalid token continue unauthenticated, and the
 * security rules then answer 401 for protected routes.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;

    private final CustomUserDetailService userDetailService;

    private final UserRepository userRepository;

    /**
     * Stores the authentication as a request attribute so it is still present on the
     * ASYNC dispatch of streaming (SSE) responses and on ERROR dispatches, which this
     * once-per-request filter does not run for.
     */
    private final SecurityContextRepository securityContextRepository = new RequestAttributeSecurityContextRepository();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = authHeader.substring(7);

        try {
            String username = jwtService.extractUsername(token);
            if (username != null && jwtService.isTokenValid(token, userDetailService.loadUserByUsername(username))) {
                var user = userRepository.findByUsername(username).orElseThrow(() -> new IllegalStateException("User not found"));
                var authentication = new UsernamePasswordAuthenticationToken(user, null, new CustomUserDetail(user).getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
                securityContextRepository.saveContext(context, request, response);
            }
        } catch (ExpiredJwtException e) {
            log.debug("Rejected expired JWT for {} {}", request.getMethod(), request.getRequestURI());
        } catch (Exception e) {
            log.warn("Rejected invalid JWT for {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
