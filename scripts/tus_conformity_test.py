#!/usr/bin/env python3
"""
Tus v1.0.0 Resumable Upload Protocol Conformity Test Suite

This conformity test suite validates a server implementation against the
official Tus v1.0.0 Resumable Upload Protocol specification:
  https://tus.io/protocols/resumable-upload

Usage:
    pytest scripts/tus_conformity_test.py --url http://localhost:8080/test/api/upload
    python3 scripts/tus_conformity_test.py --url http://localhost:8080/test/api/upload
"""

import argparse
import base64
import email.utils
import hashlib
import os
import socket
import sys
import threading
import time
from urllib.parse import urlparse

import pytest

# Protocol Constants
TUS_RESUMABLE = "Tus-Resumable"
TUS_VERSION = "Tus-Version"
TUS_EXTENSION = "Tus-Extension"
TUS_MAX_SIZE = "Tus-Max-Size"
TUS_CHECKSUM_ALGORITHM = "Tus-Checksum-Algorithm"
UPLOAD_OFFSET = "Upload-Offset"
UPLOAD_LENGTH = "Upload-Length"
UPLOAD_DEFER_LENGTH = "Upload-Defer-Length"
UPLOAD_METADATA = "Upload-Metadata"
UPLOAD_CHECKSUM = "Upload-Checksum"
UPLOAD_CONCAT = "Upload-Concat"
UPLOAD_EXPIRES = "Upload-Expires"
LOCATION = "Location"
CONTENT_TYPE = "Content-Type"
CONTENT_LENGTH = "Content-Length"
CACHE_CONTROL = "Cache-Control"
X_HTTP_METHOD_OVERRIDE = "X-HTTP-Method-Override"
APPLICATION_OFFSET_OCTET_STREAM = "application/offset+octet-stream"
TUS_API_VERSION = "1.0.0"


def pytest_addoption(parser):
    """Add command line options to pytest."""
    parser.addoption(
        "--url",
        action="store",
        default="http://localhost:8080/test/api/upload",
        help="Target Tus upload endpoint URL",
    )


@pytest.fixture(scope="session")
def target_url(request):
    """Fixture providing the target upload URL."""
    try:
        return request.config.getoption("--url")
    except (ValueError, AttributeError):
        return os.environ.get("TUS_URL", "http://localhost:8080/test/api/upload")


class CaseInsensitiveDict(dict):
    """A case-insensitive dictionary for HTTP headers."""

    def __init__(self, data=None, **kwargs):
        super().__init__()
        self._keys = {}
        if data:
            self.update(data)
        if kwargs:
            self.update(kwargs)

    def __setitem__(self, key, value):
        super().__setitem__(key.lower(), value)
        self._keys[key.lower()] = key

    def __getitem__(self, key):
        return super().__getitem__(key.lower())

    def __delitem__(self, key):
        super().__delitem__(key.lower())
        del self._keys[key.lower()]

    def __contains__(self, key):
        return super().__contains__(key.lower())

    def get(self, key, default=None):
        return super().get(key.lower(), default)

    def update(self, other=None, **kwargs):
        if hasattr(other, "items"):
            for k, v in other.items():
                self[k] = v
        elif other:
            for k, v in other:
                self[k] = v
        for k, v in kwargs.items():
            self[k] = v

    def items(self):
        return ((self._keys[k], v) for k, v in super().items())


def parse_headers(lines):
    """Parse HTTP header lines into a CaseInsensitiveDict."""
    res_headers = CaseInsensitiveDict()
    for line in lines:
        if ":" in line:
            k, v = line.split(":", 1)
            res_headers[k.strip()] = v.strip()
    return res_headers


def http_request(method, url, headers=None, body=None):
    """
    Socket-based HTTP client helper for raw HTTP/1.1 request execution.
    Returns (status_code, headers_dict, body_bytes).
    """
    if headers is None:
        headers = {}
    parsed = urlparse(url)
    host = parsed.hostname or "localhost"
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    path = parsed.path + ("?" + parsed.query if parsed.query else "")
    if not path:
        path = "/"

    s = socket.create_connection((host, port), timeout=10)
    try:
        req_headers = CaseInsensitiveDict(headers)
        if "Connection" not in req_headers:
            req_headers["Connection"] = "close"

        req_lines = [f"{method} {path} HTTP/1.1", f"Host: {host}:{port}"]
        for k, v in req_headers.items():
            req_lines.append(f"{k}: {v}")
        if body is not None and "Content-Length" not in req_headers:
            body_len = len(body) if isinstance(body, bytes) else len(body.encode("utf-8"))
            req_lines.append(f"Content-Length: {body_len}")
        elif body is None and method in ("POST", "PATCH", "PUT") and "Content-Length" not in req_headers:
            req_lines.append("Content-Length: 0")

        req_lines.append("")
        req_lines.append("")
        req_data = "\r\n".join(req_lines).encode("latin1")
        if body:
            req_data += body if isinstance(body, bytes) else body.encode("utf-8")

        s.sendall(req_data)

        # Read response data
        resp_bytes = b""
        while True:
            chunk = s.recv(4096)
            if not chunk:
                break
            resp_bytes += chunk

        raw_str = resp_bytes.decode("latin1", errors="replace")
        parts = raw_str.split("\r\n\r\n", 1)
        headers_part = parts[0]
        body_part = parts[1].encode("latin1") if len(parts) > 1 else b""

        lines = headers_part.split("\r\n")
        status_line = lines[0]
        status_code = int(status_line.split()[1]) if len(status_line.split()) > 1 else 0

        res_headers = parse_headers(lines[1:])
        return status_code, res_headers, body_part
    except (socket.timeout, ConnectionRefusedError, socket.error) as e:
        pytest.fail(f"HTTP request failed: {e}")
    finally:
        s.close()


def create_upload(
    target_url,
    upload_length="100",
    defer_length=False,
    metadata=None,
    concat=None,
    extra_headers=None,
    body=None,
    content_type=None,
):
    """
    Helper to create an upload resource via POST.
    Returns (absolute_location_url, response_headers).
    """
    headers = {TUS_RESUMABLE: TUS_API_VERSION}
    if defer_length:
        headers[UPLOAD_DEFER_LENGTH] = "1"
    elif upload_length is not None:
        headers[UPLOAD_LENGTH] = str(upload_length)

    if metadata:
        headers[UPLOAD_METADATA] = metadata
    if concat:
        headers[UPLOAD_CONCAT] = concat
    if content_type:
        headers[CONTENT_TYPE] = content_type
    if extra_headers:
        headers.update(extra_headers)

    status, resp_headers, _ = http_request("POST", target_url, headers=headers, body=body)
    assert status == 201, f"Expected 201 Created for upload creation, got {status}"

    loc = resp_headers.get(LOCATION)
    assert loc, "Location header MUST be returned upon 201 Created"
    if not loc.startswith("http"):
        parsed = urlparse(target_url)
        loc = f"{parsed.scheme}://{parsed.netloc}{loc}"
    return loc, resp_headers


