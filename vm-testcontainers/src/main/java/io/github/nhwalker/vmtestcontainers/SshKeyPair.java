package io.github.nhwalker.vmtestcontainers;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.util.Base64;
import java.util.Objects;

/**
 * An SSH key pair generated for a single VM instance.
 *
 * <p>Keys are Ed25519, generated with the JDK (Java 15+), so no extra crypto provider is needed to
 * create them. The public half is rendered in OpenSSH {@code authorized_keys} format so cloud-init can
 * install it in the guest; the {@link KeyPair} itself is handed to the SSH client for authentication.
 */
public final class SshKeyPair {

    private static final String SSH_ED25519 = "ssh-ed25519";

    private final KeyPair keyPair;

    private SshKeyPair(KeyPair keyPair) {
        this.keyPair = Objects.requireNonNull(keyPair, "keyPair");
    }

    /** Generates a fresh Ed25519 key pair. */
    public static SshKeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            return new SshKeyPair(generator.generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK does not provide Ed25519 (requires Java 15+)", e);
        }
    }

    /** Wraps an existing Ed25519 key pair. */
    public static SshKeyPair of(KeyPair keyPair) {
        if (!(keyPair.getPublic() instanceof EdECPublicKey)) {
            throw new IllegalArgumentException("Only Ed25519 key pairs are supported, got "
                    + keyPair.getPublic().getAlgorithm());
        }
        return new SshKeyPair(keyPair);
    }

    public KeyPair keyPair() {
        return keyPair;
    }

    /**
     * The public key as a single OpenSSH {@code authorized_keys} line, e.g.
     * {@code ssh-ed25519 AAAAC3... vm-testcontainers}.
     */
    public String openSshPublicKey() {
        return openSshPublicKey("vm-testcontainers");
    }

    public String openSshPublicKey(String comment) {
        byte[] raw = rawPublicKey((EdECPublicKey) keyPair.getPublic());
        byte[] type = SSH_ED25519.getBytes(StandardCharsets.US_ASCII);
        byte[] blob = new byte[4 + type.length + 4 + raw.length];
        int i = 0;
        i = putUInt32(blob, i, type.length);
        System.arraycopy(type, 0, blob, i, type.length);
        i += type.length;
        i = putUInt32(blob, i, raw.length);
        System.arraycopy(raw, 0, blob, i, raw.length);
        String encoded = Base64.getEncoder().encodeToString(blob);
        return comment == null || comment.isBlank()
                ? SSH_ED25519 + " " + encoded
                : SSH_ED25519 + " " + encoded + " " + comment;
    }

    /**
     * RFC 8032 encoding of an Ed25519 public key: the 32-byte little-endian y coordinate with the most
     * significant bit of the last byte carrying the parity of x.
     */
    static byte[] rawPublicKey(EdECPublicKey key) {
        EdECPoint point = key.getPoint();
        byte[] yBigEndian = point.getY().toByteArray(); // may carry a leading 0x00 sign byte
        byte[] out = new byte[32];
        // Copy little-endian, dropping any leading sign byte beyond 32 bytes.
        int len = Math.min(yBigEndian.length, 32);
        for (int k = 0; k < len; k++) {
            out[k] = yBigEndian[yBigEndian.length - 1 - k];
        }
        if (point.isXOdd()) {
            out[31] |= (byte) 0x80;
        }
        return out;
    }

    /** Inverse of {@link #rawPublicKey} for tests and diagnostics. */
    static EdECPoint pointFromRaw(byte[] raw) {
        byte[] copy = raw.clone();
        boolean xOdd = (copy[31] & 0x80) != 0;
        copy[31] &= 0x7f;
        byte[] bigEndian = new byte[32];
        for (int k = 0; k < 32; k++) {
            bigEndian[k] = copy[31 - k];
        }
        return new EdECPoint(xOdd, new BigInteger(1, bigEndian));
    }

    private static int putUInt32(byte[] dst, int offset, int value) {
        dst[offset] = (byte) (value >>> 24);
        dst[offset + 1] = (byte) (value >>> 16);
        dst[offset + 2] = (byte) (value >>> 8);
        dst[offset + 3] = (byte) value;
        return offset + 4;
    }
}
