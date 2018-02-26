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

import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.FormatterException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import org.basinmc.blackwater.task.Task;
import org.basinmc.blackwater.task.error.TaskExecutionException;
import org.basinmc.plunger.Plunger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transforms and extracts the source code.
 *
 * @author <a href="mailto:johannesd@torchmind.com">Johannes Donath</a>
 */
public class TransformSourceTask implements Task {

  private static final Logger logger = LoggerFactory.getLogger(TransformSourceTask.class);
  private final Path accessTransformerMap;
  private final Charset charset;

  public TransformSourceTask(@NonNull Charset charset, @Nullable File accessTransformerMap) {
    this.charset = charset;
    this.accessTransformerMap = accessTransformerMap != null ? accessTransformerMap.toPath() : null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void execute(@NonNull Context context) throws TaskExecutionException {
    Path input = context.getRequiredInputPath();
    Path output = context.getRequiredOutputPath();

    if (Files.exists(output)) {
      try {
        Iterator<Path> it = Files.walk(output)
            .sorted((p1, p2) -> p2.getNameCount() - p1.getNameCount())
            .iterator();

        while (it.hasNext()) {
          Files.deleteIfExists(it.next());
        }
      } catch (IOException ex) {
        throw new TaskExecutionException("Failed to prepare output directory: " + ex.getMessage(),
            ex);
      }
    }

    Formatter formatter = new Formatter();

    try (FileSystem inputFS = Plunger.openZipArchive(input)) {
      Plunger plunger = Plunger.sourceBuilder()
          .withCharset(this.charset)
          .withFormatter((source) -> {
            try {
              return formatter.formatSource(source);
            } catch (FormatterException ex) {
              logger.error("Failed to format file: " + ex.getMessage(), ex);
              return source;
            }
          })
          // TODO: Access transformations
          .withParallelism()
          .build(
              inputFS.getRootDirectories().iterator().next(),
              output
          );

      try {
        plunger.apply();
      } catch (IOException ex) {
        throw new TaskExecutionException(
            "Failed to apply source transformations: " + ex.getMessage(),
            ex);
      }
    } catch (IOException | URISyntaxException ex) {
      throw new TaskExecutionException("Failed to access input artifact: " + ex.getMessage(), ex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @NonNull
  @Override
  public String getName() {
    return "transform-source";
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
}
