package com.micklab.mcp.security;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;

public final class RuntimeSecurityPolicy {
    public static final int MAX_HTTP_BODY_BYTES = 256 * 1024;
    private static final int MIN_TIMEOUT_MS = 250;
    private static final int MAX_TIMEOUT_MS = 15_000;

    private RuntimeSecurityPolicy() {
    }

    public static int sanitizeTimeoutMs(int requestedTimeoutMs) {
        if (requestedTimeoutMs <= 0) {
            return 3_000;
        }
        if (requestedTimeoutMs < MIN_TIMEOUT_MS) {
            return MIN_TIMEOUT_MS;
        }
        return Math.min(requestedTimeoutMs, MAX_TIMEOUT_MS);
    }

    public static String validateExternalHttpUrl(String rawUrl)
            throws URISyntaxException, IOException {
        URI uri = new URI(rawUrl.trim()).normalize();
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("URL must include an http or https scheme.");
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Only http and https URLs are allowed.");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("URL host is required.");
        }
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IllegalArgumentException(
                        "Local, link-local, and private network addresses are blocked by default."
                );
            }
        }
        return uri.toString();
    }

    public static void requireLoopbackHost(String host) {
        if (!"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host)) {
            throw new IllegalArgumentException("Embedded MCP transport must stay bound to loopback.");
        }
    }

    public static File requireWithinRoot(File root, File candidate) throws IOException {
        String canonicalRoot = root.getCanonicalPath();
        String canonicalCandidate = candidate.getCanonicalPath();
        if (!canonicalCandidate.equals(canonicalRoot)
                && !canonicalCandidate.startsWith(canonicalRoot + File.separator)) {
            throw new IllegalArgumentException("Path escapes bundled runtime root: " + candidate);
        }
        return candidate;
    }

    public static int requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }

    public static void requireExactLength(byte[] payload, int expectedLength) {
        if (payload.length != expectedLength) {
            throw new IllegalArgumentException(
                    "Expected " + expectedLength + " bytes but received " + payload.length + "."
            );
        }
    }
}
