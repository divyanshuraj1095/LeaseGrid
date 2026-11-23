package com.leasegrid.leasegrid.auth;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.List;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Configuration
public class SecurityConfig {
    @Bean SecretKey signingKey(@Value("${JWT_SECRET}") String encoded) {
        byte[] key = Base64.getDecoder().decode(encoded);
        if (key.length < 32) throw new IllegalArgumentException("JWT_SECRET requires 32 random bytes in Base64");
        return new SecretKeySpec(key,"HmacSHA256");
    }
    @Bean JwtDecoder decoder(SecretKey key) { return NimbusJwtDecoder.withSecretKey(key).build(); }
    @Bean JwtEncoder encoder(SecretKey key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }
    @Bean BCryptPasswordEncoder passwords() { return new BCryptPasswordEncoder(); }
    @Bean UrlBasedCorsConfigurationSource apiCors(@Value("${LEASEGRID_CORS_ORIGINS:http://localhost:5173,https://lease-grid-seven.vercel.app}") List<String> origins) {
        var policy = new CorsConfiguration();
        policy.setAllowedOrigins(origins);
        policy.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        policy.setAllowedHeaders(List.of("Content-Type", "Authorization", "Idempotency-Key"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", policy);
        return source;
    }
    @Bean SecurityFilterChain security(HttpSecurity http, UrlBasedCorsConfigurationSource apiCors) throws Exception {
        return http.cors(c->c.configurationSource(apiCors)).csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a->a.requestMatchers("/api/v1/auth/register","/api/v1/auth/login").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness",
                        "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/swagger-config", "/docs/LeaseGrid_OpenAPI_v1.yaml").permitAll()
                .anyRequest().authenticated()).oauth2ResourceServer(o->o.jwt(j->{})).build();
    }
}
