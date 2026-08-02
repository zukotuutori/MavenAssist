package com.zukotuutori.mavenassist.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A Maven dependency with an optional display name.
 * The version may be empty, meaning "unknown / resolve the newest one when used".
 */
public final class Dependency {

    private final String groupId;
    private final String artifactId;
    private final String version;
    private final String name;
    private final String type;
    private final String classifier;

    public Dependency(@NotNull String groupId, @NotNull String artifactId, @Nullable String version) {
        this(groupId, artifactId, version, null);
    }

    public Dependency(@NotNull String groupId, @NotNull String artifactId,
                      @Nullable String version, @Nullable String name) {
        this(groupId, artifactId, version, name, null, null);
    }

    public Dependency(@NotNull String groupId, @NotNull String artifactId,
                      @Nullable String version, @Nullable String name,
                      @Nullable String type, @Nullable String classifier) {
        this.groupId = groupId.trim();
        this.artifactId = artifactId.trim();
        this.version = version == null ? "" : version.trim();
        this.name = name == null ? "" : name.trim();
        this.type = type == null ? "" : type.trim();
        this.classifier = classifier == null ? "" : classifier.trim();
    }

    public @NotNull String getGroupId() {
        return groupId;
    }

    public @NotNull String getArtifactId() {
        return artifactId;
    }

    /** May be empty. */
    public @NotNull String getVersion() {
        return version;
    }

    /** Optional display name, may be empty. */
    public @NotNull String getName() {
        return name;
    }

    /** May be empty, which is Maven's default {@code jar} type. */
    public @NotNull String getType() {
        return type;
    }

    /** May be empty. */
    public @NotNull String getClassifier() {
        return classifier;
    }

    /**
     * Stable dependency identity. Maven distinguishes dependencies by group id, artifact id,
     * effective type and classifier; omitting the default jar/empty pair keeps old stored keys
     * compatible.
     */
    public @NotNull String getKey() {
        if ((type.isEmpty() || "jar".equals(type)) && classifier.isEmpty()) {
            return groupId + ":" + artifactId;
        }
        return groupId + ":" + artifactId + ":" + effectiveType() + ":" + classifier;
    }

    public @NotNull String toGav() {
        return groupId + ":" + artifactId + ":" + version;
    }

    /**
     * Maven-style coordinate used by templates. The short form stays compatible with
     * existing templates; type and classifier are included only when they carry identity.
     */
    public @NotNull String toTemplateCoordinate() {
        if ((type.isEmpty() || "jar".equals(type)) && classifier.isEmpty()) {
            return toGav();
        }
        return groupId + ":" + artifactId + ":" + effectiveType() + ":" + classifier + ":" + version;
    }

    public @NotNull Dependency withVersion(@Nullable String newVersion) {
        return new Dependency(groupId, artifactId, newVersion, name, type, classifier);
    }

    /**
     * Parses the old template forms {@code groupId:artifactId[:version]} and Maven's
     * extended {@code groupId:artifactId:type[:classifier]:version} coordinates.
     */
    public static @Nullable Dependency fromGav(@NotNull String gav) {
        String[] parts = gav.trim().split(":", -1);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        return switch (parts.length) {
            case 2 -> new Dependency(parts[0], parts[1], "");
            case 3 -> new Dependency(parts[0], parts[1], parts[2]);
            case 4 -> new Dependency(parts[0], parts[1], parts[3], null, parts[2], null);
            case 5 -> new Dependency(parts[0], parts[1], parts[4], null, parts[2], parts[3]);
            default -> null;
        };
    }

    public @NotNull String getDisplayName() {
        String coordinates = version.isEmpty() ? getKey() : getKey() + "  (" + version + ")";
        return name.isEmpty() ? coordinates : name + " - " + coordinates;
    }

    private @NotNull String effectiveType() {
        return type.isEmpty() ? "jar" : type;
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Dependency that)) return false;
        return groupId.equals(that.groupId)
                && artifactId.equals(that.artifactId)
                && effectiveType().equals(that.effectiveType())
                && classifier.equals(that.classifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId, effectiveType(), classifier);
    }
}
