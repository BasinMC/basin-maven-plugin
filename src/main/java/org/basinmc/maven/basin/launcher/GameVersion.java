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
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

/**
 * Represents a single game version.
 *
 * @author <a href="mailto:johannesd@torchmind.com">Johannes Donath</a>
 */
@JsonIgnoreProperties({
    "assetIndex",
    "logging"
})
public final class GameVersion {

  private final String assets;
  private final Downloads downloads;
  private final String id;
  private final Set<Library> libraries;

  private final String mainClass;
  private final String minecraftArguments;
  private final int minimumLauncherVersion;

  private final Instant releaseTime;
  private final Instant time;
  private final VersionType type;

  @JsonCreator
  private GameVersion(
      @JsonProperty(value = "minimumLauncherVersion", required = true) int minimumLauncherVersion,
      @NonNull @JsonProperty(value = "id", required = true) String id,
      @NonNull @JsonProperty(value = "assets", required = true) String assets,
      @NonNull @JsonProperty(value = "downloads", required = true) Downloads downloads,
      @NonNull @JsonProperty(value = "libraries", required = true) Set<Library> libraries,
      @NonNull @JsonProperty(value = "mainClass", required = true) String mainClass,
      @NonNull @JsonProperty(value = "minecraftArguments", required = true) String minecraftArguments,
      @NonNull @JsonProperty(value = "releaseTime", required = true) Instant releaseTime,
      @NonNull @JsonProperty(value = "time", required = true) Instant time,
      @NonNull @JsonProperty(value = "type", required = true) String type) {
    this.minimumLauncherVersion = minimumLauncherVersion;

    this.id = id;
    this.assets = assets;
    this.downloads = downloads;
    this.libraries = libraries;

    this.mainClass = mainClass;
    this.minecraftArguments = minecraftArguments;
    this.releaseTime = releaseTime;
    this.time = time;
    this.type = VersionType.valueOf(type.toUpperCase());
  }

  public String getAssets() {
    return this.assets;
  }

  public Downloads getDownloads() {
    return this.downloads;
  }

  public String getId() {
    return this.id;
  }

  @NonNull
  public Set<Library> getLibraries() {
    return Collections.unmodifiableSet(this.libraries);
  }

  public String getMainClass() {
    return this.mainClass;
  }

  public String getMinecraftArguments() {
    return this.minecraftArguments;
  }

  public int getMinimumLauncherVersion() {
    return this.minimumLauncherVersion;
  }

  public Instant getReleaseTime() {
    return this.releaseTime;
  }

  public Instant getTime() {
    return this.time;
  }

  public VersionType getType() {
    return this.type;
  }

  /**
   * Represents a single downloadable file.
   */
  public static final class Download {

    private final String sha1;
    private final long size;
    private final URL url;

    @JsonCreator
    private Download(
        @NonNull @JsonProperty(value = "sha1", required = true) String sha1,
        @JsonProperty(value = "size", required = true) long size,
        @NonNull @JsonProperty(value = "url", required = true) URL url) {
      this.sha1 = sha1;
      this.size = size;
      this.url = url;
    }

    /**
     * Downloads the artifact from the server into the specified file.
     *
     * @param output an output file.
     * @throws IOException when retrieving the file fails.
     */
    public void fetch(@NonNull Path output) throws IOException {
      try (InputStream inputStream = this.url.openStream()) {
        try (ReadableByteChannel inputChannel = Channels.newChannel(inputStream)) {
          try (FileChannel outputChannel = FileChannel
              .open(output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                  StandardOpenOption.WRITE)) {
            outputChannel.transferFrom(inputChannel, 0, this.size);
          }
        }
      }
    }

    @NonNull
    public String getSha1() {
      return this.sha1;
    }

    public long getSize() {
      return this.size;
    }

    @NonNull
    public URL getUrl() {
      return this.url;
    }