class TestCoreProtocol:
    """Core Protocol compliance tests (§6)."""

    def test_options_server_configuration(self, target_url):
        """
        §6 OPTIONS: Gathering Server Configuration.
        Quote: "A successful response indicated by the 204 No Content or 200 OK status MUST contain
        the Tus-Version header. It MAY include the Tus-Extension and Tus-Max-Size headers."
        """
        status, resp_headers, _ = http_request("OPTIONS", target_url)
        assert status in (200, 204), f"OPTIONS request MUST return 200 or 204, got {status}"
        assert TUS_VERSION in resp_headers, "OPTIONS response MUST contain Tus-Version header"
        assert TUS_API_VERSION in resp_headers.get(TUS_VERSION, ""), "Tus-Version MUST include 1.0.0"
        assert TUS_EXTENSION in resp_headers, "OPTIONS response SHOULD include Tus-Extension header"

    def test_options_ignores_tus_resumable_header(self, target_url):
        """
        §6 OPTIONS: Server MUST Ignore Tus-Resumable Header on OPTIONS.
        Quote: "The Client SHOULD NOT include the Tus-Resumable header in the request and the
        Server MUST ignore the header."
        """
        headers = {TUS_RESUMABLE: "unsupported.version.9.9.9"}
        status, resp_headers, _ = http_request("OPTIONS", target_url, headers=headers)
        assert status in (200, 204), "OPTIONS MUST succeed and ignore Tus-Resumable header"
        assert status != 412, "Server MUST NOT respond with 412 on OPTIONS even with invalid Tus-Resumable"

    def test_unsupported_tus_version_returns_412(self, target_url):
        """
        §6 Tus-Resumable: Protocol Version Mismatch Returns 412 Precondition Failed.
        Quote: "If the version specified by the Client is not supported by the Server, it MUST
        respond with the 412 Precondition Failed status and MUST include the Tus-Version header
        into the response. In addition, the Server MUST NOT process the request."
        """
        headers = {
            TUS_RESUMABLE: "0.1.0",
            UPLOAD_LENGTH: "100",
        }
        status, resp_headers, _ = http_request("POST", target_url, headers=headers)
        assert status == 412, f"Unsupported version MUST be rejected with 412, got {status}"
        assert TUS_VERSION in resp_headers, "412 response MUST include Tus-Version header"

    def test_missing_tus_resumable_header_rejected(self, target_url):
        """
        §6 Tus-Resumable: Header Required in Requests.
        Quote: "The Tus-Resumable header MUST be included in every request and response except
        for OPTIONS requests."
        """
        headers = {UPLOAD_LENGTH: "100"}
        status, resp_headers, _ = http_request("POST", target_url, headers=headers)
        assert status in (400, 412), f"Missing Tus-Resumable header MUST be rejected, got {status}"

    def test_tus_resumable_present_in_responses(self, target_url):
        """
        §6 Tus-Resumable: Header Present in Every Response.
        Quote: "The Tus-Resumable header MUST be included in every request and response except
        for OPTIONS requests. The value MUST be the version of the protocol used by the Client or Server."
        """
        upload_url, resp_headers = create_upload(target_url, upload_length="100")
        assert resp_headers.get(TUS_RESUMABLE) == TUS_API_VERSION, "POST response MUST contain Tus-Resumable: 1.0.0"

        status, head_headers, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert head_headers.get(TUS_RESUMABLE) == TUS_API_VERSION, "HEAD response MUST contain Tus-Resumable: 1.0.0"

    def test_head_requires_upload_offset_even_if_zero(self, target_url):
        """
        §6 HEAD: Server MUST Always Include Upload-Offset.
        Quote: "The Server MUST always include the Upload-Offset header in the response for a HEAD
        request, even if the offset is 0, or the upload is already considered completed."
        """
        upload_url, _ = create_upload(target_url, upload_length="100")
        status, resp_headers, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert status in (200, 204), f"HEAD request MUST succeed with 200 or 204, got {status}"
        assert resp_headers.get(UPLOAD_OFFSET) == "0", "Upload-Offset MUST be present and 0 initially"

    def test_head_requires_upload_length_when_known(self, target_url):
        """
        §6 HEAD: Server MUST Include Upload-Length When Known.
        Quote: "If the size of the upload is known, the Server MUST include the Upload-Length
        header in the response."
        """
        upload_url, _ = create_upload(target_url, upload_length="256")
        status, resp_headers, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert status in (200, 204)
        assert resp_headers.get(UPLOAD_LENGTH) == "256", "HEAD response MUST include Upload-Length"

    def test_head_cache_control_no_store(self, target_url):
        """
        §6 HEAD: Cache Prevention via Cache-Control: no-store.
        Quote: "The Server MUST prevent the client and/or proxies from caching the response by
        adding the Cache-Control: no-store header to the response."
        """
        upload_url, _ = create_upload(target_url, upload_length="100")
        status, resp_headers, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert status in (200, 204)
        cache_ctrl = resp_headers.get(CACHE_CONTROL, "")
        assert "no-store" in cache_ctrl, f"HEAD response MUST include Cache-Control: no-store, got '{cache_ctrl}'"

    def test_head_non_existent_upload_resource(self, target_url):
        """
        §6 HEAD: Non-Existent Resource Returns 404, 410, or 403 Without Upload-Offset.
        Quote: "If the resource is not found, the Server SHOULD return either the 404 Not Found,
        410 Gone or 403 Forbidden status without the Upload-Offset header."
        """
        parsed = urlparse(target_url)
        non_existent_url = f"{parsed.scheme}://{parsed.netloc}{parsed.path}/definitely-nonexistent-id-99999"
        status, resp_headers, _ = http_request("HEAD", non_existent_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert status in (404, 410, 403), f"HEAD on non-existent resource SHOULD return 404, 410, or 403, got {status}"
        assert UPLOAD_OFFSET not in resp_headers, "Non-existent HEAD response MUST NOT include Upload-Offset"

    def test_patch_content_type_required(self, target_url):
        """
        §6 PATCH: Content-Type MUST Be application/offset+octet-stream.
        Quote: "All PATCH requests MUST use Content-Type: application/offset+octet-stream,
        otherwise the server SHOULD return a 415 Unsupported Media Type status."
        """
        upload_url, _ = create_upload(target_url, upload_length="100")
        headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_OFFSET: "0",
            CONTENT_TYPE: "text/plain",
        }
        status, _, _ = http_request("PATCH", upload_url, headers=headers, body=b"A" * 10)
        assert status in (400, 406, 415), f"PATCH with incorrect Content-Type MUST be rejected, got {status}"

    def test_patch_offset_mismatch_returns_409(self, target_url):
        """
        §6 PATCH: Mismatching Upload-Offset MUST Return 409 Conflict.
        Quote: "The Upload-Offset header’s value MUST be equal to the current offset of the resource...
        If the offsets do not match, the Server MUST respond with the 409 Conflict status without
        modifying the upload resource."
        """
        upload_url, _ = create_upload(target_url, upload_length="100")
        headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_OFFSET: "50",
            CONTENT_TYPE: APPLICATION_OFFSET_OCTET_STREAM,
        }
        status, _, _ = http_request("PATCH", upload_url, headers=headers, body=b"A" * 10)
        assert status == 409, f"PATCH with mismatching offset MUST return 409 Conflict, got {status}"

        # Verify upload resource was not modified
        _, head_headers, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert head_headers.get(UPLOAD_OFFSET) == "0", "Upload-Offset MUST NOT be modified after 409 Conflict"

    def test_patch_successful_append(self, target_url):
        """
        §6 PATCH: Successful Append Returns 204 No Content With New Upload-Offset.
        Quote: "The Server MUST acknowledge successful PATCH requests with the 204 No Content status.
        It MUST include the Upload-Offset header containing the new offset. The new offset MUST be
        the sum of the offset before the PATCH request and the number of bytes received and processed."
        """
        upload_url, _ = create_upload(target_url, upload_length="100")
        chunk = b"A" * 40
        headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_OFFSET: "0",
            CONTENT_TYPE: APPLICATION_OFFSET_OCTET_STREAM,
        }
        status, resp_headers, _ = http_request("PATCH", upload_url, headers=headers, body=chunk)
        assert status == 204, f"PATCH MUST return 204 No Content on success, got {status}"
        assert resp_headers.get(UPLOAD_OFFSET) == "40", "Upload-Offset in response MUST be updated to 40"

        # Verify via HEAD
        _, head_headers, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert head_headers.get(UPLOAD_OFFSET) == "40", "HEAD MUST report new offset of 40"

    def test_patch_non_existent_resource(self, target_url):
        """
        §6 PATCH: Non-Existent Resource Returns 404 Not Found.
        Quote: "If the server receives a PATCH request against a non-existent resource it SHOULD
        return a 404 Not Found status."
        """
        parsed = urlparse(target_url)
        non_existent_url = f"{parsed.scheme}://{parsed.netloc}{parsed.path}/definitely-nonexistent-id-99999"
        headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_OFFSET: "0",
            CONTENT_TYPE: APPLICATION_OFFSET_OCTET_STREAM,
        }
        status, _, _ = http_request("PATCH", non_existent_url, headers=headers, body=b"A" * 10)
        assert status in (404, 410), f"PATCH against non-existent upload SHOULD return 404, got {status}"

    def test_patch_exceeding_upload_length_rejected(self, target_url):
        """
        §6 PATCH: Appending Beyond Declared Upload-Length Must Be Rejected.
        """
        upload_url, _ = create_upload(target_url, upload_length="30")
        headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_OFFSET: "0",
            CONTENT_TYPE: APPLICATION_OFFSET_OCTET_STREAM,
        }
        status, _, _ = http_request("PATCH", upload_url, headers=headers, body=b"A" * 50)
        assert status in (400, 409, 413), f"PATCH exceeding Upload-Length MUST be rejected, got {status}"

    def test_x_http_method_override(self, target_url):
        """
        §6 X-HTTP-Method-Override: Request Method Override.
        Quote: "The X-HTTP-Method-Override request header MUST be a string which MUST be interpreted
        as the request’s method by the Server, if the header is presented. The actual method of the
        request MUST be ignored."
        """
        upload_url, _ = create_upload(target_url, upload_length="100")

        # Test overriding POST to PATCH
        headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            X_HTTP_METHOD_OVERRIDE: "PATCH",
            UPLOAD_OFFSET: "0",
            CONTENT_TYPE: APPLICATION_OFFSET_OCTET_STREAM,
        }
        status, resp_headers, _ = http_request("POST", upload_url, headers=headers, body=b"A" * 40)
        assert status == 204, f"Overridden PATCH request MUST succeed with 204, got {status}"
        assert resp_headers.get(UPLOAD_OFFSET) == "40"

        # Test overriding POST to HEAD
        headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            X_HTTP_METHOD_OVERRIDE: "HEAD",
        }
        status, head_headers, _ = http_request("POST", upload_url, headers=headers)
        assert status in (200, 204), f"Overridden HEAD request MUST succeed with 200 or 204, got {status}"
        assert head_headers.get(UPLOAD_OFFSET) == "40"


