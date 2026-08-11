package io.kcg.sir.application.conformance;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Captures the full qualification tuple for one conformance run. The
 * qualification is always bound to a recorded reference-environment tuple.
 */
public final class ConformanceEnvironment {

    private final String conformanceContractRevision;
    private final String targetProfileId;
    private final String loweredIrVersion;
    private final String javaVendor;
    private final String javaVersion;
    private final String osName;
    private final String osArch;
    private final String filesystemProvider;
    private final Path mavenExecutable;
    private final String mavenVersion;
    private final Path mavenRepo;
    private final boolean mavenOfflineMode;
    private final String expectedMysqlServerUuid;
    private final String observedMysqlServerUuid;
    private final String mysqlServerVersion;
    private final String targetRuntimeConnectorVersion;
    private final String harnessJdbcDriverVersion;
    private final SchemaName schemaName;
    private final Path workParent;
    private final Path evidenceParent;
    private final int serverPort;

    public ConformanceEnvironment(
            String conformanceContractRevision,
            String targetProfileId,
            String loweredIrVersion,
            String javaVendor,
            String javaVersion,
            String osName,
            String osArch,
            String filesystemProvider,
            Path mavenExecutable,
            String mavenVersion,
            Path mavenRepo,
            boolean mavenOfflineMode,
            String expectedMysqlServerUuid,
            String observedMysqlServerUuid,
            String mysqlServerVersion,
            String targetRuntimeConnectorVersion,
            String harnessJdbcDriverVersion,
            SchemaName schemaName,
            Path workParent,
            Path evidenceParent,
            int serverPort) {
        this.conformanceContractRevision = Objects.requireNonNull(conformanceContractRevision, "conformanceContractRevision");
        this.targetProfileId = Objects.requireNonNull(targetProfileId, "targetProfileId");
        this.loweredIrVersion = Objects.requireNonNull(loweredIrVersion, "loweredIrVersion");
        this.javaVendor = Objects.requireNonNull(javaVendor, "javaVendor");
        this.javaVersion = Objects.requireNonNull(javaVersion, "javaVersion");
        this.osName = Objects.requireNonNull(osName, "osName");
        this.osArch = Objects.requireNonNull(osArch, "osArch");
        this.filesystemProvider = Objects.requireNonNull(filesystemProvider, "filesystemProvider");
        this.mavenExecutable = Objects.requireNonNull(mavenExecutable, "mavenExecutable");
        this.mavenVersion = Objects.requireNonNull(mavenVersion, "mavenVersion");
        this.mavenRepo = Objects.requireNonNull(mavenRepo, "mavenRepo");
        this.mavenOfflineMode = mavenOfflineMode;
        this.expectedMysqlServerUuid = Objects.requireNonNull(expectedMysqlServerUuid, "expectedMysqlServerUuid");
        this.observedMysqlServerUuid = observedMysqlServerUuid;
        this.mysqlServerVersion = mysqlServerVersion;
        this.targetRuntimeConnectorVersion = targetRuntimeConnectorVersion;
        this.harnessJdbcDriverVersion = Objects.requireNonNull(harnessJdbcDriverVersion, "harnessJdbcDriverVersion");
        this.schemaName = Objects.requireNonNull(schemaName, "schemaName");
        this.workParent = Objects.requireNonNull(workParent, "workParent");
        this.evidenceParent = Objects.requireNonNull(evidenceParent, "evidenceParent");
        this.serverPort = serverPort;
    }

    public String conformanceContractRevision() { return conformanceContractRevision; }
    public String targetProfileId() { return targetProfileId; }
    public String loweredIrVersion() { return loweredIrVersion; }
    public String javaVendor() { return javaVendor; }
    public String javaVersion() { return javaVersion; }
    public String osName() { return osName; }
    public String osArch() { return osArch; }
    public String filesystemProvider() { return filesystemProvider; }
    public Path mavenExecutable() { return mavenExecutable; }
    public String mavenVersion() { return mavenVersion; }
    public Path mavenRepo() { return mavenRepo; }
    public boolean mavenOfflineMode() { return mavenOfflineMode; }
    public String expectedMysqlServerUuid() { return expectedMysqlServerUuid; }
    public Optional<String> observedMysqlServerUuid() { return Optional.ofNullable(observedMysqlServerUuid); }
    public Optional<String> mysqlServerVersion() { return Optional.ofNullable(mysqlServerVersion); }
    public Optional<String> targetRuntimeConnectorVersion() { return Optional.ofNullable(targetRuntimeConnectorVersion); }
    public String harnessJdbcDriverVersion() { return harnessJdbcDriverVersion; }
    public SchemaName schemaName() { return schemaName; }
    public Path workParent() { return workParent; }
    public Path evidenceParent() { return evidenceParent; }
    public int serverPort() { return serverPort; }
}