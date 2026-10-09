package com.contextlayer.backend.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// A hash function converts input data into a fixed-length fingerprint.

// For SHA-256, the output contains 256 bits, conventionally represented by 64 hexadecimal characters.

// For example, imagine two versions of a source file:

// Even though the files are almost identical, their SHA-256 hashes will ordinarily be completely different.

// The hash allows ContextLayer to compare content without storing or comparing a second full copy just for change detection.
public final class HashUtils {

    private HashUtils() {
    }

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on the JVM", e);
        }
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }
}