class TestCreationExtension:
    """Creation Extension compliance tests (§7.1)."""

    def test_creation_extension_advertised(self, target_url):
        """
        §7.1 Creation: Extension Advertisement in OPTIONS.
        Quote: "If the Server supports this extension, it MUST add creation to the Tus-Extension header."
        """
        status, resp_headers, _ = http_request("OPTIONS", target_url)
        assert status in (200, 204)
        extensions = [e.strip() for e in resp_headers.get(TUS_EXTENSION, "").split(",")]
        assert "creation" in extensions, "Tus-Extension header MUST include 'creation'"

    def test_create_upload_empty_post(self, target_url):
        """
        §7.1 Creation: Empty POST Creation Request.
        Quote: "An empty POST request is used to create a new upload resource. The Upload-Length
        header indicates the size of the entire upload in bytes... The Server MUST acknowledge a
        successful upload creation with the 201 Created status. The Server MUST set the Location
        header to the URL of the created resource."
        """
        upload_url, resp_headers = create_upload(target_url, upload_length="100")
        assert upload_url, "Location header pointing to created resource MUST be returned"
        assert resp_headers.get(TUS_RESUMABLE) == TUS_API_VERSION

    def test_create_zero_byte_upload(self, target_url):
        """
        §7.1 Creation: Zero-Byte Upload Creation.
        Quote: "The Upload-Length header MAY be set to 0, indicating that the Client wants to
        upload an empty file. Such an upload is immediately complete after its creation without
        transferring data using PATCH requests."
        """
        upload_url, _ = create_upload(target_url, upload_length="0")
        status, head_headers, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert status in (200, 204)
        assert head_headers.get(UPLOAD_OFFSET) == "0", "Upload-Offset MUST be 0 for zero-byte upload"
        assert head_headers.get(UPLOAD_LENGTH) == "0", "Upload-Length MUST be 0 for zero-byte upload"

    def test_create_upload_defer_length(self, target_url):
        """
        §7.1 Creation: Deferred Length Creation.
        Quote: "Upload-Defer-Length: 1 if upload size is not known at the time... As long as the
        length of the upload is not known, the Server MUST set Upload-Defer-Length: 1 in all
        responses to HEAD requests. If the length was deferred using Upload-Defer-Length: 1, the
        Client MUST set the Upload-Length header in the next PATCH request, once the length is known."
        """
        upload_url, _ = create_upload(target_url, defer_length=True)

        # Initial HEAD should return Upload-Defer-Length: 1 and no Upload-Length
        status, head_headers, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert status in (200, 204)
        assert head_headers.get(UPLOAD_DEFER_LENGTH) == "1", "HEAD MUST include Upload-Defer-Length: 1 when length deferred"
        assert UPLOAD_LENGTH not in head_headers, "Upload-Length MUST NOT be present while deferred"

        # PATCH providing length
        headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_OFFSET: "0",
            UPLOAD_LENGTH: "80",
            CONTENT_TYPE: APPLICATION_OFFSET_OCTET_STREAM,
        }
        status, _, _ = http_request("PATCH", upload_url, headers=headers, body=b"B" * 40)
        assert status == 204, f"PATCH providing deferred Upload-Length MUST succeed with 204, got {status}"

        # Subsequent HEAD should now report Upload-Length: 80 and no Upload-Defer-Length
        status, head_headers2, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert status in (200, 204)
        assert head_headers2.get(UPLOAD_LENGTH) == "80", "HEAD MUST now report Upload-Length: 80"
        assert UPLOAD_DEFER_LENGTH not in head_headers2, "Upload-Defer-Length MUST be omitted once length is known"

    def test_create_upload_invalid_defer_length_value(self, target_url):
        """
        §7.1 Creation: Invalid Upload-Defer-Length Value Returns 400 Bad Request.
        Quote: "If the Upload-Defer-Length header contains any other value than 1 the server
        should return a 400 Bad Request status."
        """
        headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_DEFER_LENGTH: "2",
        }
        status, _, _ = http_request("POST", target_url, headers=headers)
        assert status == 400, f"Upload-Defer-Length != 1 MUST return 400 Bad Request, got {status}"

    def test_create_upload_missing_both_length_and_defer_length(self, target_url):
        """
        §7.1 Creation: POST Missing Both Upload-Length and Upload-Defer-Length.
        Quote: "The request MUST include one of the following headers: a) Upload-Length ...
        b) Upload-Defer-Length: 1"
        """
        headers = {TUS_RESUMABLE: TUS_API_VERSION}
        status, _, _ = http_request("POST", target_url, headers=headers)
        assert status == 400, f"POST without length headers MUST return 400 Bad Request, got {status}"

    def test_create_upload_negative_length(self, target_url):
        """
        §6 Upload-Length: Value MUST Be Non-Negative Integer.
        Quote: "The Upload-Length request and response header indicates the size of the entire
        upload in bytes. The value MUST be a non-negative integer."
        """
        headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_LENGTH: "-10",
        }
        status, _, _ = http_request("POST", target_url, headers=headers)
        assert status == 400, f"Negative Upload-Length MUST return 400 Bad Request, got {status}"

    def test_create_upload_non_integer_length(self, target_url):
        """
        §6 Upload-Length: Non-Integer Value Rejected.
        """
        headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_LENGTH: "not_a_number",
        }
        status, _, _ = http_request("POST", target_url, headers=headers)
        assert status == 400, f"Non-integer Upload-Length MUST return 400 Bad Request, got {status}"

    def test_create_upload_with_metadata(self, target_url):
        """
        §7.1 Creation: Upload-Metadata Header Preservation.
        Quote: "The Client MAY supply the Upload-Metadata header to add additional metadata to the
        upload creation request... If an upload contains additional metadata, responses to HEAD
        requests MUST include the Upload-Metadata header and its value as specified by the Client."
        """
        meta_filename = base64.b64encode(b"report.pdf").decode("ascii")
        meta_author = base64.b64encode(b"Jane Doe").decode("ascii")
        metadata = f"filename {meta_filename},author {meta_author}"

        upload_url, _ = create_upload(target_url, upload_length="100", metadata=metadata)
        status, head_headers, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert status in (200, 204)
        resp_meta = head_headers.get(UPLOAD_METADATA, "")
        assert f"filename {meta_filename}" in resp_meta, "Upload-Metadata in HEAD MUST contain filename"
        assert f"author {meta_author}" in resp_meta, "Upload-Metadata in HEAD MUST contain author"

    def test_create_upload_metadata_empty_value(self, target_url):
        """
        §7.1 Creation: Upload-Metadata with Empty Value.
        Quote: "The value MAY be empty. In these cases, the space, which would normally separate
        the key and the value, MAY be left out."
        """
        metadata = "is_confidential"
        upload_url, _ = create_upload(target_url, upload_length="100", metadata=metadata)
        status, head_headers, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert status in (200, 204)
        assert "is_confidential" in head_headers.get(UPLOAD_METADATA, ""), "Upload-Metadata in HEAD MUST contain empty-value key"

    def test_create_upload_exceeding_tus_max_size(self, target_url):
        """
        §7.1 Creation: Upload Exceeding Tus-Max-Size Returns 413 Request Entity Too Large.
        Quote: "If the length of the upload exceeds the maximum, which MAY be specified using
        the Tus-Max-Size header, the Server MUST respond with the 413 Request Entity Too Large status."
        """
        status, resp_headers, _ = http_request("OPTIONS", target_url)
        max_size_str = resp_headers.get(TUS_MAX_SIZE)
        if not max_size_str:
            pytest.skip("Server does not advertise Tus-Max-Size in OPTIONS response")

        max_size = int(max_size_str)
        headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_LENGTH: str(max_size + 1),
        }
        status, _, _ = http_request("POST", target_url, headers=headers)
        assert status == 413, f"Upload exceeding Tus-Max-Size ({max_size}) MUST return 413, got {status}"

    def test_deferred_length_cannot_be_changed_once_set(self, target_url):
        """
        §7.1 Creation: Deferred Length Cannot Be Changed Once Set.
        Quote: "Once set the length MUST NOT be changed."
        """
        upload_url, _ = create_upload(target_url, defer_length=True)

        # Set length to 100
        headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_OFFSET: "0",
            UPLOAD_LENGTH: "100",
            CONTENT_TYPE: APPLICATION_OFFSET_OCTET_STREAM,
        }
        status, _, _ = http_request("PATCH", upload_url, headers=headers, body=b"A" * 40)
        assert status == 204

        # Attempt to change length to 120 in subsequent PATCH
        headers2 = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_OFFSET: "40",
            UPLOAD_LENGTH: "120",
            CONTENT_TYPE: APPLICATION_OFFSET_OCTET_STREAM,
        }
        status2, _, _ = http_request("PATCH", upload_url, headers=headers2, body=b"B" * 40)
        assert status2 in (400, 409), f"Attempting to modify already set Upload-Length MUST be rejected, got {status2}"


