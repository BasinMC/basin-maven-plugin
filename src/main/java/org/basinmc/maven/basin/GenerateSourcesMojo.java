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
package org.basinmc.maven.basin;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.artifact.DefaultArtifactCoordinate;
import org.basinmc.blackwater.Pipeline;
import org.basinmc.blackwater.artifact.ArtifactManager;
import org.basinmc.blackwater.artifact.ArtifactReference;
import org.basinmc.blackwater.artifacts.maven.MavenArtifactManager;
import org.basinmc.blackwater.artifacts.maven.MavenArtifactReference;
import org.basinmc.blackwater.task.error.TaskException;
import org.basinmc.blackwater.task.error.TaskParameterException;
import org.basinmc.blackwater.task.io.DownloadFileTask;
import org.basinmc.blackwater.tasks.git.GitAddTask;
import org.basinmc.blackwater.tasks.git.GitApplyMailArchiveTask;
import org.basinmc.blackwater.tasks.git.GitCommitTask;
import org.basinmc.blackwater.tasks.git.GitCreateBranchTask;
import org.basinmc.blackwater.tasks.git.GitInitTask;
import org.basinmc.blackwater.tasks.maven.FetchArtifactTask;
import org.basinmc.maven.basin.launcher.GameVersion;
import org.basinmc.maven.basin.launcher.LauncherVersionManifest;
import org.basinmc.maven.basin.task.ApplyMcpMappingsTask;
import org.basinmc.maven.basin.task.ApplySrgMappingsTask;
import org.basinmc.maven.basin.task.DecompileTask;
import org.basinmc.maven.basin.task.TransformSourceTask;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.jgit.lib.PersonIdent;

/**
 * <p>Fetches Minecraft and its mappings, applies any mappings, applies source code level access
 * transformations, formats the source code and applies git patches.</p>
 *
 * <p>When no patches exist, a warning will be logged and the build continues as usual (e.g. this
 * task may be used to perform the initial population of the project working tree without further
 * modifications).</p>
 *
 * @author <a href="mailto:johannesd@torchmind.com">Johannes Donath</a>
 */
@Mojo(
    name = "generate-sources",
    defaultPhase = LifecyclePhase.GENERATE_SOURCES,
    requiresOnline = true // TODO: go-offline in the future?
)
public class GenerateSourcesMojo extends AbstractMojo {

  /**
   * <p>Defines the group in which the plugin will store all of its artifacts.</p>
   *
   * <p>This value is specific to basin as wish to prevent accidental interaction with different
   * tools in the local repository but still hardcoded as other projects may safely rely on Basin
   * caches as long as they use this plugin.</p>
   */
  private static final String CACHE_GROUP_ID = "org.basinmc.minecraft";

  /**
   * <p>Defines the URL from which a particular MCP version can be retrieved.</p>
   *
   * <p>The following placeholders are present (using {@link String#format(String, Object...)}
   * placeholders):</p>
   *
   * <ol>
   *
   * <li><strong>Stability</strong> - either {@code snapshot} or {@code stable}</li>
   *
   * <li><strong>Version</strong> - mcp version (releases typically formatted as NN-V.VV.V while
   * snapshots are formatted as YYYYMMDD where N is a release number, Y is the release year, M is
   * the release month and D is the release day)</li>
   *
   * <li><strong>Game Version</strong> - Matches the SRG version number at the moment</li>
   *
   * </ol>
   */
  private static final String MCP_URL = "http://export.mcpbot.bspk.rs/mcp_%1$s/%2$s/mcp_%1$s-%2$s.zip";

