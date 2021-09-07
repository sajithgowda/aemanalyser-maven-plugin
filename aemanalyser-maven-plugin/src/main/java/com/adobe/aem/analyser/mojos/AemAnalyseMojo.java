/*
  Copyright 2020 Adobe. All rights reserved.
  This file is licensed to you under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License. You may obtain a copy
  of the License at http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software distributed under
  the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
  OF ANY KIND, either express or implied. See the License for the specific language
  governing permissions and limitations under the License.
*/
package com.adobe.aem.analyser.mojos;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.adobe.aem.analyser.AemAggregator;
import com.adobe.aem.analyser.AemAnalyser;
import com.adobe.aem.analyser.AemAnalyserResult;
import com.adobe.aem.analyser.AemPackageConverter;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.builder.ArtifactProvider;
import org.apache.sling.feature.builder.FeatureProvider;
import org.apache.sling.feature.cpconverter.ConverterException;
import org.apache.sling.feature.io.artifacts.ArtifactManager;
import org.apache.sling.feature.io.artifacts.ArtifactManagerConfig;
import org.apache.sling.feature.io.json.FeatureJSONReader;

public class AemAnalyseMojo extends AbstractMojo {

    /**
     * The artifact id of the sdk api jar. The artifact id is automatically detected by this plugin,
     * by using this configuration the auto detection can be disabled
     */
    @Parameter(property = "sdkArtifactId")
    String sdkArtifactId;
    
    /**
     * The version of the sdk api. Can be used to specify the exact version to be used. Otherwise the
     * plugin detects the version to use.
     */
    @Parameter(required = false, property = "sdkVersion")
    String sdkVersion;

    /**
     * Use dependency versions. If this is enabled, the version for the SDK and the Add-ons is taken
     * from the project dependencies. By default, the latest version is used.
     */
    @Parameter(required = false, defaultValue = "false", property = "sdkUseDependency")
    boolean useDependencyVersions;

    /**
     * The list of add ons.
     */
    @Parameter
    List<Addon> addons;
    
    /**
     * The analyser tasks run be the analyser
     */
    @Parameter(defaultValue = AemAnalyser.DEFAULT_TASKS,
        property = "analyserTasks")
    List<String> analyserTasks;

    /**
     * Optional configurations for the analyser tasks
     */
    @Parameter
    Map<String, Properties> analyserTaskConfigurations;

    /**
     * Skip the execution
     */
    @Parameter(defaultValue = "false", property = "aem.analyser.skip")
    boolean skip;

    /**
     * Fail on analyser errors?
     */
    @Parameter(defaultValue = "true", property = "failon.analyser.errors")
    private boolean failOnAnalyserErrors;

    /**
     * Only analyze the package attached to the project with the given classifier.
     */
    @Parameter(property = "aem.analyser.classifier")
    private String classifier;

    /**
     * If enabled, all analyser warnings will be turned into errors and fail the build.
     * @since 1.0.12
     */
    @Parameter(defaultValue = "false", property = "aem.analyser.strict")
    private boolean strictValidation;

    /**
     * The maven project
     */
    @Parameter(property = "project", readonly = true, required = true)
    protected MavenProject project;

    /**
     * The artifact manager to resolve artifacts
     */
    @Component
    protected ArtifactHandlerManager artifactHandlerManager;

    /**
     * The artifact resolver
     */
    @Component
    protected ArtifactResolver artifactResolver;

    /**
     * The maven session
     */
    @Parameter(property = "session", readonly = true, required = true)
    protected MavenSession mavenSession;

    @Component
    private ArtifactMetadataSource artifactMetadataSource;

    @Parameter( defaultValue = "${plugin}", readonly = true ) // Maven 3 only
    private PluginDescriptor plugin;
 
    /**
     * Artifact cache
     */
    private final Map<String, Artifact> artifactCache = new ConcurrentHashMap<>();

    /**
     * Get the output directory for the content package converter
     * @return The directory
     */
    private File getConversionOutputDir() {
        return new File(project.getBuild().getDirectory().concat(File.separator).concat(Constants.CONVERTER_DIRECTORY));
    }

