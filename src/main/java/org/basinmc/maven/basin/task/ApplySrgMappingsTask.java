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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.basinmc.blackwater.task.Task;
import org.basinmc.blackwater.task.error.TaskExecutionException;
import org.basinmc.plunger.Plunger;
import org.basinmc.plunger.bytecode.DebugAttributeBytecodeTransformer;
import org.basinmc.plunger.bytecode.NameMappingBytecodeTransformer;
import org.basinmc.plunger.common.mapping.NameMapping;
import org.basinmc.plunger.common.mapping.parser.SRGNameMappingParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes all debug parameters (e.g. troll level obfuscation) and maps the entire archive using the
 * supplied archive of SRG mappings.
 *
 * @author <a href="mailto:johannesd@torchmind.com">Johannes Donath</a>
 */
public class ApplySrgMappingsTask implements Task {

  private static final Logger logger = LoggerFactory.getLogger(ApplySrgMappingsTask.class);

  /**
   * {@inheritDoc}
   */
  @Override
  public void execute(@NonNull Context context) throws TaskExecutionException {
    Path input = context.getRequiredInputPath();
    Path output = context.getRequiredOutputPath();
    Path srg = context.getRequiredParameterPath("srg");

    logger.info("Loading SRG mappings ...");
    NameMapping mapping;
    try (FileSystem fs = FileSystems.newFileSystem(srg, this.getClass().getClassLoader())) {
      mapping = new SRGNameMappingParser().parse(fs.getPath("joined.srg"));
    } catch (IOException ex) {
      throw new TaskExecutionException("Failed to parse SRG mappings: " + ex.getMessage());
    }
    logger.info("  Success");

    logger.info("Applying mappings (this may take a long time) ...");
    try (FileSystem inputFS = Plunger.openZipArchive(input);
        FileSystem outputFS = Plunger.createZipArchive(output)) {
      Collection<Path> excludedElements = new HashSet<>(Arrays.asList(
          inputFS.getPath("com/"),
          inputFS.getPath("io/"),
          inputFS.getPath("it/"),
          inputFS.getPath("javax/"),
          inputFS.getPath("org/")
      ));

      Collection<Path> includedResources = new HashSet<>(Arrays.asList(
          inputFS.getPath("assets/"),
          inputFS.getPath("log4j2.xml"),
          inputFS.getPath("yggdrasil_session_pubkey.der")
      ));

      Plunger plunger = Plunger.bytecodeBuilder()
          .withParallelism()
          .withClassInclusionVoter((p) -> excludedElements.stream().noneMatch(p::startsWith))
          .withResourceVoter(
              (p) -> includedResources.stream().anyMatch((r) -> p.equals(r) || p.startsWith(r)))
          .withTransformer(new NameMappingBytecodeTransformer(mapping))
          .withTransformer(new DebugAttributeBytecodeTransformer(true, true, false, true))
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
    return Collections.singleton("srg");
  }

  /**
   * {@inheritDoc}
   */
  @NonNull
  @Override
  public String getName() {
    return "apply-srg";
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