  /**
   * <p>Defines the URL from which a particular SRG version can be retrieved.</p>
   *
   * <p>Note that this URL is technically a maven compatible URL. However, its descriptor (e.g.
   * pom.xml is missing and thus maven will fail to resolve this artifact when passed to our fetch
   * task implementation). As such, we'll simply use a hardcoded URL instead.</p>
   *
   * <p>The following placeholders are present (using {@link String#format(String, Object...)}
   * placeholders):</p>
   *
   * <ol>
   *
   * <li><strong>Version</strong> - The game version number (or rather desired SRG version
   * number)</li>
   *
   * </ol>
   */
  private static final String SRG_URL = "http://files.minecraftforge.net/maven/de/oceanlabs/mcp/mcp/%1$s/mcp-%1$s-srg.zip";
  @Parameter
  private File accessTransformationsFile;
  @Component
  private ArtifactFactory artifactFactory;
  @Component
  private ArtifactInstaller artifactInstaller;
  @Component
  private ArtifactResolver artifactResolver;
  @Component
  // such pure, much wow
  private org.apache.maven.shared.artifact.resolve.ArtifactResolver pureArtifactResolver;
  @Parameter(required = true)
  private String mcpMappingVersion;
  @Parameter(required = true)
  private String minecraftVersion;
  @Parameter(required = true, defaultValue = "${project.basedir}/src/main/patches")
  private File patchDirectory;
  @Component
  private MavenProject project;
  @Component
  private MavenSession session;
  @Parameter(required = true, defaultValue = "${project.basedir}/target/generated-sources/minecraft")
  private File sourceDirectory;
  @Parameter(property = "project.build.sourceEncoding")
  private String sourceEncoding;
  @Parameter(required = true)
  private String srgMappingVersion;