    /**
     * The directory for the generated feature models
     * @return The directory
     */
    private File getGeneratedFeaturesDir() {
        return new File(getConversionOutputDir(), Constants.FM_DIRECTORY);
    }

    /**
     * Detect if the execution should be skipped
     * @return {@code true} if execution should be skipped
     */
    boolean skipRun() {
        // check env var
        final String skipVar = System.getenv(Constants.SKIP_ENV_VAR);
        boolean skipExecution = skipVar != null && skipVar.length() > 0;
        if ( skipExecution ) {
            getLog().info("Skipping AEM analyser plugin as variable " + Constants.SKIP_ENV_VAR + " is set.");
        } else if ( this.skip ) {
            skipExecution = true;
            getLog().info("Skipping AEM analyser plugin as configured.");
        }

        return skipExecution;
    }

    /**
     * Execute the plugin
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (this.skipRun()) {
            return;
        }

        final VersionUtil versionUtil = new VersionUtil(this.getLog(), this.project, 
                this.artifactHandlerManager, this.artifactMetadataSource, 
                this.project.getRemoteArtifactRepositories(), this.project.getPluginArtifactRepositories(), 
                this.mavenSession.getLocalRepository(), this.mavenSession.isOffline());

        versionUtil.checkPluginVersion(this.plugin.getGroupId(), this.plugin.getArtifactId(), this.plugin.getVersion());

        final ArtifactId sdkId = versionUtil.getSDKArtifactId(this.sdkArtifactId, this.sdkVersion, this.useDependencyVersions);
        final List<ArtifactId> addons = versionUtil.discoverAddons(this.addons, this.useDependencyVersions);

        // log warnings at start and end
        for(final String msg : versionUtil.getVersionWarnings()) {
            this.getLog().warn(msg);
        }
        
        // 1. Phase : convert content packages
        this.convertContentPackages();

        try (ArtifactManager artifactManager = getArtifactManager()) {
            ArtifactProvider compositeArtifactProvider = getCompositeArtifactProvider(artifactManager);
            // 2. Phase : aggregate feature models
            final List<Feature> features = this.aggregateFeatureModels(sdkId, addons, compositeArtifactProvider);

            // 3. Phase : analyse features
            this.analyseFeatures(features, versionUtil.getVersionWarnings(), compositeArtifactProvider);
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    /**
     * Convert the content packages
     * @throws MojoExecutionException If anything goes wrong
     */
    void convertContentPackages() throws MojoExecutionException {
        final AemPackageConverter converter = new AemPackageConverter();
        converter.setArtifactIdOverride(new ArtifactId(project.getGroupId(), project.getArtifactId(), project.getVersion(), null, "slingosgifeature").toMvnId());
        converter.setConverterOutputDirectory(getConversionOutputDir());
        converter.setFeatureOutputDirectory(getGeneratedFeaturesDir());
    
        for(final Artifact contentPackage: getContentPackages()) {
            final File source = contentPackage.getFile();
            try {
                converter.convert(Collections.singletonMap(contentPackage.toString(), source));
            } catch ( final ConverterException ce) {
                getLog().error(ce.getMessage());
                throw new MojoExecutionException(ce.getMessage());        
            } catch (final IOException t) {
                throw new MojoExecutionException("Content Package Converter Exception " + t.getMessage(), t);        
            }
        }
    }

    /**
     * Search for relevant content packages.
     * @return The list of artifacts (non empty)
     * @throws MojoExecutionException If anything goes wrong, for example no content packages are found
     */
    private List<Artifact> getContentPackages() throws MojoExecutionException {
        if (!Constants.PACKAGING_AEM_ANALYSE.equals(project.getPackaging())) {
            if (classifier != null) {
                // look for attached artifact with given classifier
                for (Artifact artifact : project.getAttachedArtifacts()) {
                    if (classifier.equals(artifact.getClassifier())) {
                        getLog().info("Using attached artifact with classifier '" + classifier + "' as content package: " + project.getArtifact());
                        return Collections.singletonList(artifact);
                    }
                }
                throw new MojoExecutionException("No attached artifact with classifier " + classifier + " found for project.");
            } else {
                // Use the current project artifact as the content package
                getLog().info("Using current project as content package: " + project.getArtifact());
                return Collections.singletonList(project.getArtifact());
            }
        } else {
            final List<Artifact> result = new ArrayList<>();
            for (final Artifact d : project.getDependencyArtifacts()) {
                if (Constants.PACKAGING_ZIP.equals(d.getType()) || Constants.PACKAGING_CONTENT_PACKAGE.equals(d.getType())) {
                    // If a dependency is of type 'zip' it is assumed to be a content package
                    result.add(d);
                }
            }    
            if (result.isEmpty()) {
                throw new MojoExecutionException("No content packages found for project.");
            }
            getLog().info("Found content packages from dependencies: " + result);
            return result;
        }

    }