    /**
     * Evaluates whether a file matches the expected checksum (e.g. verifies file integrity).
     *
     * @param input an input file.
     * @return true if valid, false otherwise.
     * @throws IOException when accessing the file fails or the checksum is malformed.
     */
    public boolean verify(@NonNull Path input) throws IOException {
      try {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");

        byte[] expected = Hex.decodeHex(this.sha1.toCharArray());
        byte[] actual = digest.digest(Files.readAllBytes(input));

        return Arrays.equals(expected, actual);
      } catch (DecoderException ex) {
        throw new IOException("Illegal file checksum \"" + this.sha1 + "\": " + ex.getMessage(),
            ex);
      } catch (NoSuchAlgorithmException ex) {
        throw new RuntimeException(
            "Illegal JVM implementation: SHA-1 message digest is unsupported", ex);
      }
    }
  }

  /**
   * Represents a set of available downloads (limited to client and server in our case).
   */
  public static final class Downloads {

    private final Download client;
    private final Download server;

    @JsonCreator
    private Downloads(
        @NonNull @JsonProperty(value = "client", required = true) Download client,
        @NonNull @JsonProperty(value = "server", required = true) Download server) {
      this.client = client;
      this.server = server;
    }

    @NonNull
    public Download getClient() {
      return this.client;
    }

    @NonNull
    public Download getServer() {
      return this.server;
    }
  }

  /**
   * (Partially) Represents a library on which this game version relies (including client
   * libraries).
   */
  @JsonIgnoreProperties({
      "extract",
      "rules",
      "natives"
  })
  public static final class Library {

    private final String name;
    private final LibraryDownloads downloads;

    @JsonCreator
    private Library(
        @NonNull @JsonProperty(value = "name", required = true) String name,
        @NonNull @JsonProperty(value = "downloads", required = true) LibraryDownloads downloads) {
      this.name = name;
      this.downloads = downloads;
    }

    @NonNull
    public String getName() {
      return this.name;
    }

    @NonNull
    public LibraryDownloads getDownloads() {
      return this.downloads;
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
      Library library = (Library) o;
      return Objects.equals(this.name, library.name) &&
          Objects.equals(this.downloads, library.downloads);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
      return Objects.hash(this.name, this.downloads);
    }
  }

  public static final class LibraryDownloads {

    private final Map<String, Artifact> classifiers;
    private final Artifact artifact;

    @JsonCreator
    private LibraryDownloads(
        @Nullable @JsonProperty("classifiers") Map<String, Artifact> classifiers,
        @Nullable @JsonProperty("artifact") Artifact artifact) {
      this.classifiers = classifiers != null ? classifiers : Collections.emptyMap();
      this.artifact = artifact;
    }

    @NonNull
    public Optional<Artifact> getClassifier(@NonNull String classifier) {
      return Optional.ofNullable(this.classifiers.get(classifier));
    }

    @NonNull
    public Map<String, Artifact> getClassifiers() {
      return Collections.unmodifiableMap(this.classifiers);
    }

    @NonNull
    public Optional<Artifact> getArtifact() {
      return Optional.ofNullable(this.artifact);
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
      LibraryDownloads that = (LibraryDownloads) o;
      return Objects.equals(this.classifiers, that.classifiers) &&
          Objects.equals(this.artifact, that.artifact);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
      return Objects.hash(this.classifiers, this.artifact);
    }
  }

  /**
   * Represents a single downloadable library artifact.
   */
  public static final class Artifact {

    private final long size;
    private final String sha1;
    private final String path;
    private final URL url;

    @JsonCreator
    private Artifact(
        @JsonProperty(value = "size", required = true) long size,
        @NonNull @JsonProperty(value = "sha1", required = true) String sha1,
        @NonNull @JsonProperty(value = "path", required = true) String path,
        @NonNull @JsonProperty(value = "url", required = true) URL url) {
      this.size = size;
      this.sha1 = sha1;
      this.path = path;
      this.url = url;
    }

    public long getSize() {
      return this.size;
    }

    @NonNull
    public String getSha1() {
      return this.sha1;
    }

    @NonNull
    public String getPath() {
      return this.path;
    }

    @NonNull
    public URL getUrl() {
      return this.url;
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
      Artifact artifact = (Artifact) o;
      return this.size == artifact.size &&
          Objects.equals(this.sha1, artifact.sha1) &&
          Objects.equals(this.path, artifact.path) &&
          Objects.equals(this.url, artifact.url);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
      return Objects.hash(this.size, this.sha1, this.path, this.url);
    }
  }
}
