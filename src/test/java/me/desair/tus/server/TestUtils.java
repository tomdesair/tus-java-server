package me.desair.tus.server;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.io.File;
import java.util.Locale;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * Helper utility class for integration tests running against Testcontainers RustFS (S3) and Azurite
 * (Azure Blob). Supports both Docker and Podman container engines automatically.
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
    // Configure Docker API version 1.44 when unspecified so Testcontainers works against
    // Docker Engine 29+ on CI runners as well as Podman 4/5/6+.
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.44");
    }

    String userHome = System.getProperty("user.home", "");
    String[] possiblePodmanSockets = {
      "/run/podman/podman.sock",
      userHome + "/.local/share/containers/podman/machine/podman-machine-default/podman.sock",
      userHome + "/.local/share/containers/podman/machine/qemu/podman.sock",
      System.getProperty("java.io.tmpdir", "/tmp") + "/podman/podman-machine-default-api.sock"
    };

    // Detect Podman socket before calling DockerClientFactory so we don't cache a client failure
    // when /var/run/docker.sock is absent.
    if (System.getProperty("DOCKER_HOST") == null
        && System.getenv("DOCKER_HOST") == null
        && !new File("/var/run/docker.sock").exists()) {
      for (String socketPath : possiblePodmanSockets) {
        if (new File(socketPath).exists()) {
          System.setProperty("DOCKER_HOST", "unix://" + socketPath);
          System.setProperty("TESTCONTAINERS_RYUK_DISABLED", "true");
          break;
        }
      }
    }

    Throwable lastFailure = null;
    try {
      if (DockerClientFactory.instance().isDockerAvailable()) {
        return true;
      }
    } catch (Throwable t) {
      lastFailure = t;
    }

    try {
      Process process = new ProcessBuilder("podman", "info").start();
      if (process.waitFor() == 0) {
        System.setProperty("TESTCONTAINERS_RYUK_DISABLED", "true");
        if (DockerClientFactory.instance().isDockerAvailable()) {
          return true;
        }
      }
    } catch (Throwable t) {
      if (lastFailure == null) {
        lastFailure = t;
      }
    }

    // Fail loudly on Linux CI runners instead of silently skipping containerized integration tests
    // if the runner's Docker configuration changes in the future.
    if (isLinuxCiEnvironment()) {
      throw new IllegalStateException(
          "Container runtime (Docker/Podman) is required on Linux CI runners but was not"
              + " available.",
          lastFailure);
    }

    return false;
  }

  private static boolean isLinuxCiEnvironment() {
    boolean isCi =
        Boolean.parseBoolean(System.getenv("CI"))
            || Boolean.parseBoolean(System.getenv("GITHUB_ACTIONS"));
    boolean isLinux = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    return isCi && isLinux;
  }

  /**
   * Create and configure a GenericContainer running RustFS for S3 integration testing.
   *
   * @return A configured GenericContainer instance (not started yet)
   */
  public static GenericContainer<?> createRustFsContainer() {
    return new GenericContainer<>("rustfs/rustfs:latest")
        .withExposedPorts(9000)
        .withEnv("RUSTFS_ACCESS_KEY", "rustfsadmin")
        .withEnv("RUSTFS_SECRET_KEY", "rustfsadmin");
  }

  /**
   * Returns the S3 endpoint URL for the active S3 Testcontainer (RustFS).
   *
   * @param s3Container The active S3 Testcontainer (RustFS)
   * @return S3 endpoint URL
   */
  public static String getS3Endpoint(GenericContainer<?> s3Container) {
    return "http://" + s3Container.getHost() + ":" + s3Container.getMappedPort(9000);
  }

  /**
   * Create a {@link MinioClient} configured to connect to the given S3/RustFS container.
   *
   * @param s3Container The active S3 Testcontainer (RustFS)
   * @return Pre-configured MinioClient
   */
  public static MinioClient createMinioClient(GenericContainer<?> s3Container) {
    String s3Url = getS3Endpoint(s3Container);
    return MinioClient.builder().endpoint(s3Url).credentials("rustfsadmin", "rustfsadmin").build();
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

  /**
   * Create and configure a GenericContainer running Azurite for Azure integration testing.
   *
   * @return A configured GenericContainer instance (not started yet)
   */
  public static GenericContainer<?> createAzuriteContainer() {
    return new GenericContainer<>("mcr.microsoft.com/azure-storage/azurite:3.36.0")
        .withExposedPorts(10000)
        .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--skipApiVersionCheck");
  }

  /**
   * Create a {@link com.azure.storage.blob.BlobContainerClient} connected to Azurite Testcontainer.
   *
   * @param azurite The active Azurite Testcontainer
   * @param containerName Target container name
   * @return Pre-configured BlobContainerClient
   */
  public static com.azure.storage.blob.BlobContainerClient createBlobContainerClient(
      GenericContainer<?> azurite, String containerName) {
    String connectionString =
        String.format(
            "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/"
                + "K1SZFPTOtr/KBHBeksoGMGw==;"
                + "BlobEndpoint=http://%s:%d/devstoreaccount1",
            azurite.getHost(), azurite.getMappedPort(10000));

    com.azure.storage.blob.BlobServiceClient serviceClient =
        new com.azure.storage.blob.BlobServiceClientBuilder()
            .connectionString(connectionString)
            .buildClient();

    com.azure.storage.blob.BlobContainerClient containerClient =
        serviceClient.getBlobContainerClient(containerName);

    if (!containerClient.exists()) {
      containerClient.create();
    }

    return containerClient;
  }
}
