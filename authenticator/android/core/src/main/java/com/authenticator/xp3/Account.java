package com.authenticator.xp3;

import com.xp3.common.Json;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * One two-factor account and its codes: a time-based one-time password
 * (TOTP, RFC 6238) over HMAC-SHA1, SHA-256 or SHA-512 (RFC 4226's dynamic
 * truncation), as the command center keeps it.
 */
public final class Account {

    /** The command center's id; null until it has one. */
    public final String id;
    public final String issuer;
    public final String account;
    /** Base32, tidied. */
    public final String secret;
    /** SHA1, SHA256 or SHA512. */
    public final String algorithm;
    public final int digits;
    /** Seconds each code lasts. */
    public final int period;

    private final byte[] key;

    public Account(String id, String issuer, String account, String secret, String algorithm, int digits, int period) {
        this.id = id;
        this.issuer = issuer == null ? "" : issuer.trim();
        this.account = account == null ? "" : account.trim();
        this.secret = Base32.tidy(secret);
        this.algorithm = algorithm(algorithm);
        this.digits = digits == 0 ? 6 : digits;
        this.period = period == 0 ? 30 : period;
        if (this.issuer.length() == 0 && this.account.length() == 0) throw new IllegalArgumentException("The account needs a name");
        if (this.digits < 6 || this.digits > 8) throw new IllegalArgumentException("Codes have 6 to 8 digits");
        if (this.period < 1 || this.period > 300) throw new IllegalArgumentException("A code lasts 1 to 300 seconds");
        this.key = Base32.decode(this.secret);
    }

    /** A new account, as typed or scanned, with the usual settings unless given. */
    public static Account of(String issuer, String account, String secret) {
        return new Account(null, issuer, account, secret, "SHA1", 6, 30);
    }

    private static String algorithm(String given) {
        String name = given == null ? "" : given.toUpperCase(Locale.US).replace("-", "").replace("HMAC", "");
        if (name.length() == 0 || name.equals("SHA1")) return "SHA1";
        if (name.equals("SHA256") || name.equals("SHA512")) return name;
        throw new IllegalArgumentException("Only SHA1, SHA256 and SHA512 codes are supported");
    }

    /** What the list leads with: the service, or the account when there is no service. */
    public String title() {
        return issuer.length() > 0 ? issuer : account;
    }

    /** What follows it, when there is a service to lead with. */
    public String detail() {
        return issuer.length() > 0 ? account : "";
    }

    /** The code at `millis`, zero-padded. */
    public String code(long millis) {
        long counter = Math.floorDiv(millis / 1000, (long) period);
        byte[] hash;
        try {
            Mac mac = Mac.getInstance("Hmac" + algorithm);
            mac.init(new SecretKeySpec(key, "Hmac" + algorithm));
            hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
            | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
        int modulus = 1;
        for (int i = 0; i < digits; i++) modulus *= 10;
        StringBuilder code = new StringBuilder(Integer.toString(binary % modulus));
        while (code.length() < digits) code.insert(0, '0');
        return code.toString();
    }

    /** The code as Authy shows it: "482 963", "1234 5678", "123 4567". */
    public static String spaced(String code) {
        int half = code.length() / 2;
        return code.substring(0, half) + " " + code.substring(half);
    }

    /** How much of the current code's life is left, from 1 down to 0. */
    public float left(long millis) {
        long periodMillis = period * 1000L;
        return (periodMillis - Math.floorMod(millis, periodMillis)) / (float) periodMillis;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = Json.map("issuer", issuer, "account", account, "secret", secret,
            "algorithm", algorithm, "digits", Integer.valueOf(digits), "period", Integer.valueOf(period));
        if (id != null) map.put("id", id);
        return map;
    }

    public static Account fromJson(Map<String, Object> map) {
        Double digits = Json.number(map, "digits");
        Double period = Json.number(map, "period");
        return new Account(Json.text(map, "id"), Json.text(map, "issuer"), Json.text(map, "account"),
            Json.string(map, "secret"), Json.text(map, "algorithm"),
            digits == null ? 0 : digits.intValue(), period == null ? 0 : period.intValue());
    }
}