class TestCreationWithUploadExtension:
    """Creation With Upload Extension compliance tests (§7.2)."""

    def test_creation_with_upload_advertised(self, target_url):
        """
        §7.2 Creation With Upload: Extension Advertisement.
        Quote: "If the Server supports this extension, it MUST advertise this by including
        creation-with-upload in the Tus-Extension header."
        """
        status, resp_headers, _ = http_request("OPTIONS", target_url)
        assert status in (200, 204)
        extensions = [e.strip() for e in resp_headers.get(TUS_EXTENSION, "").split(",")]
        assert "creation-with-upload" in extensions, "Tus-Extension MUST include 'creation-with-upload'"

    def test_creation_with_full_upload(self, target_url):
        """
        §7.2 Creation With Upload: Initial POST Containing Entire File Payload.
        Quote: "The Client MAY include either the entirety or a chunk of the upload data in the
        body of the POST request... The Server SHOULD accept as many bytes as possible and MUST
        include the Upload-Offset header in the response and MUST set its value to the offset of
        the upload after applying the accepted bytes."
        """
        payload = b"Hello, Full Tus Creation With Upload!"
        upload_url, resp_headers = create_upload(
            target_url,
            upload_length=str(len(payload)),
            content_type=APPLICATION_OFFSET_OCTET_STREAM,
            body=payload,
        )
        assert resp_headers.get(UPLOAD_OFFSET) == str(len(payload)), "Response MUST return Upload-Offset equal to body length"

        # Verify via HEAD
        status, head_headers, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert status in (200, 204)
        assert head_headers.get(UPLOAD_OFFSET) == str(len(payload))
        assert head_headers.get(UPLOAD_LENGTH) == str(len(payload))

    def test_creation_with_partial_upload_then_patch(self, target_url):
        """
        §7.2 Creation With Upload: Initial POST With Partial Payload Followed by PATCH.
        Quote: "The Server SHOULD accept as many bytes as possible and MUST include the
        Upload-Offset header in the response... The Client MUST perform the actual upload
        using the core protocol."
        """
        part1 = b"Part 1 Data with at least 32 bytes!!"
        part2 = b"Part 2 Data with at least 32 bytes!!"
        total_length = len(part1) + len(part2)

        upload_url, resp_headers = create_upload(
            target_url,
            upload_length=str(total_length),
            content_type=APPLICATION_OFFSET_OCTET_STREAM,
            body=part1,
        )
        assert resp_headers.get(UPLOAD_OFFSET) == str(len(part1))

        # Complete upload using PATCH
        headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_OFFSET: str(len(part1)),
            CONTENT_TYPE: APPLICATION_OFFSET_OCTET_STREAM,
        }
        status, patch_headers, _ = http_request("PATCH", upload_url, headers=headers, body=part2)
        assert status == 204
        assert patch_headers.get(UPLOAD_OFFSET) == str(total_length)

        # Final verification via HEAD
        _, head_headers, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert head_headers.get(UPLOAD_OFFSET) == str(total_length)

    def test_creation_with_upload_and_deferred_length(self, target_url):
        """
        §7.2 Creation With Upload: Creation With Upload Combined With Deferred Length.
        """
        payload = b"Initial chunk with deferred length"
        upload_url, resp_headers = create_upload(
            target_url,
            defer_length=True,
            content_type=APPLICATION_OFFSET_OCTET_STREAM,
            body=payload,
        )
        assert resp_headers.get(UPLOAD_OFFSET) == str(len(payload))

        # HEAD verification
        _, head_headers, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert head_headers.get(UPLOAD_OFFSET) == str(len(payload))
        assert head_headers.get(UPLOAD_DEFER_LENGTH) == "1"

    def test_creation_with_upload_invalid_content_type(self, target_url):
        """
        §7.2 Creation With Upload: Non-Empty POST With Invalid Content-Type Must Be Rejected.
        Quote: "The Client MUST include the Content-Type: application/offset+octet-stream header."
        """
        headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_LENGTH: "30",
            CONTENT_TYPE: "text/plain",
        }
        status, _, _ = http_request("POST", target_url, headers=headers, body=b"A" * 20)
        assert status in (400, 406, 415), f"Non-empty POST with invalid Content-Type MUST be rejected, got {status}"

    def test_creation_with_upload_exceeds_upload_length(self, target_url):
        """
        §7.2 Creation With Upload: Body Exceeding Declared Upload-Length Must Be Rejected.
        """
        headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_LENGTH: "10",
            CONTENT_TYPE: APPLICATION_OFFSET_OCTET_STREAM,
        }
        status, _, _ = http_request("POST", target_url, headers=headers, body=b"A" * 20)
        assert status in (400, 413), f"Body exceeding declared length MUST be rejected, got {status}"


