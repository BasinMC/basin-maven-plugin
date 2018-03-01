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
import org.basinmc.plunger.bytecode.BytecodePlunger;
import org.basinmc.plunger.bytecode.transformer.DebugAttributeBytecodeTransformer;
import org.basinmc.plunger.bytecode.transformer.NameMappingBytecodeTransformer;
import org.basinmc.plunger.mapping.DelegatingNameMapping;
import org.basinmc.plunger.mapping.NameMapping;
import org.basinmc.plunger.mapping.ParameterNameMapping;
import org.basinmc.plunger.mapping.mcp.InnerClassConstructorBytecodeTransformer;
import org.basinmc.plunger.mapping.mcp.InnerClassMapping;
import org.basinmc.plunger.mapping.mcp.InnerClassMappingBytecodeTransformer;
import org.basinmc.plunger.mapping.mcp.VariableTableConstructionBytecodeTransformer;
import org.basinmc.plunger.mapping.mcp.parser.SRGNameMappingParser;
import org.basinmc.plunger.mapping.mcp.parser.SRGParameterNameMappingParser;
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
    NameMapping mainMapping;
    ParameterNameMapping parameterMapping;
    InnerClassMapping innerClassMapping;
    try (FileSystem fs = FileSystems.newFileSystem(srg, this.getClass().getClassLoader())) {
      mainMapping = new SRGNameMappingParser().parse(fs.getPath("joined.srg"));
      parameterMapping = new SRGParameterNameMappingParser().parse(fs.getPath("joined.exc"));
      innerClassMapping = InnerClassMapping.read(fs.getPath("exceptor.json"));
    } catch (IOException ex) {
      throw new TaskExecutionException("Failed to parse SRG mappings: " + ex.getMessage(), ex);
    }
    logger.info("  Success");

    NameMapping mapping = DelegatingNameMapping.builder()
        .withMapping(mainMapping)
        .withParameterNameMapping(parameterMapping)
        .build();

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

      Plunger plunger = BytecodePlunger.builder()
          .withParallelism()
          .withClassInclusionVoter((p) -> excludedElements.stream().noneMatch(p::startsWith))
          .withResourceVoter(
              (p) -> includedResources.stream().anyMatch((r) -> p.equals(r) || p.startsWith(r)))
          .withTransformer(new DebugAttributeBytecodeTransformer(true, true, false, false))
          .withTransformer(new VariableTableConstructionBytecodeTransformer())
          .withTransformer(new NameMappingBytecodeTransformer(mapping))
          .withTransformer(new InnerClassMappingBytecodeTransformer(innerClassMapping))
          .withTransformer(new InnerClassConstructorBytecodeTransformer())
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
