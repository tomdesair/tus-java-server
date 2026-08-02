package me.desair.tus.server;

import java.net.URI;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Helper utility class for S3 integration tests running against Testcontainers MinIO. Supports both
 * Docker and Podman container engines automatically.
 */
public final class TestUtils {

  private TestUtils() {
    // Utility class
  }

  /**
   * Check if a container runtime (Docker or Podman) is available locally.
   *
   * @return True if Docker or Podman is available for Testcontainers, false otherwise
   */
  public static boolean isContainerRuntimeAvailable() {
    try {
      if (DockerClientFactory.instance().isDockerAvailable()) {
        return true;
      }
    } catch (Throwable ignored) {
    }

    try {
      String userHome = System.getProperty("user.home", "");
      String[] possibleSockets = {
        "/var/run/docker.sock",
        "/run/podman/podman.sock",
        userHome + "/.local/share/containers/podman/machine/podman-machine-default/podman.sock",
        userHome + "/.local/share/containers/podman/machine/qemu/podman.sock"
      };

      for (String socketPath : possibleSockets) {
        if (new java.io.File(socketPath).exists()) {
          if (System.getProperty("DOCKER_HOST") == null) {
            System.setProperty("DOCKER_HOST", "unix://" + socketPath);
          }
          System.setProperty("TESTCONTAINERS_RYUK_DISABLED", "true");
          try {
            if (DockerClientFactory.instance().isDockerAvailable()) {
              return true;
            }
          } catch (Throwable ignored) {
          }
        }
      }

      Process process = new ProcessBuilder("podman", "info").start();
      if (process.waitFor() == 0) {
        System.setProperty("TESTCONTAINERS_RYUK_DISABLED", "true");
        return DockerClientFactory.instance().isDockerAvailable();
      }
    } catch (Throwable ignored) {
    }

    return false;
  }

  /**
   * Create and configure a GenericContainer running MinIO for integration testing.
   *
   * @return A configured GenericContainer instance (not started yet)
   */
  public static GenericContainer<?> createMinioContainer() {
    return new GenericContainer<>("minio/minio:RELEASE.2024-01-16T16-07-38Z")
        .withExposedPorts(9000)
        .withEnv("MINIO_ROOT_USER", "minioadmin")
        .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
        .withCommand("server /data");
  }

  /**
   * Create an AWS SDK v2 {@link S3Client} configured to connect to the given MinIO container.
   *
   * @param minio The active MinIO Testcontainer
   * @return Pre-configured S3Client
   */
  public static S3Client createS3Client(GenericContainer<?> minio) {
    String minioUrl = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
    return S3Client.builder()
        .endpointOverride(URI.create(minioUrl))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create("minioadmin", "minioadmin")))
        .region(Region.US_EAST_1)
        .forcePathStyle(true)
        .build();
  }

  /**
   * Create an S3 bucket if it does not already exist.
   *
   * @param s3Client The S3Client instance
   * @param bucketName Name of the bucket to create
   */
  public static void createBucket(S3Client s3Client, String bucketName) {
    try {
      s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
    } catch (Exception ignored) {
    }
  }
}
