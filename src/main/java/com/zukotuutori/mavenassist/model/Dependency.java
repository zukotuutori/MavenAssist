package com.zukotuutori.mavenassist.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A Maven dependency (groupId / artifactId / version) with an optional display name.
 * The version may be empty, meaning "unknown / resolve the newest one when used".
 */
public final class Dependency {

    private final String groupId;
    private final String artifactId;
    private final String version;
    private final String name;

    public Dependency(@NotNull String groupId, @NotNull String artifactId, @Nullable String version) {
        this(groupId, artifactId, version, null);
    }

    public Dependency(@NotNull String groupId, @NotNull String artifactId,
                      @Nullable String version, @Nullable String name) {
        this.groupId = groupId.trim();
        this.artifactId = artifactId.trim();
        this.version = version == null ? "" : version.trim();
        this.name = name == null ? "" : name.trim();
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

    /** groupId:artifactId — identifies a dependency regardless of version. */
    public @NotNull String getKey() {
        return groupId + ":" + artifactId;
    }

    public @NotNull String toGav() {
        return groupId + ":" + artifactId + ":" + version;
    }

    public @NotNull Dependency withVersion(@Nullable String newVersion) {
        return new Dependency(groupId, artifactId, newVersion, name);
    }

    /** Parses "groupId:artifactId" or "groupId:artifactId:version". */
    public static @Nullable Dependency fromGav(@NotNull String gav) {
        String[] parts = gav.trim().split(":");
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        return new Dependency(parts[0], parts[1], parts.length > 2 ? parts[2] : "");
    }

    public @NotNull String getDisplayName() {
        String coordinates = version.isEmpty() ? getKey() : getKey() + "  (" + version + ")";
        return name.isEmpty() ? coordinates : name + "  —  " + coordinates;
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Dependency that)) return false;
        return groupId.equals(that.groupId) && artifactId.equals(that.artifactId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId);
    }
}
