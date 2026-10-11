package com.droiddeck.launcher.stores.amazon;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;

/** The PKCE values Amazon's device sign-in takes, generated the way the Amazon Games launcher does. */
public final class AmazonPkce {
    private static final String DEVICE_TYPE = "A2UMVHOX7UP4V7";

    private AmazonPkce() {}

    /** One per sign-in: a UUID as uppercase hex. */
    public static String generateDeviceSerial() { return UUID.randomUUID().toString().replace("-", "").toUpperCase(); }

    /** hex("serial#DEVICE_TYPE"). */
    public static String generateClientId(String serial) {
        byte[] bytes = (serial + "#" + DEVICE_TYPE).getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    public static String generateCodeVerifier() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    public static String generateCodeChallenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(hash, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    /** SHA-256 of the input as uppercase hex: the entitlements call's hardwareHash. */
    public static String sha256Upper(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString().toUpperCase();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
