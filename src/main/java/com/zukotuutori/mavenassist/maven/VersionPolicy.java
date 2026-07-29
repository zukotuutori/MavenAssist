package com.zukotuutori.mavenassist.maven;

import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative rules for unattended dependency updates. */
public final class VersionPolicy {

    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern PRE_RELEASE = Pattern.compile(
            "(?i)(?:^|[._+\\-]|(?<=\\d))"
                    + "(alpha|beta|rc|cr|milestone|preview|pre|ea|eap|snapshot|dev|nightly|canary|"
                    + "experimental|incubating|draft|pr|m)"
                    + "(?:\\d|[._+\\-]|$)");

    private VersionPolicy() {
    }

    /** Maven Central's latest timestamp may be an alpha/beta/RC; automatic actions skip those. */
    public static boolean isStable(@NotNull String version) {
        String trimmed = version.trim();
        return !trimmed.isEmpty() && !PRE_RELEASE.matcher(trimmed).find();
    }

    /**
     * True only when the candidate is a stable version that is numerically newer.
     * Unknown/non-numeric schemes are deliberately not changed unattended.
     */
    public static boolean isUpgrade(@NotNull String current, @NotNull String candidate) {
        if (current.isBlank() || candidate.equals(current) || !isStable(candidate)) {
            return false;
        }
        List<BigInteger> from = numericParts(current);
        List<BigInteger> to = numericParts(candidate);
        if (from.isEmpty() || to.isEmpty()) {
            return false;
        }
        int count = Math.max(from.size(), to.size());
        for (int i = 0; i < count; i++) {
            BigInteger left = i < from.size() ? from.get(i) : BigInteger.ZERO;
            BigInteger right = i < to.size() ? to.get(i) : BigInteger.ZERO;
            int comparison = right.compareTo(left);
            if (comparison != 0) {
                return comparison > 0;
            }
        }
        // 1.0.0-rc1 -> 1.0.0 is an upgrade even though its numeric parts are equal.
        return !isStable(current) && isStable(candidate);
    }

    /** Uses a Central result only when it is a stable value at least as new as the fallback. */
    public static @NotNull String newestOrFallback(@NotNull String fallback, String candidate) {
        if (candidate == null || !isStable(candidate)) {
            return fallback;
        }
        if (fallback.isBlank() || candidate.equals(fallback) || isUpgrade(fallback, candidate)) {
            return candidate;
        }
        return fallback;
    }

    private static @NotNull List<BigInteger> numericParts(@NotNull String version) {
        List<BigInteger> result = new ArrayList<>();
        String normalized = version.toLowerCase(Locale.ROOT);
        Matcher preRelease = PRE_RELEASE.matcher(normalized);
        if (preRelease.find()) {
            normalized = normalized.substring(0, preRelease.start());
        }
        Matcher matcher = NUMBER.matcher(normalized);
        while (matcher.find()) {
            result.add(new BigInteger(matcher.group()));
        }
        return result;
    }
}
