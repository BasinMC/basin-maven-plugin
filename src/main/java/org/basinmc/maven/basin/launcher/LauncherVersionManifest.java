/*
 * Copyright 2018 Johannes Donath <johannesd@torchmind.com>
 * and other copyright owners as documented in the project's IP log.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.basinmc.maven.basin.launcher;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * @author <a href="mailto:johannesd@torchmind.com">Johannes Donath</a>
 */
public final class LauncherVersionManifest {

  /**
   * Defines the URL at which the manifest is available.
   */
  public static final String MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest.json";

  private final Latest latest;
  private final Set<Version> versions;

  @JsonCreator
  private LauncherVersionManifest(
      @NonNull @JsonProperty(value = "latest", required = true) Latest latest,
      @NonNull @JsonProperty(value = "versions", required = true) Set<Version> versions) {
    this.latest = latest;
    this.versions = versions;
  }

  /**
   * Retrieves the current launcher version manifest.
   *
   * @return a manifest representation.
   * @throws IOException when loading the manifest fails.
   */
  @NonNull
  public static LauncherVersionManifest get() throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    mapper.findAndRegisterModules();

    try (InputStream inputStream = new URL(MANIFEST_URL).openStream()) {
      return mapper.readValue(inputStream, LauncherVersionManifest.class);
    }
  }

  @NonNull
  public Latest getLatest() {
    return this.latest;
  }

  @NonNull
  public Optional<Version> getVersion(@NonNull String id) {
    return this.versions.stream()
        .filter((v) -> id.equalsIgnoreCase(v.id))
        .findAny();
  }

  @NonNull
  public Set<Version> getVersions() {
    return Collections.unmodifiableSet(this.versions);
  }

  /**
   * Provides information on the latest available game versions.
   */
  public static final class Latest {

    private final String release;
    private final String snapshot;

    @JsonCreator
    private Latest(
        @NonNull @JsonProperty(value = "snapshot", required = true) String snapshot,
        @NonNull @JsonProperty(value = "release", required = true) String release) {
      this.snapshot = snapshot;
      this.release = release;
    }

    /**
     * Retrieves the identification of the latest known release version.
     *
     * @return a version identifier.
     */
    @NonNull
    public String getRelease() {
      return this.release;
    }

    /**
     * Retrieves the identification of the latest known snapshot version.
     *
     * @return a version identifier.
     */
    @NonNull
    public String getSnapshot() {
      return this.snapshot;
    }
  }

  /**
   * Provides information on a released version.
   */
  public static final class Version {

    private final String id;
    private final Instant releaseTime;
    private final Instant time;
    private final VersionType type;
    private final URL url;

    private Version(
        @NonNull @JsonProperty(value = "id", required = true) String id,
        @NonNull @JsonProperty(value = "type", required = true) String type,
        @NonNull @JsonProperty(value = "time", required = true) Instant time,
        @NonNull @JsonProperty(value = "releaseTime", required = true) Instant releaseTime,
        @NonNull @JsonProperty(value = "url", required = true) URL url) {
      this.id = id;
      this.type = VersionType.valueOf(type.toUpperCase());
      this.time = time;
      this.releaseTime = releaseTime;
      this.url = url;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || this.getClass() != o.getClass()) {
        return false;
      }
      Version version = (Version) o;
      return Objects.equals(this.id, version.id);
    }

    /**
     * Retrieves the game version manifest.
     *
     * @return a game version manifest.
     * @throws IOException when downloading the manifest fails.
     */
    @NonNull
    public GameVersion get() throws IOException {
      ObjectMapper mapper = new ObjectMapper();
      mapper.findAndRegisterModules();

      try (InputStream inputStream = this.url.openStream()) {
        return mapper.readValue(inputStream, GameVersion.class);
      }
    }

    @NonNull
    public String getId() {
      return this.id;
    }

    @NonNull
    public Instant getReleaseTime() {
      return this.releaseTime;
    }

    @NonNull
    public Instant getTime() {
      return this.time;
    }

    @NonNull
    public VersionType getType() {
      return this.type;
    }

    @NonNull
    public URL getUrl() {
      return this.url;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
      return Objects.hash(this.id);
    }
  }
}
