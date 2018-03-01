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
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import org.basinmc.blackwater.task.Task;
import org.basinmc.blackwater.task.error.TaskExecutionException;
import org.basinmc.maven.basin.transformer.KeywordRemovalTransformer;
import org.basinmc.plunger.Plunger;
import org.basinmc.plunger.bytecode.BytecodePlunger;
import org.basinmc.plunger.bytecode.transformer.NameMappingBytecodeTransformer;
import org.basinmc.plunger.mapping.DelegatingNameMapping;
import org.basinmc.plunger.mapping.FieldNameMapping;
import org.basinmc.plunger.mapping.MethodNameMapping;
import org.basinmc.plunger.mapping.NameMapping;
import org.basinmc.plunger.mapping.csv.CSVFieldNameMappingParser;
import org.basinmc.plunger.mapping.csv.CSVMethodNameMappingParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the supplied archive of MCP mappings to a previously SRG mapped archive.
 *
 * @author <a href="mailto:johannesd@torchmind.com">Johannes Donath</a>
 */
public class ApplyMcpMappingsTask implements Task {

  private static final Logger logger = LoggerFactory.getLogger(ApplyMcpMappingsTask.class);

  /**
   * {@inheritDoc}
   */
  @Override
  public void execute(@NonNull Context context) throws TaskExecutionException {
    Path input = context.getRequiredInputPath();
    Path output = context.getRequiredOutputPath();
    Path srg = context.getRequiredParameterPath("mcp");

    logger.info("Loading MCP mappings ...");

    FieldNameMapping fieldMapping;
    MethodNameMapping methodNameMapping;

    try (FileSystem fs = FileSystems.newFileSystem(srg, this.getClass().getClassLoader())) {
      fieldMapping = CSVFieldNameMappingParser.builder()
          .build("searge", "name")
          .parse(fs.getPath("fields.csv"));
      methodNameMapping = CSVMethodNameMappingParser.builder()
          .build("searge", "name")
          .parse(fs.getPath("methods.csv"));
    } catch (IOException ex) {
      throw new TaskExecutionException("Failed to parse SRG mappings: " + ex.getMessage());
    }
    logger.info("  Success");

    NameMapping mapping = DelegatingNameMapping.builder()
        .withFieldNameMapping(fieldMapping)
        .withMethodNameMapping(methodNameMapping)
        .build();

    logger.info("Applying mappings (this may take a long time) ...");
    try (FileSystem inputFS = Plunger.openZipArchive(input);
        FileSystem outputFS = Plunger.createZipArchive(output)) {
      Plunger plunger = BytecodePlunger.builder()
          .withTransformer(new NameMappingBytecodeTransformer(mapping))
          .withTransformer(new KeywordRemovalTransformer())
          .withParallelism()
          .build(
              inputFS.getRootDirectories().iterator().next(),
              outputFS.getRootDirectories().iterator().next()
          );
      plunger.apply();
    } catch (IOException | URISyntaxException ex) {
      throw new TaskExecutionException("Cannot access input or output artifact: " + ex.getMessage(),
          ex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @NonNull
  @Override
  public Set<String> getAvailableParameterNames() {
    return Collections.singleton("mcp");
  }

  /**
   * {@inheritDoc}
   */
  @NonNull
  @Override
  public String getName() {
    return "apply-mcp";
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