    /**
     * Aggregate the feature models
     * @return A list of feature models
     * @throws MojoExecutionException If anything goes wrong
     */
    List<Feature> aggregateFeatureModels(final ArtifactId sdkId, final List<ArtifactId> addons, final ArtifactProvider artifactProvider) throws MojoExecutionException {
        try {
            final AemAggregator a = new AemAggregator();
            a.setFeatureOutputDirectory(getGeneratedFeaturesDir());
            a.setArtifactProvider(artifactProvider);
            a.setFeatureProvider(new FeatureProvider() {
                @Override
                public Feature provide(final ArtifactId id) {
                    return getOrResolveFeature(id);
                }
            });
            a.setProjectId(new ArtifactId(project.getGroupId(), project.getArtifactId(), project.getVersion(), null, null));
            a.setSdkId(sdkId);
            a.setAddOnIds(addons);
            
            return a.aggregate();
        
        } catch (final IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    /**
     * Analyse the features
     * 
     * @param features The features
     * @param additionalWarnings List of additional warnings, might be empty
     * @throws MojoFailureException If the analysis fails
     * @throws MojoExecutionException If something goes wrong
     */
    void analyseFeatures(final List<Feature> features, final List<String> additionalWarnings, final ArtifactProvider artifactProvider) throws MojoFailureException, MojoExecutionException {
        boolean hasErrors = false;
        try {
            final AemAnalyser analyser = new AemAnalyser();
            analyser.setArtifactProvider(artifactProvider);
            analyser.setIncludedTasks(this.getAnalyserTasks());
            analyser.setTaskConfigurations(this.getAnalyserTaskConfigurations());

            final AemAnalyserResult result = analyser.analyse(features);
            
            // print additional warnings first
            for(final String msg : additionalWarnings) {
                if ( strictValidation ) {
                    getLog().error(msg);
                } else {
                    getLog().warn(msg);
                }
            }
            // now analyser warnings
            for(final String msg : result.getWarnings()) {
                if ( strictValidation ) {
                    getLog().error(msg);
                } else {
                    getLog().warn(msg);
                }
            }
            // finally, analyser errors
            for(final String msg : result.getErrors()) {
                getLog().error(msg);
            }
            hasErrors = result.hasErrors() || (strictValidation && result.hasWarnings());

        } catch ( final Exception e) {
            throw new MojoExecutionException("A fatal error occurred while analysing the features, see error cause:",
                    e);
        }

        if (hasErrors) {
            if ( failOnAnalyserErrors ) {
                throw new MojoFailureException(
                    "One or more feature analyser(s) detected feature error(s), please read the plugin log for more details");
            }
            getLog().warn("Errors found during analyser run, but this plugin is configured to ignore errors and continue the build!");
        }        
    }

    /**
     * Get the composite artifact provider of a default artifact manager and a custom provider which is able to resolve the project attached artifacts
     * @return the composite provider
     * @throws IOException If creation of the provider fails
     */
    ArtifactProvider getCompositeArtifactProvider(final ArtifactManager artifactManager) throws IOException {
        return new ArtifactProvider() {

            @Override
            public URL provide(final ArtifactId id) {
                URL url = artifactManager.provide(id);
                if (url != null) {
                    return url;
                }
                try {
                    return getOrResolveArtifact(id).getFile().toURI().toURL();
                } catch (final MalformedURLException e) {
                    getLog().debug("Malformed url " + e.getMessage(), e);
                    // ignore
                    return null;
                }
            }
        };
    }

    /**
     * Get the analyser task configuration
     * @return The analyser task configuration
     */
    Map<String, Map<String, String>> getAnalyserTaskConfigurations() {
        Map<String, Map<String, String>> config = new HashMap<>();
        if (this.analyserTaskConfigurations != null) {
            for(final Map.Entry<String, Properties> entry : this.analyserTaskConfigurations.entrySet()) {
                final Map<String, String> m = new HashMap<>();

                entry.getValue().stringPropertyNames().forEach(n -> m.put(n, entry.getValue().getProperty(n)));
                config.put(entry.getKey(), m);
            }
        }

        return config;
    }

    /**
     * Get the analyser task
     * @return The tasks
     */
    Set<String> getAnalyserTasks() {
        return new LinkedHashSet<>(this.analyserTasks);
    }

    /**
     * Get an artifact manager. The returned one must be shut down.
     * @return The provider
     * @throws IOException If the provider can't be created
     */
    ArtifactManager getArtifactManager() throws IOException {
        ArtifactManagerConfig amcfg = new ArtifactManagerConfig();
        amcfg.setRepositoryUrls(new String[] { getConversionOutputDir().toURI().toURL().toString() });

        return ArtifactManager.getArtifactManager(amcfg);
    }

    /**
     * Find the artifact in the collection
     * @param id The artifact id
     * @param artifacts The collection
     * @return The artifact or {@code null}
     */
    private static Artifact findArtifact(final ArtifactId id, final Collection<Artifact> artifacts) {
        if (artifacts != null) {
            for(final Artifact artifact : artifacts) {
                if ( artifact.getGroupId().equals(id.getGroupId())
                   && artifact.getArtifactId().equals(id.getArtifactId())
                   && artifact.getVersion().equals(id.getVersion())
                   && artifact.getType().equals(id.getType())
                   && ((id.getClassifier() == null && artifact.getClassifier() == null) || (id.getClassifier() != null && id.getClassifier().equals(artifact.getClassifier()))) ) {
                    return artifact.getFile() == null ? null : artifact;
                }
            }
        }
        return null;
    }

    /**
     * Get a resolved Artifact from the coordinates provided
     *
     * @param id The ID of the artifact to get/resolve.
     * @return the artifact, which has been resolved.
     * @throws RuntimeException if the artifact can't be resolved
     */
    @SuppressWarnings("deprecation")
    Artifact getOrResolveArtifact(final ArtifactId id) {
        Artifact result = this.artifactCache.get(id.toMvnId());
        if ( result == null ) {
            result = findArtifact(id, project.getAttachedArtifacts());
            if ( result == null ) {
                result = findArtifact(id, project.getDependencyArtifacts());
                if ( result == null ) {
                    final Artifact prjArtifact = new DefaultArtifact(id.getGroupId(),
                            id.getArtifactId(),
                            VersionRange.createFromVersion(id.getVersion()),
                            Artifact.SCOPE_PROVIDED,
                            id.getType(),
                            id.getClassifier(),
                            artifactHandlerManager.getArtifactHandler(id.getType()));
                    try {
                        this.artifactResolver.resolve(prjArtifact, project.getRemoteArtifactRepositories(), this.mavenSession.getLocalRepository());
                    } catch (final ArtifactResolutionException | ArtifactNotFoundException e) {
                        throw new RuntimeException("Unable to get artifact for " + id.toMvnId(), e);
                    }
                    result = prjArtifact;
                }
            }
            this.artifactCache.put(id.toMvnId(), result);
        }

        return result;
    }
    
    /**
     * Get a resolved feature
     *
     * @param id The artifact id of the feature
     * @return The feature
     * @throws RuntimeException if the feature can't be resolved
     */
    Feature getOrResolveFeature(final ArtifactId id) {
        final File artFile = getOrResolveArtifact(id).getFile();
        try (final Reader reader = new FileReader(artFile)) {
            return FeatureJSONReader.read(reader, artFile.getAbsolutePath());
        } catch (final IOException ioe) {
            throw new RuntimeException("Unable to read feature file " + artFile + " for " + id.toMvnId(), ioe);
        }
    }
}
