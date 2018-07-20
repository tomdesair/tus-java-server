[![Build Status](https://travis-ci.org/tomdesair/tus-java-server.svg?branch=master)](https://travis-ci.org/tomdesair/tus-java-server) [![Test Coverage](https://sonarcloud.io/api/project_badges/measure?project=me.desair.tus%3Atus-java-server&metric=coverage)](https://sonarcloud.io/dashboard?id=me.desair.tus%3Atus-java-server) [![Bugs](https://sonarcloud.io/api/project_badges/measure?project=me.desair.tus%3Atus-java-server&metric=bugs)](https://sonarcloud.io/dashboard?id=me.desair.tus%3Atus-java-server) [![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=me.desair.tus%3Atus-java-server&metric=vulnerabilities)](https://sonarcloud.io/dashboard?id=me.desair.tus%3Atus-java-server) [![Duplicated Lines](https://sonarcloud.io/api/project_badges/measure?project=me.desair.tus%3Atus-java-server&metric=duplicated_lines_density)](https://sonarcloud.io/dashboard?id=me.desair.tus%3Atus-java-server)

# tus-java-server
This library can be used to enable resumable (and potentially asynchronous) file uploads in any Java web application. This allows the users of your application to upload large files over slow and unreliable internet connections. The ability to pause or resume a file upload (after a connection loss or reset) is achieved by implementing the open file upload protocol tus (https://tus.io/). This library implements the server-side of the tus v1.0.0 protocol with [all optional extensions](#tus-protocol-extensions).

## Quick Start and Examples
The tus-java-server library only depends on Java Servlet API 3.1 and some Apache Commons utility libraries. This means that (in theory) you can use this library on any modern Java Web Application server like Tomcat, JBoss, Jetty... By default all uploaded data and information is stored on the file system of the application server (and currently this is the only option, see [configuration section](#usage-and-configuration)).

You can add this library to your application using Maven by adding the following to the pom dependencies:

    <dependency>
      <groupId>me.desair.tus</groupId>
      <artifactId>tus-java-server</artifactId>
      <version>1.0.0-0.1-SNAPSHOT</version>
    </dependency>

The main entry point of the library is the `me.desair.tus.server.TusFileUploadService.process(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)` method. You can call this method inside a `javax.servlet.http.HttpServlet`, a `javax.servlet.Filter` or any REST API controller of a framework that gives you access to `HttpServletRequest` and `HttpServletResponse` objects. In the following list, you can find some example implementations:

* [Tus resumable file upload in Spring Boot REST API with Uppy JavaScript client.](https://github.com/tomdesair/tus-java-server-spring-demo)
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

* `withUploadURI(String)`: Set the relative URL under which the tus upload endpoint will be made available, for example `/files/upload`.
* `withMaxUploadSize(Long)`: Specify the maximum number of bytes that can be uploaded per upload. If you don't call this method, the maximum number of bytes is `Long.MAX_VALUE`.
* `withStoragePath(String)`: If you're using the default filesystem-based storage service, you can use this method to specify the path where to store the uploaded bytes and upload information.
* `withUploadExpirationPeriod(Long)`: You can set the number of milliseconds after which an upload is considered as expired and available for cleanup.
* `withDownloadFeature()`: Enable the unofficial `download` extension that also allows you to download uploaded bytes.
* `addTusExtension(TusExtension)`: Add a custom (application-specific) extension that implements the `me.desair.tus.server.TusExtension` interface. For example you can add your own extension that checks authentication and authorization policies within your application for the user doing the upload.
* `disableTusExtension(String)`: Disable the `TusExtension` for which the `getName()` method matches the provided string. The default extensions have names "creation", "checksum", "expiration", "concatenation", "termination" and "download". You cannot disable the "core" feature.


For now this library only provides filesystem-based storage and locking options. You can however provide your own implementation of a `UploadStorageService` and `UploadLockingService` using the methods `withUploadStorageService(UploadStorageService)` and `withUploadLockingService(UploadLockingService)` in order to support different types of upload storage.

### 2. Processing an upload
To process an upload request you have to pass the current `javax.servlet.http.HttpServletRequest` and `javax.servlet.http.HttpServletResponse` objects to the `me.desair.tus.server.TusFileUploadService.process()` method. Typical places were you can do this are inside Servlets, Filters or REST API Controllers (see [examples](#quick-start-and-examples)).

Optionally you can also pass a `String ownerKey` parameter. The `ownerKey` can be used to have a hard separation between uploads of different users, groups or tenants in a multi-tenant setup. Examples of `ownerKey` values are user ID's, group names, client ID's...

### 3. Retrieving the uploaded bytes and metadata within the application
Once the upload has been completed by the user, the business logic layer of your application needs to retrieve and do something with the uploaded bytes. This can be achieved by using the `me.desair.tus.server.TusFileUploadService.getUploadedBytes(String uploadURL)` method. The passed `uploadURL` value should be the upload url used by the user to which he uploaded his file. Therefor your application should pass the upload URL of completed uploads to the backend. Optionally, you can also pass an `ownerKey` value to this method in case your application chooses to process uploads using owner keys.

### 4. Upload cleanup
TODO


## Compatible Client Implementations
TODO

## Versioning
TODO

## Contributing
TODO