class TestChecksumExtension:
    """Checksum Extension compliance tests (§7.4)."""

    def test_checksum_extension_advertised(self, target_url):
        """
        §7.4 Checksum: Extension and Algorithms Advertisement.
        Quote: "If supported, the Server MUST add checksum to the Tus-Extension header.
        The Tus-Checksum-Algorithm header MUST be included in the response to an OPTIONS request.
        The Server MUST support at least the SHA1 checksum algorithm identified by sha1."
        """
        status, resp_headers, _ = http_request("OPTIONS", target_url)
        assert status in (200, 204)
        extensions = [e.strip() for e in resp_headers.get(TUS_EXTENSION, "").split(",")]
        assert "checksum" in extensions, "Tus-Extension header MUST include 'checksum'"
        algorithms = [a.strip() for a in resp_headers.get(TUS_CHECKSUM_ALGORITHM, "").split(",")]
        assert "sha1" in algorithms, "Tus-Checksum-Algorithm header MUST support 'sha1'"

    def test_patch_with_valid_sha1_checksum(self, target_url):
        """
        §7.4 Checksum: Valid SHA1 Checksum Verification.
        Quote: "A Client MAY include the Upload-Checksum header in a PATCH request. Once the entire
        request has been received, the Server MUST verify the uploaded chunk against the provided
        checksum using the specified algorithm... 3. 204 No Content if the checksums match and
        the processing of the data succeeded."
        """
        upload_url, _ = create_upload(target_url, upload_length="100")
        payload = b"Tus SHA1 checksum verified payload"
        digest_bytes = hashlib.sha1(payload).digest()
        b64_digest = base64.b64encode(digest_bytes).decode("ascii")

        headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_OFFSET: "0",
            CONTENT_TYPE: APPLICATION_OFFSET_OCTET_STREAM,
            UPLOAD_CHECKSUM: f"sha1 {b64_digest}",
        }
        status, resp_headers, _ = http_request("PATCH", upload_url, headers=headers, body=payload)
        assert status == 204, f"PATCH with valid checksum MUST succeed with 204, got {status}"
        assert resp_headers.get(UPLOAD_OFFSET) == str(len(payload))

    def test_patch_with_invalid_checksum_mismatch(self, target_url):
        """
        §7.4 Checksum: Checksum Mismatch Returns 460 Checksum Mismatch.
        Quote: "2. 460 Checksum Mismatch if the checksums mismatch... In the first two cases the
        uploaded chunk MUST be discarded, and the upload and its offset MUST NOT be updated."
        """
        upload_url, _ = create_upload(target_url, upload_length="100")
        payload = b"Tus SHA1 payload with at least 32 bytes in length"
        invalid_digest = base64.b64encode(b"0" * 20).decode("ascii")

        headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_OFFSET: "0",
            CONTENT_TYPE: APPLICATION_OFFSET_OCTET_STREAM,
            UPLOAD_CHECKSUM: f"sha1 {invalid_digest}",
        }
        status, _, _ = http_request("PATCH", upload_url, headers=headers, body=payload)
        assert status == 460, f"Checksum mismatch MUST return 460 Checksum Mismatch, got {status}"

        # Verify chunk was discarded and offset remains 0
        _, head_headers, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert head_headers.get(UPLOAD_OFFSET) == "0", "Upload-Offset MUST NOT be updated after checksum mismatch"

    def test_patch_with_unsupported_checksum_algorithm(self, target_url):
        """
        §7.4 Checksum: Unsupported Checksum Algorithm Returns 400 Bad Request.
        Quote: "1. 400 Bad Request if the checksum algorithm is not supported by the server...
        the uploaded chunk MUST be discarded, and the upload and its offset MUST NOT be updated."
        """
        upload_url, _ = create_upload(target_url, upload_length="100")
        payload = b"Payload with unknown checksum algorithm at least 32 bytes"

        headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_OFFSET: "0",
            CONTENT_TYPE: APPLICATION_OFFSET_OCTET_STREAM,
            UPLOAD_CHECKSUM: "unknown_algo AAAA",
        }
        status, _, _ = http_request("PATCH", upload_url, headers=headers, body=payload)
        assert status == 400, f"Unsupported checksum algorithm MUST return 400 Bad Request, got {status}"

        # Verify chunk was discarded
        _, head_headers, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert head_headers.get(UPLOAD_OFFSET) == "0", "Upload-Offset MUST NOT be updated after invalid algorithm"