  /**
   * Configures a set of tasks which's sole purpose is to preload all version dependencies
   * (including client dependencies) and store them in the local maven repository.
   *
   * @param builder a reference to the pipeline builder.
   * @param version a reference to the game version manifest.
   */
  private void configureDependencyPreload(@NonNull Pipeline.Builder builder,
      @NonNull GameVersion version) {
    ArtifactRepositoryPolicy policy = new ArtifactRepositoryPolicy(true,
        ArtifactRepositoryPolicy.UPDATE_POLICY_DAILY,
        ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN);

    List<ArtifactRepository> repositories = new ArrayList<>();
    repositories.add(
        new MavenArtifactRepository(
            "maven-central",
            "https://repo1.maven.org/maven2",
            new DefaultRepositoryLayout(),
            policy,
            policy
        )
    );
    repositories.add(
        new MavenArtifactRepository(
            "minecraft",
            "https://libraries.minecraft.net/",
            new DefaultRepositoryLayout(),
            policy,
            policy
        )
    );

    ProjectBuildingRequest request = new DefaultProjectBuildingRequest(
        this.session.getProjectBuildingRequest());
    request.setRemoteRepositories(repositories);

    version.getLibraries().stream()
        .filter((l) -> l.getDownloads().getArtifact().isPresent())
        .forEach((l) -> {
          DefaultArtifactCoordinate coordinate = new DefaultArtifactCoordinate();

          String[] tokens = StringUtils.split(l.getName(), ":");
          if (tokens.length < 3 || tokens.length > 5) {
            throw new IllegalArgumentException("Invalid artifact \"" + l.getName()
                + "\" must be in format groupId:artifactId:version[:packaging[:classifier]]");
          }

          coordinate.setGroupId(tokens[0]);
          coordinate.setArtifactId(tokens[1]);
          coordinate.setVersion(tokens[2]);
          if (tokens.length >= 4) {
            coordinate.setExtension(tokens[3]);
          }
          if (tokens.length == 5) {
            coordinate.setClassifier(tokens[4]);
          }

          try {
            builder.withTask(new FetchArtifactTask(this.pureArtifactResolver, request, coordinate))
                .register();
          } catch (TaskParameterException ex) {
            throw new IllegalStateException(
                "Failed to register task for dependency " + coordinate + ": " + ex.getMessage(),
                ex);
          }
        });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    // figure out which charset we were asked to encode all of our source files with
    Charset charset = Charset.defaultCharset();

    if (this.sourceEncoding != null) {
      charset = Charset.forName(this.sourceEncoding);
    }

    // create an artifact manager which grants Blackwater direct access to maven's repository system
    // so that we don't have to redo all this stuff over and over again
    ArtifactManager artifactManager = new MavenArtifactManager(
        this.artifactFactory,
        this.artifactInstaller,
        this.artifactResolver,
        this.session.getLocalRepository()
    );

    // pre-allocate references for all artifacts we are going to interact with (all of them are our
    // own cached versions of intermediate build steps)
    ArtifactReference srgReference = new MavenArtifactReference(
        CACHE_GROUP_ID,
        "srg",
        this.srgMappingVersion,
        "zip",
        false
    );
    ArtifactReference mcpReference = new MavenArtifactReference(
        CACHE_GROUP_ID,
        "mcp",
        this.mcpMappingVersion,
        "zip",
        false
    );
    ArtifactReference vanillaReference = new MavenArtifactReference(
        CACHE_GROUP_ID,
        "server",
        this.minecraftVersion,
        "jar",
        false
    );
    ArtifactReference srgMappedReference = new MavenArtifactReference(
        CACHE_GROUP_ID,
        "server-srg",
        this.minecraftVersion + "-" + this.srgMappingVersion,
        "jar",
        false
    );
    ArtifactReference mcpMappedReference = new MavenArtifactReference(
        CACHE_GROUP_ID,
        "server-mcp",
        this.minecraftVersion + "-" + this.srgMappingVersion + "-" + this.mcpMappingVersion,
        "jar",
        false
    );
    ArtifactReference decompiledReference = new MavenArtifactReference(
        CACHE_GROUP_ID,
        "server-mcp",
        this.minecraftVersion + "-" + this.srgMappingVersion + "-" + this.mcpMappingVersion,
        "jar",
        "source",
        false
    );

    // allocate a path matcher on the system's standard filesystem in order to permit filtering of
    // resource files
    PathMatcher sourceFileMatcher = FileSystems.getDefault().getPathMatcher("glob:**.java");

    // fetch the launcher version manifest so we know where to retrieve the game file from and which
    // dependencies to populate and pass on
    GameVersion version;

    try {
      version = LauncherVersionManifest.get()
          .getVersion(this.minecraftVersion)
          .orElseThrow(() -> new MojoExecutionException(
              "Illegal Minecraft version: " + this.minecraftVersion + " does not exist"))
          .get();
    } catch (IOException ex) {
      throw new MojoExecutionException(
          "Failed to retrieve one or more launcher manifests: " + ex.getMessage(), ex);
    }

    try {
      // Build Pipeline overview:
      //
      //   (1)  Resolve SRG mappings using a maven or local repository
      //   (2)  Download the MCP mappings from mcpbot's website
      //   (3)  Download the Minecraft binary from Mojang's servers
      //   (4)  Pre-Populate all game dependencies
      //   (5)  Apply SRG mappings to vanilla
      //   (6)  Apply the MCP mappings on top of SRG
      //   (7)  Decompile the mapped version
      //   (8)  Apply source code access transformations, reformat and extract
      //   (9)  Initialize git repository
      //   (10) Add files to repository
      //   (11) Create initial commit
      //   (12) Create a reference branch called "upstream"
      //   (13) Apply git patches
      //
      // @formatter:off
      Pipeline pipeline = Pipeline.builder()
          .withArtifactManager(artifactManager)
          .withTask(new DownloadFileTask(this.getSrgUrl())) // (1)
              .withOutputArtifact(srgReference)
              .register()
          .withTask(new DownloadFileTask(this.getMcpUrl())) // (2)
              .withOutputArtifact(mcpReference)
              .register()
          .withTask(new DownloadFileTask(version.getDownloads().getServer().getUrl())) // (3)
              .withOutputArtifact(vanillaReference)
              .register()
          .apply((b) -> this.configureDependencyPreload(b, version)) // (4)
          .withTask(new ApplySrgMappingsTask()) // (5)
              .withInputArtifact(vanillaReference)
              .withOutputArtifact(srgMappedReference)
              .withParameter("srg", srgReference)
              .register()
          .withTask(new ApplyMcpMappingsTask()) // (6)
              .withInputArtifact(srgMappedReference)
              .withOutputArtifact(mcpMappedReference)
              .withParameter("mcp", mcpReference)
              .register()
          .withTask(new DecompileTask(charset, this.getDependencies(version))) // (7)
              .withInputArtifact(mcpMappedReference)
              .withOutputArtifact(decompiledReference)
              .register()
          .withTask(new TransformSourceTask(charset, this.accessTransformationsFile)) // (8)
              .withInputArtifact(decompiledReference)
              .withOutputFile(this.sourceDirectory.toPath())
              .register()
          .withTask(new GitInitTask()) // (9)
              .withInputFile(this.sourceDirectory.toPath())
              .register()
          .withTask(new GitAddTask(sourceFileMatcher::matches)) // (10)
              .withInputFile(this.sourceDirectory.toPath())
              .register()
          .withTask(new GitCommitTask(new PersonIdent("Basin", "contact@basinmc.org"), "Decompiled Minecraft")) // (11)
              .withInputFile(this.sourceDirectory.toPath())
              .register()
          .withTask(new GitCreateBranchTask("upstream")) // (12)
              .withInputFile(this.sourceDirectory.toPath())
              .register()
          .withTask(new GitApplyMailArchiveTask("upstream")) // (13)
              .withInputFile(this.patchDirectory.toPath())
              .withOutputFile(this.sourceDirectory.toPath())
              .register()
          .build();
      // @formatter:on

      pipeline.execute();

      this.project.addCompileSourceRoot(
          this.project.getBasedir().toPath().relativize(this.sourceDirectory.toPath()).toString());
    } catch (TaskParameterException ex) {
      throw new MojoExecutionException("Failed to configure pipeline: " + ex.getMessage(), ex);
    } catch (TaskException ex) {
      throw new MojoExecutionException("Failed to execute pipeline: " + ex.getMessage(), ex);
    }
  }

