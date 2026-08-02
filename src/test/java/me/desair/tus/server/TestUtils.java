package me.desair.tus.server;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * Helper utility class for S3 integration tests running against Testcontainers MinIO using the
 * MinIO Java SDK. Supports both Docker and Podman container engines automatically.
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
   * Create a {@link MinioClient} configured to connect to the given MinIO container.
   *
   * @param minio The active MinIO Testcontainer
   * @return Pre-configured MinioClient
   */
  public static MinioClient createMinioClient(GenericContainer<?> minio) {
    String minioUrl = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
    return MinioClient.builder().endpoint(minioUrl).credentials("minioadmin", "minioadmin").build();
  }

  /**
   * Ensures an S3 bucket exists using MinIO Client.
   *
   * @param minioClient The MinIO Client
   * @param bucket The S3 bucket name
   */
  public static void createBucket(MinioClient minioClient, String bucket) {
    try {
      boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
      if (!found) {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to create bucket " + bucket, e);
    }
  }
}
