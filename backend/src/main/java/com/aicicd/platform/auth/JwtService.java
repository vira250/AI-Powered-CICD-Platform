package com.aicicd.platform.auth;

import com.aicicd.platform.config.AppProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/** Issues/validates the platform's own session JWT (HS256). */
@Service
public class JwtService {

    private final SecretKey key;

    public JwtService(AppProperties props) {
        byte[] raw = props.sessionSecret().getBytes(StandardCharsets.UTF_8);
        // HS256 needs >= 32 bytes; pad deterministically for short secrets
        byte[] padded = new byte[Math.max(32, raw.length)];
        System.arraycopy(raw, 0, padded, 0, raw.length);
        this.key = Keys.hmacShaKeyFor(padded);
    }

    public String issue(String githubLogin) {
        return Jwts.builder()
                .subject(githubLogin)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(12, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    public String validate(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload().getSubject();
    }
}