class TestTerminationExtension:
    """Termination Extension compliance tests (§7.5)."""

    def test_termination_extension_advertised(self, target_url):
        """
        §7.5 Termination: Extension Advertisement.
        Quote: "If this extension is supported by the Server, it MUST be announced by adding
        termination to the Tus-Extension header."
        """
        status, resp_headers, _ = http_request("OPTIONS", target_url)
        assert status in (200, 204)
        extensions = [e.strip() for e in resp_headers.get(TUS_EXTENSION, "").split(",")]
        assert "termination" in extensions, "Tus-Extension header MUST include 'termination'"

    def test_delete_existing_upload(self, target_url):
        """
        §7.5 Termination: Deletion of Existing Upload.
        Quote: "When receiving a DELETE request for an existing upload the Server SHOULD free
        associated resources and MUST respond with the 204 No Content status confirming that the
        upload was terminated. For all future requests to this URL, the Server SHOULD respond with
        the 404 Not Found or 410 Gone status."
        """
        upload_url, _ = create_upload(target_url, upload_length="100")

        # Terminate
        status, _, _ = http_request("DELETE", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert status == 204, f"DELETE on existing upload MUST return 204 No Content, got {status}"

        # Future HEAD request MUST return 404 or 410
        head_status, _, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert head_status in (404, 410), f"HEAD after DELETE MUST return 404 or 410, got {head_status}"

        # Future PATCH request MUST return 404 or 410
        patch_headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_OFFSET: "0",
            CONTENT_TYPE: APPLICATION_OFFSET_OCTET_STREAM,
        }
        patch_status, _, _ = http_request("PATCH", upload_url, headers=patch_headers, body=b"A" * 10)
        assert patch_status in (404, 410), f"PATCH after DELETE MUST return 404 or 410, got {patch_status}"

    def test_delete_non_existent_upload(self, target_url):
        """
        §7.5 Termination: DELETE on Non-Existent Resource Returns 404 Not Found.
        """
        parsed = urlparse(target_url)
        non_existent_url = f"{parsed.scheme}://{parsed.netloc}{parsed.path}/definitely-nonexistent-id-99999"
        status, _, _ = http_request("DELETE", non_existent_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert status in (404, 410), f"DELETE on non-existent upload SHOULD return 404, got {status}"

    def test_delete_completed_upload(self, target_url):
        """
        §7.5 Termination: Termination of Completed Upload.
        Quote: "This extension defines a way for the Client to terminate completed and unfinished
        uploads allowing the Server to free up used resources."
        """
        payload = b"Terminating completed upload test payload"
        upload_url, _ = create_upload(
            target_url,
            upload_length=str(len(payload)),
            content_type=APPLICATION_OFFSET_OCTET_STREAM,
            body=payload,
        )

        status, _, _ = http_request("DELETE", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert status == 204, f"DELETE on completed upload MUST return 204 No Content, got {status}"

        head_status, _, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert head_status in (404, 410), f"HEAD on deleted completed upload MUST return 404 or 410, got {head_status}"


class TestConcatenationExtension:
    """Concatenation Extension compliance tests (§7.6)."""

    def test_concatenation_extension_advertised(self, target_url):
        """
        §7.6 Concatenation: Extension Advertisement.
        Quote: "If the Server supports this extension, it MUST add concatenation to the Tus-Extension header."
        """
        status, resp_headers, _ = http_request("OPTIONS", target_url)
        assert status in (200, 204)
        extensions = [e.strip() for e in resp_headers.get(TUS_EXTENSION, "").split(",")]
        assert "concatenation" in extensions, "Tus-Extension header MUST include 'concatenation'"

    def test_partial_upload_creation_and_head(self, target_url):
        """
        §7.6 Concatenation: Partial Upload Creation and HEAD Response.
        Quote: "A partial upload represents a chunk of a file. It is constructed by including the
        Upload-Concat: partial header while creating a new upload using the Creation extension...
        The response to a HEAD request for a partial upload MUST contain the Upload-Offset header.
        Response to HEAD request against partial or final upload MUST include the Upload-Concat
        header and its value as received in the upload creation request."
        """
        upload_url, _ = create_upload(target_url, upload_length="40", concat="partial")
        status, head_headers, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert status in (200, 204)
        assert head_headers.get(UPLOAD_OFFSET) == "0"
        assert head_headers.get(UPLOAD_CONCAT) == "partial", "HEAD response MUST include Upload-Concat: partial"

    def test_final_upload_concatenation_success(self, target_url):
        """
        §7.6 Concatenation: Final Concatenation of Partial Uploads.
        Quote: "In order to create a new final upload, the Client MUST add the Upload-Concat header
        to the upload creation request. The value MUST be final followed by a semicolon and a
        space-separated list of the partial upload URLs that need to be concatenated... The length of
        the final upload MUST be the sum of the length of all partial uploads. After successful
        concatenation, the Upload-Offset and Upload-Length MUST be set and their values MUST be equal."
        """
        # Create and fill partial 1
        part1_data = b"Hello, Concatenated World Part 1!"
        part1_url, _ = create_upload(
            target_url,
            upload_length=str(len(part1_data)),
            concat="partial",
            content_type=APPLICATION_OFFSET_OCTET_STREAM,
            body=part1_data,
        )

        # Create and fill partial 2
        part2_data = b"Hello, Concatenated World Part 2!"
        part2_url, _ = create_upload(
            target_url,
            upload_length=str(len(part2_data)),
            concat="partial",
            content_type=APPLICATION_OFFSET_OCTET_STREAM,
            body=part2_data,
        )

        total_length = len(part1_data) + len(part2_data)

        # Create final concatenated upload
        concat_val = f"final;{part1_url} {part2_url}"
        final_url, _ = create_upload(target_url, upload_length=None, concat=concat_val)

        # Verify final upload HEAD
        status, head_headers, _ = http_request("HEAD", final_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert status in (200, 204)
        assert head_headers.get(UPLOAD_OFFSET) == str(total_length), "Upload-Offset MUST equal total combined length"
        assert head_headers.get(UPLOAD_LENGTH) == str(total_length), "Upload-Length MUST equal total combined length"
        assert head_headers.get(UPLOAD_CONCAT) == concat_val, "Upload-Concat MUST match final concatenation specification"

    def test_final_creation_must_not_include_upload_length(self, target_url):
        """
        §7.6 Concatenation: Final Upload Creation MUST NOT Include Upload-Length.
        Quote: "The Client MUST NOT include the Upload-Length header in the final upload creation."
        """
        # Create a partial upload
        part_url, _ = create_upload(target_url, upload_length="20", concat="partial")

        headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_CONCAT: f"final;{part_url}",
            UPLOAD_LENGTH: "20",
        }
        status, _, _ = http_request("POST", target_url, headers=headers)
        assert status == 400, f"Final creation including Upload-Length MUST be rejected with 400, got {status}"

    def test_patch_against_final_upload_forbidden(self, target_url):
        """
        §7.6 Concatenation: PATCH on Final Upload Returns 403 Forbidden.
        Quote: "The Server MUST respond with the 403 Forbidden status to PATCH requests against
        a final upload URL and MUST NOT modify the final or its partial uploads."
        """
        part_data = b"Fixed Part Data with at least 32 bytes!"
        part_url, _ = create_upload(
            target_url,
            upload_length=str(len(part_data)),
            concat="partial",
            content_type=APPLICATION_OFFSET_OCTET_STREAM,
            body=part_data,
        )
        final_url, _ = create_upload(target_url, upload_length=None, concat=f"final;{part_url}")

        # Retrieve current offset of final upload via HEAD
        _, head_headers, _ = http_request("HEAD", final_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        offset = head_headers.get(UPLOAD_OFFSET, str(len(part_data)))

        patch_headers = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_OFFSET: offset,
            CONTENT_TYPE: APPLICATION_OFFSET_OCTET_STREAM,
        }
        status, _, _ = http_request("PATCH", final_url, headers=patch_headers, body=b"")
        assert status == 403, f"PATCH against final upload MUST return 403 Forbidden, got {status}"


class TestExpirationExtension:
    """Expiration Extension compliance tests (§7.3)."""

    def test_expiration_extension_advertised(self, target_url):
        """
        §7.3 Expiration: Extension Advertisement.
        Quote: "In order to indicate this behavior to the Client, the Server MUST add expiration
        to the Tus-Extension header."
        """
        status, resp_headers, _ = http_request("OPTIONS", target_url)
        assert status in (200, 204)
        extensions = [e.strip() for e in resp_headers.get(TUS_EXTENSION, "").split(",")]
        assert "expiration" in extensions, "Tus-Extension header MUST include 'expiration'"

    def test_upload_expires_header_format(self, target_url):
        """
        §7.3 Expiration: Upload-Expires Header in Datetime Format.
        Quote: "The Upload-Expires response header indicates the time after which the unfinished
        upload expires... This header MUST be included in every PATCH response if the upload is
        going to expire. If the expiration is known at the creation, the Upload-Expires header MUST
        be included in the response to the initial POST request... The value of the Upload-Expires
        header MUST be in RFC 9110 datetime format."
        """
        upload_url, post_headers = create_upload(target_url, upload_length="100")
        expires_header = post_headers.get(UPLOAD_EXPIRES)

        if not expires_header:
            # Perform a PATCH to observe Upload-Expires on intermediate append
            patch_headers = {
                TUS_RESUMABLE: TUS_API_VERSION,
                UPLOAD_OFFSET: "0",
                CONTENT_TYPE: APPLICATION_OFFSET_OCTET_STREAM,
            }
            status, patch_resp_headers, _ = http_request("PATCH", upload_url, headers=patch_headers, body=b"A" * 40)
            assert status == 204
            expires_header = patch_resp_headers.get(UPLOAD_EXPIRES)

        if expires_header:
            dt = email.utils.parsedate_to_datetime(expires_header)
            assert dt is not None, f"Upload-Expires '{expires_header}' could not be parsed as valid RFC 9110 datetime"


class TestConcurrencyAndWorkflow:
    """Concurrency and end-to-end upload workflows."""

    def test_concurrent_patches_same_offset(self, target_url):
        """
        §6 PATCH: Concurrency Contention Prevention.
        Quote: "The Upload-Offset header’s value MUST be equal to the current offset of the resource.
        If the offsets do not match, the Server MUST respond with the 409 Conflict status without
        modifying the upload resource."
        """
        upload_url, _ = create_upload(target_url, upload_length="100")
        results = []

        def do_patch():
            headers = {
                TUS_RESUMABLE: TUS_API_VERSION,
                UPLOAD_OFFSET: "0",
                CONTENT_TYPE: APPLICATION_OFFSET_OCTET_STREAM,
            }
            st, _, _ = http_request("PATCH", upload_url, headers=headers, body=b"A" * 40)
            results.append(st)

        t1 = threading.Thread(target=do_patch)
        t2 = threading.Thread(target=do_patch)
        t1.start()
        t2.start()
        t1.join()
        t2.join()

        successes = [r for r in results if r == 204]
        assert len(successes) <= 1, f"At most one concurrent PATCH at offset 0 can succeed, got {results}"

    def test_multi_chunk_resumable_upload_workflow(self, target_url):
        """
        End-to-End multi-chunk resumable upload workflow.
        Create upload -> PATCH chunk 1 -> HEAD verify -> PATCH chunk 2 -> complete.
        """
        total_data = b"0123456789" * 12  # 120 bytes
        chunk1 = total_data[:40]
        chunk2 = total_data[40:80]
        chunk3 = total_data[80:]

        upload_url, _ = create_upload(target_url, upload_length=str(len(total_data)))

        # Chunk 1
        headers1 = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_OFFSET: "0",
            CONTENT_TYPE: APPLICATION_OFFSET_OCTET_STREAM,
        }
        st1, _, _ = http_request("PATCH", upload_url, headers=headers1, body=chunk1)
        assert st1 == 204

        # HEAD verification
        _, head1, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert head1.get(UPLOAD_OFFSET) == "40"

        # Chunk 2
        headers2 = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_OFFSET: "40",
            CONTENT_TYPE: APPLICATION_OFFSET_OCTET_STREAM,
        }
        st2, _, _ = http_request("PATCH", upload_url, headers=headers2, body=chunk2)
        assert st2 == 204

        # HEAD verification
        _, head2, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert head2.get(UPLOAD_OFFSET) == "80"

        # Chunk 3 (final)
        headers3 = {
            TUS_RESUMABLE: TUS_API_VERSION,
            UPLOAD_OFFSET: "80",
            CONTENT_TYPE: APPLICATION_OFFSET_OCTET_STREAM,
        }
        st3, _, _ = http_request("PATCH", upload_url, headers=headers3, body=chunk3)
        assert st3 == 204

        # Final verification
        _, head_final, _ = http_request("HEAD", upload_url, headers={TUS_RESUMABLE: TUS_API_VERSION})
        assert head_final.get(UPLOAD_OFFSET) == "120"
        assert head_final.get(UPLOAD_LENGTH) == "120"


