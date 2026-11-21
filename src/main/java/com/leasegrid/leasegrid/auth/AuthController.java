package com.leasegrid.leasegrid.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public record Registration(@Email @NotBlank @Size(max=320) String email,
            @NotBlank @Size(max=120) String displayName,@Size(min=8,max=72) @NotNull String password) {}
    public record Login(@NotBlank @Email String email,@NotBlank String password) {}
    private final JdbcClient db;
    private final BCryptPasswordEncoder passwords;
    private final JwtEncoder tokens;
    public AuthController(JdbcClient db, BCryptPasswordEncoder passwords, JwtEncoder tokens) {
        this.db=db; this.passwords=passwords; this.tokens=tokens;
    }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> register(@Valid @RequestBody Registration request) {
        if(request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return db.sql("""
                insert into users(email,display_name,password_hash) values(lower(?),?,?)
                on conflict do nothing returning id,email,display_name as "displayName",system_role as "systemRole"
                """).params(request.email(),request.displayName(),passwords.encode(request.password()))
                .query().listOfRows().stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT));
    }
    @PostMapping("/login") public Map<String,Object> login(@Valid @RequestBody Login request) {
        var rows=db.sql("select id,password_hash from users where lower(email)=lower(?) and account_status='ACTIVE'")
                .param(request.email()).query().listOfRows();
        if(rows.isEmpty() || !passwords.matches(request.password(),rows.get(0).get("password_hash").toString()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Instant now=Instant.now();
        var claims=JwtClaimsSet.builder().subject(rows.get(0).get("id").toString()).issuedAt(now).expiresAt(now.plusSeconds(900)).build();
        String token=tokens.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
        return Map.of("accessToken",token,"tokenType","Bearer","expiresIn",900);
    }
}
