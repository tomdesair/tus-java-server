package me.desair.tus.server.upload.azure;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Lightweight in-memory {@link HttpResponse} stub for simulating specific Azure HTTP error status
 * codes and headers in tests without anonymous boilerplate.
 */
public class InMemoryAzureHttpResponse extends HttpResponse {

  private final int statusCode;
  private final HttpHeaders headers;

  /**
   * Create a response stub with the given HTTP status code and empty headers.
   *
   * @param statusCode HTTP status code to return
   */
  public InMemoryAzureHttpResponse(int statusCode) {
    this(statusCode, new HttpHeaders());
  }

  /**
   * Create a response stub with the given HTTP status code and headers.
   *
   * @param statusCode HTTP status code to return
   * @param headers HTTP headers to return (defaults to empty headers if {@code null})
   */
  public InMemoryAzureHttpResponse(int statusCode, HttpHeaders headers) {
    super(null);
    this.statusCode = statusCode;
    this.headers = headers != null ? headers : new HttpHeaders();
  }

  @Override
  public int getStatusCode() {
    return statusCode;
  }

  @Override
  public String getHeaderValue(String name) {
    return headers.getValue(name);
  }

  @Override
  public HttpHeaders getHeaders() {
    return headers;
  }

  @Override
  public Flux<ByteBuffer> getBody() {
    return Flux.empty();
  }

  @Override
  public Mono<byte[]> getBodyAsByteArray() {
    return Mono.just(new byte[0]);
  }

  @Override
  public Mono<String> getBodyAsString() {
    return Mono.just("");
  }

  @Override
  public Mono<String> getBodyAsString(Charset charset) {
    return Mono.just("");
  }
}
