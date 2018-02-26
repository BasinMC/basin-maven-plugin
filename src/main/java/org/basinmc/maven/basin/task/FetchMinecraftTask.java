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
package org.basinmc.maven.basin.task;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import org.basinmc.blackwater.task.Task;
import org.basinmc.blackwater.task.error.TaskExecutionException;
import org.basinmc.maven.basin.launcher.GameVersion;
import org.basinmc.maven.basin.launcher.LauncherVersionManifest;
import org.basinmc.maven.basin.launcher.VersionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retrieves a specific version of the Minecraft server from the official servers.
 *
 * @author <a href="mailto:johannesd@torchmind.com">Johannes Donath</a>
 */
public class FetchMinecraftTask implements Task {

  /**
   * Defines the location of the launcher version manifest which indicates where to retrieve the
   * server or client from.
   */
  private static final String LAUNCHER_MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
  private static final Logger logger = LoggerFactory.getLogger(FetchMinecraftTask.class);
  private final String versionId;

  public FetchMinecraftTask(@NonNull String versionId) {
    this.versionId = versionId;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void execute(@NonNull Context context) throws TaskExecutionException {
    Path out = context.getRequiredOutputPath();

    try {
      LauncherVersionManifest manifest = LauncherVersionManifest.get();

      logger.info("Latest release version: {}", manifest.getLatest().getRelease());
      logger.info("Latest snapshot version: {}", manifest.getLatest().getRelease());

      GameVersion version = manifest.getVersion(this.versionId)
          .orElseThrow(
              () -> new TaskExecutionException("Illegal version identification: " + this.versionId))
          .get();

      logger.info("Selected version: {}", version.getId());
      logger.info("Released date: {}",
          DateTimeFormatter.ISO_INSTANT.format(version.getReleaseTime()));
      logger.info("Modification date: {}", DateTimeFormatter.ISO_INSTANT.format(version.getTime()));
      logger.info("Type: {}", version.getType());

      if (version.getType() == VersionType.SNAPSHOT) {
        logger.warn("Compiling against a snapshot release - Proceed with caution");
      }

      logger.info("Downloading artifact (this may take a while) ...");
      version.getDownloads().getServer().fetch(out);
      logger.info("  Success");

      logger.info("Validating file integrity ...");
      if (!version.getDownloads().getServer().verify(out)) {
        throw new TaskExecutionException("Artifact download failed - Integrity check has failed");
      }
      logger.info("  Valid");
    } catch (IOException ex) {
      throw new TaskExecutionException("Failed to retrieve launcher manifest: " + ex.getMessage(),
          ex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @NonNull
  @Override
  public String getName() {
    return "fetch-minecraft";
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean requiresOutputParameter() {
    return true;
  }
}
