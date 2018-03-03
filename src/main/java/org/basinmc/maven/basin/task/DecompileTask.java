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
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;
import org.basinmc.blackwater.artifact.Artifact;
import org.basinmc.blackwater.artifact.ArtifactReference;
import org.basinmc.blackwater.task.Task;
import org.basinmc.blackwater.task.error.TaskExecutionException;
import org.basinmc.plunger.Plunger;
import org.basinmc.plunger.bytecode.BytecodePlunger;
import org.basinmc.plunger.mapping.mcp.AccessLevelCorrectionBytecodeTransformer;
import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler;
import org.jetbrains.java.decompiler.main.extern.IBytecodeProvider;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decompiles an archive using Fernflower (and the Basin standard set of decompiler flags).
 *
 * @author <a href="mailto:johannesd@torchmind.com">Johannes Donath</a>
 */
public class DecompileTask implements Task {

  private static final Logger logger = LoggerFactory.getLogger(DecompileTask.class);

  private final Charset charset;
  private final Set<ArtifactReference> dependencies;

  public DecompileTask(@NonNull Charset charset, @NonNull Set<ArtifactReference> dependencies) {
    this.charset = charset;
    this.dependencies = dependencies;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void execute(@NonNull Context context) throws TaskExecutionException {
    Path input = context.getRequiredInputPath();
    Path output = context.getRequiredOutputPath();

    Map<String, Object> options = new HashMap<>();
    options.put(IFernflowerPreferences.DECOMPILE_INNER, "1");
    options.put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1");
    options.put(IFernflowerPreferences.ASCII_STRING_CHARACTERS, "1");
    options.put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1");
    options.put(IFernflowerPreferences.REMOVE_SYNTHETIC, "1");
    options.put(IFernflowerPreferences.REMOVE_BRIDGE, "1");
    options.put(IFernflowerPreferences.LITERALS_AS_IS, "0");
    options.put(IFernflowerPreferences.UNIT_TEST_MODE, "0");
    options.put(IFernflowerPreferences.MAX_PROCESSING_METHOD, "0");

    try (FileSystem outputFS = Plunger.createZipArchive(output)) {
      try (FileSystem inputFS = Plunger.openZipArchive(input)) {
        Path intermediateFile = context.allocateTemporaryDirectory().resolve("intermediate.jar");

        try (FileSystem intermediateFS = Plunger.createZipArchive(intermediateFile)) {
          Plunger plunger = BytecodePlunger.builder()
              .withParallelism()
              .withTransformer(new AccessLevelCorrectionBytecodeTransformer())
              .build(
                  inputFS.getRootDirectories().iterator().next(),
                  intermediateFS.getRootDirectories().iterator().next()
              );
          plunger.apply();
        }

        try (FileSystem intermediateFS = Plunger.openZipArchive(intermediateFile)) {
          BaseDecompiler decompiler = new BaseDecompiler(
              new NioBytecodeProvider(inputFS),
              new NioResultSaver(intermediateFS, outputFS),
              options,
              new Slf4jLogger()
          );

          decompiler.addSpace(intermediateFile.toFile(), true);

          for (ArtifactReference reference : this.dependencies) {
            Artifact artifact = context.getRequiredArtifactManager()
                .getArtifact(reference)
                .orElseThrow(
                    () -> new TaskExecutionException("Failed to resolve artifact " + reference));

            decompiler.addSpace(artifact.getPath().toFile(), false);
          }

          decompiler.decompileContext();
        }
      }
    } catch (IOException ex) {
      throw new TaskExecutionException("Failed to create source archive: " + ex.getMessage(), ex);
    } catch (URISyntaxException ex) {
      throw new TaskExecutionException("Illegal output path: " + ex.getMessage(), ex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @NonNull
  @Override
  public String getName() {
    return "decompile";
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean requiresInputParameter() {
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean requiresOutputParameter() {
    return true;
  }

  /**
   * Retrieves bytecode from within an archive using NIO.
   */
  private static final class NioBytecodeProvider implements IBytecodeProvider {

    private final FileSystem fs;

    private NioBytecodeProvider(@NonNull FileSystem fs) {
      this.fs = fs;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] getBytecode(@NonNull String externalPath, @NonNull String internalPath)
        throws IOException {
      return Files.readAllBytes(this.fs.getPath(internalPath));
    }
  }

  /**
   * Redirects all Fernflower outputs to the build tool log.
   */
  private static class Slf4jLogger extends IFernflowerLogger {

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeMessage(@NonNull String message, @NonNull Severity severity) {
      this.writeMessage(message, severity, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeMessage(@NonNull String message, @NonNull Severity severity,
        @Nullable Throwable throwable) {
      switch (severity) {
        case TRACE:
          logger.trace("  " + message, throwable);
          break;
        case INFO:
          logger.info("  " + message, throwable);
          break;
        case WARN:
          logger.warn("  " + message, throwable);
          break;
        case ERROR:
          logger.error("  " + message, throwable);
          break;
      }
    }
  }

  /**
   * Stores decompiled class and resources in an archive using NIO.
   */
  private final class NioResultSaver implements IResultSaver {

    private final FileSystem inputFS;
    private final FileSystem outputFS;

    private NioResultSaver(@NonNull FileSystem inputFS, @NonNull FileSystem outputFS) {
      this.inputFS = inputFS;
      this.outputFS = outputFS;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void closeArchive(@NonNull String path, @NonNull String archiveName) {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void copyEntry(@NonNull String source, @NonNull String path, @NonNull String archiveName,
        @NonNull String entryName) {
      this.copyFile(source, path, entryName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void copyFile(@NonNull String source, @NonNull String path, @NonNull String entryName) {
      try {
        Path sourcePath = this.inputFS.getPath(entryName);
        Path targetPath = this.outputFS.getPath(entryName);

        if (Files.isDirectory(sourcePath)) {
          return;
        }

        if (targetPath.getParent() != null) {
          Files.createDirectories(targetPath.getParent());
        }

        Files.copy(sourcePath, targetPath);
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to copy entry: " + ex.getMessage(), ex);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createArchive(@NonNull String path, @NonNull String archiveName,
        @Nullable Manifest manifest) {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void saveClassEntry(@NonNull String path, @NonNull String archiveName,
        @NonNull String qualifiedName, @NonNull String entryName, @NonNull String content) {
      this.saveClassFile(path, qualifiedName, entryName, content, new int[0]);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void saveClassFile(@NonNull String path, @NonNull String qualifiedName,
        @NonNull String entryName, @Nullable String content, @NonNull int[] mapping) {
      try {
        Path outputPath = this.outputFS.getPath(entryName);

        if (content == null) {
          logger.error("Cannot save entry \"" + entryName + "\" with empty content");
          return;
        }

        Files.createDirectories(outputPath.getParent());
        Files.write(outputPath, content.getBytes(DecompileTask.this.charset));
      } catch (IOException ex) {
        throw new IllegalStateException(
            "Failed to write class at path \"" + path + "\": " + ex.getMessage(), ex);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void saveDirEntry(@NonNull String path, @NonNull String archiveName,
        @NonNull String entryName) {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void saveFolder(@NonNull String path) {
      try {
        Files.createDirectories(this.outputFS.getPath(path));
      } catch (IOException ex) {
        throw new IllegalStateException(
            "Failed to write directory at path \"" + path + "\": " + ex.getMessage(), ex);
      }
    }
  }
}