# CLI Entry Point & Custom Formatted Summary Reporter
if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Tus v1.0.0 Conformity Test Runner")
    parser.add_argument(
        "--url",
        default="http://localhost:8080/test/api/upload",
        help="Target Tus upload endpoint URL",
    )
    args = parser.parse_args()

    os.environ["TUS_URL"] = args.url

    print("=" * 70)
    print("      Tus v1.0.0 Resumable Upload Protocol Conformity Test Suite")
    print("      Specification: https://tus.io/protocols/resumable-upload")
    print("      Target Endpoint:", args.url)
    print("=" * 70)

    class CustomReporter:
        def __init__(self):
            self.passed = []
            self.failed = []
            self.docs = {}

        @pytest.hookimpl(tryfirst=True, hookwrapper=True)
        def pytest_runtest_makereport(self, item, call):
            outcome = yield
            report = outcome.get_result()
            if report.when == "call":
                doc = item.obj.__doc__ or "No description provided."
                self.docs[report.nodeid] = doc.strip()
                if report.passed:
                    self.passed.append(report.nodeid)
                elif report.failed:
                    if hasattr(report.longrepr, "reprcrash"):
                        err_text = report.longrepr.reprcrash.message
                    elif hasattr(report, "longreprtext"):
                        err_lines = [
                            l.strip()
                            for l in report.longreprtext.splitlines()
                            if l.strip().startswith("E   ") or l.strip().startswith("AssertionError")
                        ]
                        err_text = "\n   ".join(err_lines) if err_lines else str(report.longrepr)
                    else:
                        err_text = str(report.longrepr)
                    self.failed.append((report.nodeid, err_text))

    reporter = CustomReporter()
    pytest.main([__file__, "-q", f"--url={args.url}"], plugins=[reporter])

    total_tests = len(reporter.passed) + len(reporter.failed)

    print("\n" + "=" * 70)
    print("                       CONFORMITY TEST RESULTS")
    print("=" * 70)
    print(f" Total Tests Executed: {total_tests}")
    print(f" Passed:               {len(reporter.passed)}")
    print(f" Failed:               {len(reporter.failed)}")
    print("=" * 70)

    if reporter.failed:
        print("\n[!] DETAILED FAILURE BREAKDOWN FOR REMEDIATION:")
        print("-" * 70)
        for idx, (test_id, err_text) in enumerate(reporter.failed, 1):
            print(f"\n{idx}. Test: {test_id}")
            func_name = test_id.split("::")[-1]
            print(f"   Function: {func_name}")
            print(f"   Specification Goal:\n     " + reporter.docs.get(test_id, "").replace("\n", "\n     "))
            print(f"   Failure Reason:\n     " + err_text.replace("\n", "\n     "))
            print("-" * 70)
    else:
        print("\n[✓] ALL TUS V1.0.0 CONFORMITY TESTS PASSED SUCCESSFULLY!")

    sys.exit(0 if not reporter.failed else 1)
