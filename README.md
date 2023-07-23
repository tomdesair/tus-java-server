[![Build and Tests](https://github.com/tomdesair/tus-java-server/actions/workflows/build.yml/badge.svg)](https://github.com/tomdesair/tus-java-server/actions?query=branch%3Amaster+) [![Coverage Status](https://coveralls.io/repos/github/tomdesair/tus-java-server/badge.svg?branch=master)](https://coveralls.io/github/tomdesair/tus-java-server?branch=master) [![Bugs](https://sonarcloud.io/api/project_badges/measure?project=me.desair.tus%3Atus-java-server&metric=bugs)](https://sonarcloud.io/dashboard?id=me.desair.tus%3Atus-java-server) [![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=me.desair.tus%3Atus-java-server&metric=vulnerabilities)](https://sonarcloud.io/dashboard?id=me.desair.tus%3Atus-java-server) [![Duplicated Lines](https://sonarcloud.io/api/project_badges/measure?project=me.desair.tus%3Atus-java-server&metric=duplicated_lines_density)](https://sonarcloud.io/dashboard?id=me.desair.tus%3Atus-java-server)

# tus-java-server
This library can be used to enable resumable (and potentially asynchronous) file uploads in any Java web application. This allows the users of your application to upload large files over slow and unreliable internet connections. The ability to pause or resume a file upload (after a connection loss or reset) is achieved by implementing the open file upload protocol tus (https://tus.io/). This library implements the server-side of the tus v1.0.0 protocol with [all optional extensions](#tus-protocol-extensions).

The Javadoc of this library can be found at https://tus.desair.me/. As of version 1.0.0-3.0, this library requires Java 17+. For older Java versions, please use one of the `1.0.0-2.x` releases.

## Quick Start and Examples
The tus-java-server library only depends on Jakarta Servlet API 6.0 and some Apache Commons utility libraries. This
means that (in theory) you can use this library on any modern Java Web Application server like Tomcat, JBoss, Jetty... By default all uploaded data and information is stored on the file system of the application server (and currently this is the only option, see [configuration section](#usage-and-configuration)).

You can add the latest stable version of this library to your application using Maven by adding the following dependency:

    <dependency>
      <groupId>me.desair.tus</groupId>
      <artifactId>tus-java-server</artifactId>
      <version>1.0.0-3.0-SNAPSHOT</version>
    </dependency>

The main entry point of the library is the `me.desair.tus.server.TusFileUploadService.process(jakarta.servlet.http.HttpServletRequest, jakarta.servlet.http.HttpServletResponse)` method. You can call this method inside a `jakarta.servlet.http.HttpServlet`, a `jakarta.servlet.Filter` or any REST API controller of a framework that gives you access to `HttpServletRequest` and `HttpServletResponse` objects. In the following list, you can find some example implementations:

* [Detailed blog post by Ralph](https://golb.hplar.ch/2019/06/upload-with-tus.html) on how to use this library in [Spring Boot in combination with the Tus JavaScript client](https://github.com/ralscha/blog2019/tree/master/uploadtus).
* [Resumable and asynchronous file upload using Uppy with form submission in Dropwizard (Jetty)](https://github.com/tomdesair/tus-java-server-dropwizard-demo)
* [Resumable and asynchronous file upload in Spring Boot REST API with Uppy JavaScript client.](https://github.com/tomdesair/tus-java-server-spring-demo)
* (more examples to come!)

## Tus Protocol Extensions
Besides the [core protocol](https://tus.io/protocols/resumable-upload.html#core-protocol), the library has all optional tus protocol extensions enabled by default. This means that the `Tus-Extension` header has value `creation,creation-defer-length,checksum,checksum-trailer,termination,expiration,concatenation,concatenation-unfinished`. Optionally you can also enable an unofficial `download` extension (see [configuration section](#usage-and-configuration)).

* [creation](https://tus.io/protocols/resumable-upload.html#creation): The creation extension allows you to create new uploads and to retrieve the upload URL for them.
* [creation-defer-length](https://tus.io/protocols/resumable-upload.html#post): You can create a new upload even if you don't know its final length at the time of creation.
* [checksum](https://tus.io/protocols/resumable-upload.html#checksum): An extension that allows you to verify data integrity of each upload (PATCH) request.
* [checksum-trailer](https://tus.io/protocols/resumable-upload.html#checksum): If the checksum hash cannot be calculated at the beginning of the upload, it may be included as a trailer HTTP header at the end of the chunked HTTP request.
* [termination](https://tus.io/protocols/resumable-upload.html#termination): Clients can terminate completed or in-progress uploads which allows the tus-java-server library to free up resources on the server.
* [expiration](https://tus.io/protocols/resumable-upload.html#expiration): You can instruct the tus-java-server library to cleanup uploads that are older than a configurable period.
* [concatenation](https://tus.io/protocols/resumable-upload.html#concatenation): This extension can be used to concatenate multiple uploads into a single final upload enabling clients to perform parallel uploads and to upload non-contiguous chunks.
* [concatenation-unfinished](https://tus.io/protocols/resumable-upload.html#concatenation): The client is allowed send the request to concatenate partial uploads while these partial uploads are still in progress.
* `download`: The (unofficial) download extension allows clients to download uploaded files using a HTTP `GET` request. You can enable this extension by calling the `withDownloadFeature()` method.

## Usage and Configuration

### 1. Setup
The first step is to create a `TusFileUploadService` object using its constructor. You can make this object available as a (Spring bean) singleton or create a new instance for each request. After creating the object, you can configure it using the following methods:

* `withUploadUri(String)`: Set the relative URL under which the main tus upload endpoint will be made available, for example `/files/upload`. Optionally, this URI may contain regex parameters in order to support endpoints that contain URL parameters, for example `/users/[0-9]+/files/upload`.
* `withMaxUploadSize(Long)`: Specify the maximum number of bytes that can be uploaded per upload. If you don't call this method, the maximum number of bytes is `Long.MAX_VALUE`.
* `withStoragePath(String)`: If you're using the default file system-based storage service, you can use this method to specify the path where to store the uploaded bytes and upload information.
* `withChunkedTransferDecoding`: You can enable or disable the decoding of chunked HTTP requests by this library. Enable this feature in case the web container in which this service is running does not decode chunked transfers itself. By default, chunked decoding via this library is disabled (as modern frameworks tend to already do this for you).
* `withThreadLocalCache(Boolean)`: Optionally you can enable (or disable) an in-memory (thread local) cache of upload request data to reduce load on the storage backend and potentially increase performance when processing upload requests.
* `withUploadExpirationPeriod(Long)`: You can set the number of milliseconds after which an upload is considered as expired and available for cleanup.
* `withDownloadFeature()`: Enable the unofficial `download` extension that also allows you to download uploaded bytes.
* `addTusExtension(TusExtension)`: Add a custom (application-specific) extension that implements the `me.desair.tus.server.TusExtension` interface. For example you can add your own extension that checks authentication and authorization policies within your application for the user doing the upload.
* `disableTusExtension(String)`: Disable the `TusExtension` for which the `getName()` method matches the provided string. The default extensions have names "creation", "checksum", "expiration", "concatenation", "termination" and "download". You cannot disable the "core" feature.
* `withUploadIdFactory(UploadIdFactory)`: Provide a custom `UploadIdFactory` implementation that should be used to generate identifiers for the different uploads. The default implementation generates identifiers using a UUID (`UuidUploadIdFactory`). Another example implementation of a custom ID factory is the system-time based `TimeBasedUploadIdFactory` class.


For now this library only provides filesystem based storage and locking options. You can however provide your own implementation of a `UploadStorageService` and `UploadLockingService` using the methods `withUploadStorageService(UploadStorageService)` and `withUploadLockingService(UploadLockingService)` in order to support different types of upload storage.

### 2. Processing an upload
To process an upload request you have to pass the current `jakarta.servlet.http.HttpServletRequest` and `jakarta.servlet.http.HttpServletResponse` objects to the `me.desair.tus.server.TusFileUploadService.process()` method. Typical places were you can do this are inside Servlets, Filters or REST API Controllers (see [examples](#quick-start-and-examples)).

Optionally you can also pass a `String ownerKey` parameter. The `ownerKey` can be used to have a hard separation between uploads of different users, groups or tenants in a multi-tenant setup. Examples of `ownerKey` values are user ID's, group names, client ID's...

### 3. Retrieving the uploaded bytes and metadata within the application
Once the upload has been completed by the user, the business logic layer of your application needs to retrieve and do something with the uploaded bytes. For example it could read the contents of the file, or move the uploaded bytes to their final persistent storage location. Retrieving the uploaded bytes in the backend can be achieved by using the `me.desair.tus.server.TusFileUploadService.getUploadedBytes(String uploadUrl)` method. The passed `uploadUrl` value should be the upload url used by the client to which the file was uploaded. Therefor your application should pass the upload URL of completed uploads to the backend. Optionally, you can also pass an `ownerKey` value to this method in case your application chooses to process uploads using owner keys. Examples of values that can be used as an `ownerKey` are: an internal user identifier, a session ID, the name of the subpart of your application...

Using the `me.desair.tus.server.TusFileUploadService.getUploadInfo(String uploadUrl)` method you can retrieve metadata about a specific upload process. This includes metadata provided by the client as well as metadata kept by the library like creation timestamp, creator ip-address list, upload length... The method `UploadInfo.getId()` will return the unique identifier of this upload encapsulated in an `UploadId` instance. The original (custom generated) identifier object of this upload can be retrieved using `UploadId.getOriginalObject()`. A URL safe string representation of the identifier is returned by `UploadId.toString()`. It is highly recommended to consult the [JavaDoc of both classes](https://tus.desair.me/).

### 4. Upload cleanup
After having processed the uploaded bytes on the server backend (e.g. copy them to their final persistent location), it's important to cleanup the (temporary) uploaded bytes. This can be done by calling the `me.desair.tus.server.TusFileUploadService.deleteUpload(String uploadUri)` method. This will remove the uploaded bytes and any associated upload information from the storage backend. Alternatively, a client can also remove an (in-progress) upload using the [termination extension](https://tus.io/protocols/resumable-upload.html#termination).

Next to removing uploads after they have been completed and processed by the backend, it is also recommended to schedule a regular maintenance task to clean up any expired uploads or locks. Cleaning up expired uploads and locks can be achieved using the `me.desair.tus.server.TusFileUploadService.cleanup()` method.

## Compatible Client Implementations
This tus protocol implementation has been [tested](https://github.com/tomdesair/tus-java-server-spring-demo) with the [Uppy file upload client](https://uppy.io/). This repository also contains [many automated integration tests](https://github.com/tomdesair/tus-java-server/blob/master/src/test/java/me/desair/tus/server/ITTusFileUploadService.java) that validate the tus protocol server implementation using plain HTTP requests. So in theory this means we're compatible with any tus 1.0.0 compliant client.

## Versioning
This artifact is versioned as `A.B.C-X.Y` where `A.B.C` is the version of the implemented tus protocol (currently 1.0.0) and `X.Y` is the version of this library.

## Contributing
This library comes without any warranty and is released under a [MIT license](https://github.com/tomdesair/tus-java-server/blob/master/LICENSE). If you encounter any bugs or if you have an idea for a useful improvement you are welcome to [open a new issue](https://github.com/tomdesair/tus-java-server/issues) or to [create a pull request](https://github.com/tomdesair/tus-java-server/pulls) with the proposed implementation. Please note that any contributed code needs to be accompanied by automated unit and/or integration tests and comply with the [defined code-style](#code-style).

### Code Style
All pull requests should have the correct formatting according to [Google Java Style](https://github.com/google/google-java-format) code formatting. To verify if the code style is correct run:

```
mvn -P codestyle com.spotify.fmt:fmt-maven-plugin:check
```

To reformat your code run:

```
mvn -P codestyle com.spotify.fmt:fmt-maven-plugin:format
```

See the [Google Java Style Github page](https://github.com/google/google-java-format) on recommendations on how to configure this in your IDE. Or if you have Python 3, you can also use [pre-commit](https://pre-commit.com) to make your live easier:

```
pip install pre-commit
pre-commit install
```
