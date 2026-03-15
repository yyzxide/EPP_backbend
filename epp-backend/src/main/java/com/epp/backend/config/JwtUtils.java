package com.epp.backend.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;

/**
 * JWT 工具类 — 负责 Token 的签发与校验
 * 
 * 底层逻辑:
 *   1. 签发: {"deviceId": "A001"} + SecretKey → HMAC-SHA256 签名 → Token
 *   2. 校验: Token + SecretKey → 重新计算签名并对比
 * 
 * C++ 类比: 相当于一个封装了 OpenSSL HMAC-SHA256 的工具类，保证数据不可篡改。
 */
@Component
public class JwtUtils {

    @Value("${epp.jwt.secret}")
    private String secret;

    @Value("${epp.jwt.expiration}")
    private long expiration;

    private Key key;

    @PostConstruct
    public void init() {
        // HMAC-SHA 算法要求密钥长度至少为 256 位 (32 字节)
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
    }

    /**
     * 为设备生成唯一的通行证 (Token)
     * 
     * @param deviceId 设备唯一标识
     * @return 签名的 JWT 字符串
     */
    public String createToken(String deviceId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .setSubject(deviceId)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 验证 Token 的合法性并提取 deviceId
     * 
     * @param token 客户端传来的 Token
     * @return 合法的 deviceId
     * @throws RuntimeException 如果 Token 过期、被篡改或签名不匹配，会抛出异常
     */
    public String validateAndGetId(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            return claims.getSubject();
        } catch (Exception e) {
            // 签名不匹配或过期都会进这里
            throw new RuntimeException("JWT 验证失败: " + e.getMessage());
        }
    }
}
