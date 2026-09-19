package com.slz.crm.common.untils;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import javax.crypto.SecretKey;

/** JWT工具类 */
public class JwtUntil {

  /**
   * 创建JWT
   *
   * @param key
   * @param ttl
   * @param claims
   * @return
   */
  public static String createJWT(String key, long ttl, Map<String, Object> claims) {

    long expMillis = System.currentTimeMillis() + ttl;
    Date exp = new Date(expMillis);

    SecretKey key1 = Keys.hmacShaKeyFor(key.getBytes(StandardCharsets.UTF_8));

    JwtBuilder builder = Jwts.builder().signWith(key1).claims(claims).expiration(exp);
    return builder.compact();
  }

  /**
   * 解析JWT
   *
   * @param key
   * @param token
   * @return
   */
  public static Claims parseJWT(String key, String token) {
    SecretKey key1 = Keys.hmacShaKeyFor(key.getBytes(StandardCharsets.UTF_8));

    JwtParser jwtParser = Jwts.parser().verifyWith(key1).build();
    Jws<Claims> jws = jwtParser.parseSignedClaims(token);
    return jws.getPayload();
  }
}
