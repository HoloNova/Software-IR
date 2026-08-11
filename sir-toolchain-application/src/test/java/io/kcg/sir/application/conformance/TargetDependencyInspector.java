package io.kcg.sir.application.conformance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.kcg.sir.lowering.springboot.model.ProjectArtifact.MavenDependency;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;

/**
 * Inspects the Target runtime Connector/J version from two independent
 * sources and records the harness JDBC driver version separately.
 *
 * <p>The target runtime version is authoritative only when the Maven
 * dependency in the current {@link SpringBootLoweredModel} and the
 * dependency in the current generated {@code pom.xml} agree. The harness
 * reads those current artifacts; it does not copy a Target version into a
 * test constant or replace the Target's version authority.
 *
 * <p>The harness JDBC driver is a test tool dependency. Its version is
 * independently recorded and never enters Lowered IR, generated POM, Target
 * Profile, or production dependencies.
 */
public final class TargetDependencyInspector {

    private static final String CONNECTOR_GROUP_ID = "com.mysql";
    private static final String CONNECTOR_ARTIFACT_ID = "mysql-connector-j";

    private final String targetRuntimeConnectorVersion;
    private final String generatedPomConnectorVersion;
    private final String harnessJdbcDriverVersion;
    private final boolean versionsAgree;

    private TargetDependencyInspector(String targetRuntimeConnectorVersion,
                                      String generatedPomConnectorVersion,
                                      String harnessJdbcDriverVersion,
                                      boolean versionsAgree) {
        this.targetRuntimeConnectorVersion = targetRuntimeConnectorVersion;
        this.generatedPomConnectorVersion = generatedPomConnectorVersion;
        this.harnessJdbcDriverVersion = harnessJdbcDriverVersion;
        this.versionsAgree = versionsAgree;
    }

    /**
     * Inspect the Target runtime and generated POM for the Connector/J
     * version, and record the harness JDBC driver version independently.
     *
     * @param loweredModel          the current SpringBootLoweredModel
     * @param generatedPomXmlFile   the generated pom.xml file on disk
     * @param harnessJdbcDriverVersion the harness JDBC driver version
     * @return the inspection result
     */
    public static TargetDependencyInspector inspect(
            SpringBootLoweredModel loweredModel,
            Path generatedPomXmlFile,
            String harnessJdbcDriverVersion) throws IOException {
        Objects.requireNonNull(loweredModel, "loweredModel");
        Objects.requireNonNull(generatedPomXmlFile, "generatedPomXmlFile");
        Objects.requireNonNull(harnessJdbcDriverVersion, "harnessJdbcDriverVersion");

        // 1. Read Target runtime Connector/J from the Lowered model.
        String targetVersion = findConnectorVersion(loweredModel.mavenProject().dependencies());

        // 2. Read the generated pom.xml Connector/J version.
        String pomVersion = findConnectorVersionInPomXml(generatedPomXmlFile);

        // 3. The two must agree.
        boolean agree = targetVersion != null
                && targetVersion.equals(pomVersion);

        return new TargetDependencyInspector(
                targetVersion, pomVersion, harnessJdbcDriverVersion, agree);
    }

    private static String findConnectorVersion(List<MavenDependency> dependencies) {
        for (MavenDependency dep : dependencies) {
            if (CONNECTOR_GROUP_ID.equals(dep.groupId())
                    && CONNECTOR_ARTIFACT_ID.equals(dep.artifactId())) {
                return dep.version();
            }
        }
        return null;
    }

    private static String findConnectorVersionInPomXml(Path pomXmlFile) throws IOException {
        String content = Files.readString(pomXmlFile, StandardCharsets.UTF_8);
        // Simple XML parsing: find <dependency> blocks with mysql-connector-j.
        String marker = "<artifactId>" + CONNECTOR_ARTIFACT_ID + "</artifactId>";
        int markerIdx = content.indexOf(marker);
        if (markerIdx < 0) {
            return null;
        }
        // Look backwards for <groupId> and forwards for <version>.
        int depStart = content.lastIndexOf("<dependency>", markerIdx);
        if (depStart < 0) {
            return null;
        }
        int depEnd = content.indexOf("</dependency>", markerIdx);
        if (depEnd < 0) {
            return null;
        }
        String depBlock = content.substring(depStart, depEnd);
        int versionStart = depBlock.indexOf("<version>");
        int versionEnd = depBlock.indexOf("</version>");
        if (versionStart < 0 || versionEnd < 0 || versionEnd <= versionStart) {
            return null;
        }
        return depBlock.substring(versionStart + "<version>".length(), versionEnd).trim();
    }

    public Optional<String> targetRuntimeConnectorVersion() {
        return Optional.ofNullable(targetRuntimeConnectorVersion);
    }

    public Optional<String> generatedPomConnectorVersion() {
        return Optional.ofNullable(generatedPomConnectorVersion);
    }

    public String harnessJdbcDriverVersion() {
        return harnessJdbcDriverVersion;
    }

    public boolean versionsAgree() {
        return versionsAgree;
    }
}