  /**
   * Retrieves a set of artifact references which refer to the dependencies which are declared for a
   * specific game version (and have typically already been preloaded).
   *
   * @param version a game version.
   * @return a set of references.
   */
  @NonNull
  public Set<ArtifactReference> getDependencies(@NonNull GameVersion version) {
    return version.getLibraries().stream()
        .filter((l) -> l.getDownloads().getArtifact().isPresent())
        .map((l) -> {

          String[] tokens = StringUtils.split(l.getName(), ":");
          if (tokens.length < 3 || tokens.length > 5) {
            throw new IllegalArgumentException("Invalid artifact \"" + l.getName()
                + "\" must be in format groupId:artifactId:version[:packaging[:classifier]]");
          }

          return new MavenArtifactReference(
              tokens[0],
              tokens[1],
              tokens[2],
              tokens.length == 5 ? tokens[4] : "jar",
              tokens.length >= 4 ? tokens[3] : null,
              false
          );
        })
        .collect(Collectors.toSet());
  }

  /**
   * Retrieves the URL from which the MCP mappings may be downloaded.
   *
   * @return a download URL.
   * @throws MojoExecutionException when the version name is invalid.
   */
  @NonNull
  private URL getMcpUrl() throws MojoExecutionException {
    int separatorIndex = this.mcpMappingVersion.indexOf('-');

    if (separatorIndex == -1) {
      throw new MojoExecutionException(
          "Illegal MCP mapping version: Not formatted as <stability>-<version>");
    }

    String stability = this.mcpMappingVersion.substring(0, separatorIndex);
    String version = this.mcpMappingVersion.substring(separatorIndex + 1);

    // TODO: Permit application of differing MCP versions
    try {
      return new URL(String.format(MCP_URL, stability, version));
    } catch (MalformedURLException ex) {
      throw new MojoExecutionException("Illegal MCP archive URL: " + ex.getMessage(), ex);
    }
  }

  /**
   * Retrieves the URL from which the SRG mappings may be downloaded.
   *
   * @return a download URL.
   * @throws MojoExecutionException when the version name is invalid.
   */
  @NonNull
  private URL getSrgUrl() throws MojoExecutionException {
    try {
      return new URL(String.format(SRG_URL, this.srgMappingVersion));
    } catch (MalformedURLException ex) {
      throw new MojoExecutionException("Illegal SRG archive URL: " + ex.getMessage(), ex);
    }
  }
}
