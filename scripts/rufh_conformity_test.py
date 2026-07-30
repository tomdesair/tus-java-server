#!/usr/bin/env python3
"""
RUFH (Resumable Uploads for HTTP) Draft-12 & RFC 9530 Conformity Test Suite

This conformity test suite validates a server implementation against the
IETF Resumable Uploads for HTTP draft-12 specification:
  https://www.ietf.org/archive/id/draft-ietf-httpbis-resumable-upload-12.txt
and RFC 9530 HTTP Digests:
  https://www.rfc-editor.org/rfc/rfc9530.html

Usage:
    pytest scripts/rufh_conformity_test.py --url http://localhost:8080/test/api/upload
    python3 scripts/rufh_conformity_test.py --url http://localhost:8080/test/api/upload
"""

import argparse
import base64
import hashlib
import json
import os
import socket
import sys
import threading
import time
from urllib.parse import urlparse

import pytest

# Protocol Constants
TRUE = '?1'
FALSE = '?0'
UPLOAD_COMPLETE = 'Upload-Complete'
UPLOAD_OFFSET = 'Upload-Offset'
UPLOAD_LENGTH = 'Upload-Length'
UPLOAD_LIMIT = 'Upload-Limit'
LOCATION = 'Location'
CONTENT_TYPE = 'Content-Type'
APPLICATION_PARTIAL_UPLOAD = 'application/partial-upload'
APPLICATION_PROBLEM_JSON = 'application/problem+json'

# Global set to track tests where 104 interim responses were detected
INTERIM_RESPONSES_DETECTED = set()


def pytest_addoption(parser):
    """Add command line options to pytest."""
    parser.addoption(
        "--url",
        action="store",
        default="http://localhost:8080/test/api/upload",
        help="Target RUFH upload endpoint URL"
    )


@pytest.fixture(scope="session")
def target_url(request):
    """Fixture providing the target upload URL."""
    try:
        return request.config.getoption("--url")
    except (ValueError, AttributeError):
        return os.environ.get("RUFH_URL", "http://localhost:8080/test/api/upload")


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
    res_headers = CaseInsensitiveDict()
    for line in lines:
        if ":" in line:
            k, v = line.split(":", 1)
            res_headers[k.strip()] = v.strip()
    return res_headers


def http_request(method, url, headers=None, body=None, test_name=""):
    """
    Socket-based HTTP client helper that transparently handles HTTP 104 interim response frames.
    Tracks 104 interim response detection in INTERIM_RESPONSES_DETECTED.
    Returns (status_code, headers_dict, body_bytes, interim_104_headers_list).
    """
    if headers is None:
        headers = {}
    parsed = urlparse(url)
    host = parsed.hostname or "localhost"
    port = parsed.port or 80
    path = parsed.path + ("?" + parsed.query if parsed.query else "")

    s = socket.create_connection((host, port), timeout=5)
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
        parts = raw_str.split("\r\n\r\n")

        interim_104_headers = []
        final_headers_part = ""
        final_body_part = ""

        for idx, part in enumerate(parts):
            if part.startswith("HTTP/1."):
                lines = part.split("\r\n")
                status_line = lines[0]
                status_code = int(status_line.split()[1]) if len(status_line.split()) > 1 else 0
                if status_code == 104:
                    interim_104_headers.append(parse_headers(lines[1:]))
                else:
                    final_headers_part = part
                    final_body_part = "\r\n\r\n".join(parts[idx + 1 :])
                    break

        if not final_headers_part and parts:
            final_headers_part = parts[0]

        if interim_104_headers and test_name:
            INTERIM_RESPONSES_DETECTED.add(test_name)
            if "__main__" in sys.modules and hasattr(sys.modules["__main__"], "INTERIM_RESPONSES_DETECTED"):
                sys.modules["__main__"].INTERIM_RESPONSES_DETECTED.add(test_name)

        lines = final_headers_part.split("\r\n")
        status_line = lines[0]
        status_code = int(status_line.split()[1]) if len(status_line.split()) > 1 else 0

        res_headers = parse_headers(lines[1:])
        return status_code, res_headers, final_body_part.encode("latin1"), interim_104_headers
    except (socket.timeout, ConnectionRefusedError, socket.error) as e:
        pytest.fail(f"HTTP request failed: {e}")
    finally:
        s.close()


def create_partial_upload(target_url, test_name="", upload_length="100"):
    """Helper to create a partial upload returning absolute Location URI."""
    payload = b""
    headers = {
        UPLOAD_COMPLETE: FALSE,
    }
    if upload_length is not None:
        headers[UPLOAD_LENGTH] = str(upload_length)
    status, headers_dict, _, interim = http_request("POST", target_url, headers=headers, body=payload, test_name=test_name)
    assert status == 201, f"Expected 201 Created for upload creation, got {status}"

    # Validation for §5 (104 interim responses during creation MUST include Location)
    if interim:
        for i_headers in interim:
            assert LOCATION in i_headers, "104 interim response during creation MUST include Location"

    loc = headers_dict.get(LOCATION)
    assert loc, "Location header MUST be returned upon 201 Created"
    if not loc.startswith("http"):
        parsed = urlparse(target_url)
        loc = f"{parsed.scheme}://{parsed.netloc}{loc}"
    return loc


class TestUploadState:
    """Tests for Upload State Constraints (§4.1)."""

    def test_append_non_integer_upload_offset(self, target_url, request):
        """
        §4.1.1: Upload-Offset with non-integer value MUST be ignored.
        Quote: "If the Upload-Offset header field is present and its value is not a valid integer, the server MUST ignore it."
        Expected behavior: The server should reject the request since without a valid Upload-Offset, the append is invalid.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name)
        headers = {
            UPLOAD_OFFSET: "abc",
            UPLOAD_COMPLETE: FALSE,
            CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD,
        }
        status, _, _, _ = http_request("PATCH", upload_uri, headers=headers, body=b"A" * 32, test_name=request.node.name)
        assert status in (400, 409), f"Non-integer Upload-Offset must be rejected, got {status}"

    def test_offset_never_decreases(self, target_url, request):
        """
        §4.1.1: Offset MUST NOT decrease after data is processed.
        Quote: "If the server loses any part of the state, it MUST deactivate the upload resource and reject further interaction with it."
        Expected behavior: Decreasing offset must be rejected (e.g. 409). Furthermore, a subsequent HEAD should confirm the resource is deactivated (404/410).
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name)

        # Append some data
        http_request("PATCH", upload_uri,
            headers={UPLOAD_OFFSET: "0", UPLOAD_COMPLETE: FALSE, CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD},
            body=b"A" * 32, test_name=request.node.name)

        # Retrieve offset
        _, h_headers, _, _ = http_request("HEAD", upload_uri, test_name=request.node.name)
        assert int(h_headers.get(UPLOAD_OFFSET, "0")) >= 32

        # Attempt to append at a lower offset
        status, resp_headers, _, _ = http_request("PATCH", upload_uri,
            headers={UPLOAD_OFFSET: "5", UPLOAD_COMPLETE: FALSE, CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD},
            body=b"B" * 32, test_name=request.node.name)
        assert status == 409, f"Mismatching/stale offset MUST be rejected with 409 Conflict, got {status}"
        assert resp_headers.get(UPLOAD_OFFSET) == "32", "409 response MUST include current server Upload-Offset for client resumption"

        # Verify resource remains valid for resumption (Section 4.4.2)
        h_status, h_headers, _, _ = http_request("HEAD", upload_uri, test_name=request.node.name)
        assert h_status in (200, 204), "Upload resource MUST remain valid for client resumption after 409 offset mismatch"
        assert h_headers.get(UPLOAD_OFFSET) == "32", "HEAD response MUST return correct server offset"

    def test_creation_invalid_boolean_upload_complete(self, target_url, request):
        """
        §4.1.2: Upload-Complete with invalid Boolean value MUST be ignored.
        Quote: "Other values MUST cause the entire header field to be ignored."
        Expected behavior: The request becomes a regular POST if the header is ignored. It should process based on target resource's normal POST behavior.
        """
        headers = {UPLOAD_COMPLETE: "true", UPLOAD_LENGTH: "10"}
        status, _, _, _ = http_request("POST", target_url, headers=headers, body=b"Hello", test_name=request.node.name)
        assert status in (400, 404, 405, 200, 201), f"Expected rejection or normal non-resumable POST behavior, got {status}"

    def test_unknown_length_upload(self, target_url, request):
        """
        §4.1.3: Upload-Length without Upload-Complete (unknown length scenario).
        Quote: "If the request does not include the Upload-Length header field, the representation's length is unknown."
        Expected behavior: Upload-Length shouldn't be present in HEAD response. After complete append, it should be set.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name, upload_length=None)

        # Initial offset retrieval
        status, resp_headers, _, _ = http_request("HEAD", upload_uri, test_name=request.node.name)
        assert status in (204, 200)
        assert UPLOAD_LENGTH not in resp_headers, "Upload-Length should not be present when unknown"

        # Completing append
        status, _, _, _ = http_request("PATCH", upload_uri,
            headers={UPLOAD_OFFSET: "0", UPLOAD_COMPLETE: TRUE, CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD},
            body=b"A" * 32, test_name=request.node.name)
        assert status in (200, 204, 201)

        # Retrieve final offset and length
        status, resp_headers, _, _ = http_request("HEAD", upload_uri, test_name=request.node.name)
        assert resp_headers.get(UPLOAD_LENGTH) == "32", "Upload-Length must be known after completion"


class TestUploadCreation:
    """Tests for Upload Creation (Section 4.2)."""

    def test_creation_optimistic_complete_upload(self, target_url, request):
        """
        §4.2: Optimistic complete upload with Upload-Complete: ?1.
        Quote: "The server SHOULD NOT generate a response with the 301, 302, or 303 status codes..."
        Expected behavior: Should return 200/201 and Upload-Complete ?1. Must not redirect.
        """
        payload = b"Hello, RUFH World!"
        headers = {
            UPLOAD_COMPLETE: TRUE,
            UPLOAD_LENGTH: str(len(payload)),
            CONTENT_TYPE: "text/plain",
        }
        status, resp_headers, _, _ = http_request("POST", target_url, headers=headers, body=payload, test_name=request.node.name)
        assert status in (200, 201), f"Expected status 200 or 201, got {status}"
        assert resp_headers.get(UPLOAD_COMPLETE) == TRUE, f"Expected Upload-Complete: ?1"
        assert status not in (301, 302, 303), "Server SHOULD NOT generate 301/302/303 redirect"

    def test_creation_partial_upload(self, target_url, request):
        """
        §4.2: Upload creation with partial representation.
        Quote: "the server MUST include the Location response header field pointing to the upload resource and MUST include the Upload-Limit header field"
        Expected behavior: 201 Created with Location and Upload-Limit. Upload-Offset should be present.
        """
        payload = b"X" * 32
        headers = {
            UPLOAD_COMPLETE: FALSE,
            UPLOAD_LENGTH: "100",
            CONTENT_TYPE: "text/plain",
        }
        status, resp_headers, _, _ = http_request("POST", target_url, headers=headers, body=payload, test_name=request.node.name)
        assert status == 201, f"Expected 201 Created for partial upload creation, got {status}"
        assert resp_headers.get(LOCATION), "Response MUST include Location header"
        assert resp_headers.get(UPLOAD_COMPLETE) == FALSE, f"Expected Upload-Complete: ?0"
        assert status not in (301, 302, 303), "Server SHOULD NOT generate 301/302/303 redirect"
        assert resp_headers.get(UPLOAD_OFFSET), "Upload-Offset MUST be returned in partial creation response"
        assert UPLOAD_LIMIT in resp_headers, "Upload-Limit MUST be included in upload creation response"

    def test_creation_empty_upload(self, target_url, request):
        """
        §4.2.1: Upload creation with empty body and Upload-Complete: ?0.
        Quote: "the server MUST include the Location response header field pointing to the upload resource and MUST include the Upload-Limit header field"
        Expected behavior: 201 Created with Location. Upload-Limit MUST be present (Important Gap 2.6).
        """
        headers = {UPLOAD_COMPLETE: FALSE, UPLOAD_LENGTH: "100"}
        status, resp_headers, _, _ = http_request("POST", target_url, headers=headers, body=b"", test_name=request.node.name)
        assert status == 201
        assert resp_headers.get(LOCATION)
        assert resp_headers.get(UPLOAD_COMPLETE) == FALSE
        assert UPLOAD_LIMIT in resp_headers, "Upload-Limit MUST be included in empty creation response"

    def test_creation_empty_representation(self, target_url, request):
        """
        §4.2.1: Empty body with Upload-Complete: ?1 uploads empty representation.
        Quote: "A client MAY create a resumable upload resource without uploading any data..."
        Expected behavior: 200/201 with Upload-Complete ?1.
        """
        headers = {UPLOAD_COMPLETE: TRUE, UPLOAD_LENGTH: "0"}
        status, resp_headers, _, _ = http_request("POST", target_url, headers=headers, body=b"", test_name=request.node.name)
        assert status in (200, 201)
        assert resp_headers.get(UPLOAD_COMPLETE) == TRUE

    def test_creation_content_disposition(self, target_url, request):
        """
        §4.2.1: Content-Disposition header acceptance.
        Quote: "For this purpose, the inline disposition type is RECOMMENDED."
        Expected behavior: Content-Disposition: inline should be accepted.
        """
        headers = {UPLOAD_COMPLETE: FALSE, UPLOAD_LENGTH: "100", "Content-Disposition": 'inline; filename="test.txt"'}
        status, _, _, _ = http_request("POST", target_url, headers=headers, body=b"X" * 32, test_name=request.node.name)
        assert status == 201

    def test_creation_inconsistent_length(self, target_url, request):
        """
        §4.1.3 & 7.2: Inconsistent Upload-Length.
        Quote: "The server MUST reject a request if the representation's length is known and inconsistent..."
        Expected behavior: 400 error. Optionally check Upload-Offset if server responded gracefully.
        """
        headers = {UPLOAD_COMPLETE: TRUE, UPLOAD_LENGTH: "100"}
        status, resp_headers, body, _ = http_request("POST", target_url, headers=headers, body=b"12345", test_name=request.node.name)
        assert status == 400
        if resp_headers.get("Content-Type", "").startswith(APPLICATION_PROBLEM_JSON):
            prob = json.loads(body.decode("utf-8"))
            assert prob.get("type") == "https://iana.org/assignments/http-problem-types#inconsistent-upload-length"
        assert resp_headers.get(UPLOAD_COMPLETE) == FALSE
        # Optional check for Upload-Offset (Correction 4.3)
        if UPLOAD_OFFSET in resp_headers:
            assert int(resp_headers.get(UPLOAD_OFFSET)) >= 0

    def test_creation_inconsistent_length_across_requests(self, target_url, request):
        """
        §4.1.3 & 7.2: Length MUST stay consistent across requests.
        Quote: "The server MUST reject a request if the representation's length is known and inconsistent..."
        Expected behavior: PATCH with mismatched Upload-Length must be rejected with 400.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name, upload_length="100")
        headers = {
            UPLOAD_OFFSET: "0",
            UPLOAD_COMPLETE: FALSE,
            UPLOAD_LENGTH: "50",
            CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD,
        }
        status, resp_headers, body, _ = http_request("PATCH", upload_uri, headers=headers, body=b"A" * 32, test_name=request.node.name)
        assert status == 400
        if resp_headers.get("Content-Type", "").startswith(APPLICATION_PROBLEM_JSON):
            prob = json.loads(body.decode("utf-8"))
            assert prob.get("type") == "https://iana.org/assignments/http-problem-types#inconsistent-upload-length"
        assert resp_headers.get(UPLOAD_COMPLETE) == FALSE

    def test_creation_location_consistent_across_interim_and_final(self, target_url, request):
        """
        §4.2.2: Location MUST Be Identical Across Interim and Final Responses.
        Quote: "all interim and final response messages for the same request MUST contain an identical Location value"
        Expected behavior: Interim 104 responses during creation must have identical Location headers to final 201 response.
        """
        payload = b""
        headers = {
            UPLOAD_COMPLETE: FALSE,
            UPLOAD_LENGTH: "100",
        }
        status, resp_headers, _, interim = http_request("POST", target_url, headers=headers, body=payload, test_name=request.node.name)
        assert status == 201, f"Expected 201 Created, got {status}"
        final_loc = resp_headers.get(LOCATION)
        assert final_loc, "Final response MUST include Location"

        if interim:
            final_path = urlparse(final_loc).path
            for i_headers in interim:
                i_loc = i_headers.get(LOCATION, "")
                i_path = urlparse(i_loc).path
                assert i_path == final_path, f"Location in interim 104 ({i_loc}) MUST match Location in final response ({final_loc})"

    def test_creation_upload_length_persisted_across_appends(self, target_url, request):
        """
        §4.2.2: Server MUST Record Representation Length from Upload-Length.
        Quote: "The server MUST record the representation's length according to Section 4.1.3 if the Upload-Length... are included"
        Expected behavior: Upload-Length provided during creation MUST be persisted and returned in HEAD responses.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name, upload_length="200")

        # Append some data
        http_request("PATCH", upload_uri,
            headers={UPLOAD_OFFSET: "0", UPLOAD_COMPLETE: FALSE, CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD},
            body=b"A" * 32, test_name=request.node.name)

        # Retrieval
        status, resp_headers, _, _ = http_request("HEAD", upload_uri, test_name=request.node.name)
        assert status in (204, 200)
        assert resp_headers.get(UPLOAD_LENGTH) == "200", "Upload-Length MUST be persisted and returned"


class TestOffsetRetrieval:
    """Tests for Offset Retrieval (Section 4.3)."""

    def test_offset_retrieval_head(self, target_url, request):
        """
        §4.3: HEAD request to retrieve upload offset.
        Quote: "The server SHOULD NOT generate a response with the 301, 302, or 303 status codes..."
        Quote: "The response SHOULD include the Cache-Control header field with the no-store directive..."
        Expected behavior: 200/204 with Upload-Offset, Upload-Complete, Upload-Limit. Cache-Control: no-store should be present.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name)
        status, resp_headers, _, _ = http_request("HEAD", upload_uri, test_name=request.node.name)
        assert status in (204, 200)
        assert resp_headers.get(UPLOAD_OFFSET) == "0"
        assert resp_headers.get(UPLOAD_COMPLETE) == FALSE
        assert "no-store" in resp_headers.get("Cache-Control", ""), "HEAD response SHOULD include Cache-Control: no-store"
        assert UPLOAD_LIMIT in resp_headers, "HEAD response MUST include Upload-Limit"
        assert status not in (301, 302, 303), "HEAD response SHOULD NOT redirect"

    def test_offset_retrieval_get(self, target_url, request):
        """
        §4.3: GET request for offset retrieval.
        Quote: "MUST indicate the limits in the Upload-Limit header field"
        Expected behavior: 200/204 response with Upload-Limit, Upload-Length, and Cache-Control: no-store.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name, upload_length="100")
        status, resp_headers, _, _ = http_request("GET", upload_uri, test_name=request.node.name)
        assert status in (200, 204)
        assert resp_headers.get(UPLOAD_OFFSET) == "0"
        assert resp_headers.get(UPLOAD_COMPLETE) == FALSE, "GET response MUST include Upload-Complete"
        assert UPLOAD_LIMIT in resp_headers, "GET response MUST include Upload-Limit"
        assert resp_headers.get(UPLOAD_LENGTH) == "100", "GET response MUST include Upload-Length when known"
        assert "no-store" in resp_headers.get("Cache-Control", ""), "GET response SHOULD include Cache-Control: no-store"

    def test_offset_retrieval_bad_head_upload_offset(self, target_url, request):
        """
        §4.3.1: HEAD request containing Upload-Offset header.
        Note: Defensive compliance test enforcing client MUST NOT requirements.
        Expected behavior: Server should defensively reject with 400.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name)
        headers = {UPLOAD_OFFSET: "10"}
        status, _, _, _ = http_request("HEAD", upload_uri, headers=headers, test_name=request.node.name)
        assert status == 400

    def test_offset_retrieval_bad_head_upload_complete(self, target_url, request):
        """
        §4.3.1: HEAD request containing Upload-Complete header.
        Note: Defensive compliance test enforcing client MUST NOT requirements.
        Expected behavior: Server should defensively reject with 400.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name)
        headers = {UPLOAD_COMPLETE: FALSE}
        status, _, _, _ = http_request("HEAD", upload_uri, headers=headers, test_name=request.node.name)
        assert status == 400


class TestUploadAppend:
    """Tests for Upload Append (Section 4.4 & Section 6)."""

    def test_append_partial_data(self, target_url, request):
        """
        §4.4: Appending intermediate data via PATCH.
        Quote: "The server SHOULD NOT generate a response with the 301, 302, or 303 status codes..."
        Expected behavior: 200/204 response. 104 interim response MUST NOT include Location. Should not redirect.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name)
        headers = {
            UPLOAD_OFFSET: "0",
            UPLOAD_COMPLETE: FALSE,
            CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD,
        }
        status, resp_headers, _, interim = http_request("PATCH", upload_uri, headers=headers, body=b"A" * 32, test_name=request.node.name)
        assert status in (204, 200)
        assert resp_headers.get(UPLOAD_COMPLETE) == FALSE
        assert status not in (301, 302, 303), "Append MUST NOT respond with 301/302/303 redirect"

        resp_offset = resp_headers.get(UPLOAD_OFFSET)
        if resp_offset:
            assert resp_offset == "32", f"Upload-Offset in response should be 32, got {resp_offset}"

        if interim:
            for i_headers in interim:
                assert LOCATION not in i_headers, "104 interim response on append MUST NOT include Location"

    def test_append_missing_upload_offset(self, target_url, request):
        """
        §4.4.1: Upload-Offset MUST be included in PATCH append requests.
        Expected behavior: Server must reject the request.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name)
        headers = {
            UPLOAD_COMPLETE: FALSE,
            CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD,
        }
        status, _, _, _ = http_request("PATCH", upload_uri, headers=headers, body=b"A" * 32, test_name=request.node.name)
        assert status in (400, 409)

    def test_append_missing_upload_complete(self, target_url, request):
        """
        §4.4.1: Upload Append: MUST Include Upload-Complete.
        Quote: "The request MUST include the Upload-Complete header field."
        Expected behavior: Sending PATCH without Upload-Complete MUST be rejected (400).
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name)
        headers = {
            UPLOAD_OFFSET: "0",
            CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD,
        }
        status, _, _, _ = http_request("PATCH", upload_uri, headers=headers, body=b"A" * 32, test_name=request.node.name)
        assert status in (400, 409), "PATCH missing Upload-Complete MUST be rejected"

    def test_append_wrong_content_type(self, target_url, request):
        """
        §4.4.2: Upload Append: Content-Type MUST Be application/partial-upload.
        Quote: "A server applies a PATCH request with the application/partial-upload media type..."
        Expected behavior: PATCH with wrong Content-Type MUST be rejected (400 or 415).
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name)
        headers = {
            UPLOAD_OFFSET: "0",
            UPLOAD_COMPLETE: FALSE,
            CONTENT_TYPE: "text/plain",
        }
        status, _, _, _ = http_request("PATCH", upload_uri, headers=headers, body=b"A" * 32, test_name=request.node.name)
        assert status in (400, 415), "PATCH with incorrect Content-Type MUST be rejected"

    def test_append_empty_intermediate(self, target_url, request):
        """
        §6: Empty intermediate append (Upload-Complete: ?0, empty body).
        Expected behavior: If min-append-size > 0, should reject with 400 and include Upload-Limit.
        """
        _, resp_headers, _, _ = http_request("OPTIONS", target_url, test_name=request.node.name)
        limit_hdr = resp_headers.get(UPLOAD_LIMIT, "")
        min_append_size = 0
        for part in limit_hdr.split(","):
            if "min-append-size=" in part:
                min_append_size = int(part.split("=")[1].strip())

        upload_uri = create_partial_upload(target_url, test_name=request.node.name)
        headers = {
            UPLOAD_OFFSET: "0",
            UPLOAD_COMPLETE: FALSE,
            CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD,
        }
        status, resp_headers, _, _ = http_request("PATCH", upload_uri, headers=headers, body=b"", test_name=request.node.name)
        if min_append_size > 0:
            assert status == 400
            assert UPLOAD_LIMIT in resp_headers, "Rejection response SHOULD include Upload-Limit"
        else:
            assert status in (200, 204)

    def test_append_exceeding_upload_length(self, target_url, request):
        """
        §4.4.2: Appending beyond the declared Upload-Length.
        Quote: "the server MUST reject the request with a 409 (Conflict) status code and the Upload-Complete header field set to false..."
        Expected behavior: Reject with 409, Upload-Complete ?0, resource invalidated.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name, upload_length="50")
        headers = {
            UPLOAD_OFFSET: "0",
            UPLOAD_COMPLETE: FALSE,
            CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD,
        }
        status, resp_headers, _, _ = http_request("PATCH", upload_uri, headers=headers, body=b"B" * 100, test_name=request.node.name)
        assert status in (400, 409)
        assert resp_headers.get(UPLOAD_COMPLETE) == FALSE, "Error response for append MUST include Upload-Complete: ?0"

        # Verify resource is invalidated after exceeding length
        h_status, _, _, _ = http_request("HEAD", upload_uri, test_name=request.node.name)
        assert h_status not in (200, 204), "Resource should be invalidated after exceeding length"

    def test_append_exactly_at_length_then_exceed(self, target_url, request):
        """
        §4.4.2: Offset Exceeding Length: Server MUST Invalidate Upload Resource.
        Quote: "the server MUST prevent the offset from exceeding the representation's length by rejecting the request... marking the upload resource invalid"
        Expected behavior: Appending exactly at length succeeds. Appending 1 more byte fails and invalidates resource.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name, upload_length="50")
        headers = {
            UPLOAD_OFFSET: "0",
            UPLOAD_COMPLETE: FALSE,
            CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD,
        }
        # Append exact length
        status, _, _, _ = http_request("PATCH", upload_uri, headers=headers, body=b"B" * 50, test_name=request.node.name)
        assert status in (200, 204)

        # Exceed by 1 byte
        headers = {
            UPLOAD_OFFSET: "50",
            UPLOAD_COMPLETE: FALSE,
            CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD,
        }
        status_exceed, resp_headers, _, _ = http_request("PATCH", upload_uri, headers=headers, body=b"B", test_name=request.node.name)
        assert status_exceed in (400, 409), "Appending beyond exact length MUST be rejected"

        # Verify invalidation
        h_status, _, _, _ = http_request("HEAD", upload_uri, test_name=request.node.name)
        assert h_status not in (200, 204), "Resource should be marked invalid after offset exceeds length"

    def test_append_offset_mismatch(self, target_url, request):
        """
        §4.4.2 & 7.1: Mismatching Upload-Offset.
        Quote: "the server MUST reject the request with a 409 (Conflict) status code and the Upload-Complete header field set to false..."
        Expected behavior: Reject with 409, Upload-Complete ?0, correct Upload-Offset.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name)
        headers = {
            UPLOAD_OFFSET: "99",
            UPLOAD_COMPLETE: FALSE,
            CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD,
        }
        status, resp_headers, body, _ = http_request("PATCH", upload_uri, headers=headers, body=b"A" * 32, test_name=request.node.name)
        assert status == 409
        assert resp_headers.get(UPLOAD_OFFSET) == "0"
        assert resp_headers.get(UPLOAD_COMPLETE) == FALSE
        if resp_headers.get("Content-Type", "").startswith(APPLICATION_PROBLEM_JSON):
            prob = json.loads(body.decode("utf-8"))
            assert "expected-offset" in prob and prob["expected-offset"] == 0
            assert "provided-offset" in prob and prob["provided-offset"] == 99
            assert prob.get("type") == "https://iana.org/assignments/http-problem-types#mismatching-upload-offset"

    def test_append_offset_mismatch_after_partial_data(self, target_url, request):
        """
        §4.4.2: Upload Append: Response MUST Include Correct Upload-Offset on 409 (after partial data).
        Quote: "The response MUST include the correct offset in the Upload-Offset header field."
        Expected behavior: Upload 32 bytes. Send PATCH with offset 0. Expected 409 with Upload-Offset: 32.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name)

        # Initial valid append
        http_request("PATCH", upload_uri,
            headers={UPLOAD_OFFSET: "0", UPLOAD_COMPLETE: FALSE, CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD},
            body=b"A" * 32, test_name=request.node.name)

        # Stale offset append
        headers = {
            UPLOAD_OFFSET: "0",
            UPLOAD_COMPLETE: FALSE,
            CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD,
        }
        status, resp_headers, _, _ = http_request("PATCH", upload_uri, headers=headers, body=b"B" * 32, test_name=request.node.name)
        assert status == 409, "Mismatching offset MUST be rejected with 409"
        assert resp_headers.get(UPLOAD_OFFSET) == "32", "Response MUST include the correct server-side offset"
        assert resp_headers.get(UPLOAD_COMPLETE) == FALSE, "Response MUST include Upload-Complete: ?0"

    def test_length_derived_from_completing_append(self, target_url, request):
        """
        §4.1.3: Length Derivation from Upload-Complete: ?1 and Content-Length.
        Quote: "The representation's length is then the sum of the current offset (Section 4.1.1) and the request content's length"
        Expected behavior: Append with ?1 when length is unknown derives length correctly.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name, upload_length=None)

        # Append 50 bytes and complete
        headers = {
            UPLOAD_OFFSET: "0",
            UPLOAD_COMPLETE: TRUE,
            CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD,
        }
        status, _, _, _ = http_request("PATCH", upload_uri, headers=headers, body=b"A" * 50, test_name=request.node.name)
        assert status in (200, 204, 201)

        # Retrieve and verify length
        h_status, h_headers, _, _ = http_request("HEAD", upload_uri, test_name=request.node.name)
        assert h_status in (200, 204)
        assert h_headers.get(UPLOAD_LENGTH) == "50", "Server MUST correctly derive Upload-Length from completing append"


class TestUploadCancellation:
    """Tests for Upload Cancellation (Section 4.5)."""

    def test_cancellation_delete(self, target_url, request):
        """
        §4.5: Cancel upload via DELETE request.
        Quote: "The server SHOULD NOT generate a response with the 301, 302, or 303 status codes..."
        Expected behavior: 204/200, no redirect.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name)
        status, _, _, _ = http_request("DELETE", upload_uri, test_name=request.node.name)
        assert status in (204, 200)
        assert status not in (301, 302, 303), "DELETE response SHOULD NOT redirect"

    def test_cancellation_delete_completed_upload(self, target_url, request):
        """
        §4.5: DELETE on a completed upload resource.
        Expected behavior: DELETE should succeed or return 404 if already cleaned up.
        """
        payload = b"Complete"
        headers = {UPLOAD_COMPLETE: TRUE, UPLOAD_LENGTH: str(len(payload))}
        status, resp_headers, _, _ = http_request("POST", target_url, headers=headers, body=payload, test_name=request.node.name)
        assert status in (200, 201)
        loc = resp_headers.get(LOCATION)
        if loc:
            if not loc.startswith("http"):
                parsed = urlparse(target_url)
                loc = f"{parsed.scheme}://{parsed.netloc}{loc}"
            del_status, _, _, _ = http_request("DELETE", loc, test_name=request.node.name)
            assert del_status in (204, 200, 404)


class TestUploadLimitEnforcement:
    """Tests for Upload Limit Enforcement (§4.1.4)."""

    def test_options_upload_limit_structured_field_format(self, target_url, request):
        """
        §4.1.4: Upload-Limit MUST be a Dictionary Structured Header Field.
        Quote: "a member with an unknown key MUST be ignored"
        Expected behavior: Server responds with valid dictionary; test ignores unknown keys.
        """
        status, resp_headers, _, _ = http_request("OPTIONS", target_url, test_name=request.node.name)
        assert status in (200, 204)
        limit_hdr = resp_headers.get(UPLOAD_LIMIT, "")
        assert limit_hdr, "Upload-Limit MUST be present in OPTIONS response"
        known_keys = {"max-size", "min-size", "max-append-size", "min-append-size", "max-age"}
        has_limit = False
        for part in limit_hdr.split(","):
            part = part.strip()
            if "=" in part:
                key, val = part.split("=", 1)
                key = key.strip()
                val = val.strip()
                if key in known_keys:
                    assert val.lstrip("-").isdigit(), f"Value for '{key}' must be Integer, got '{val}'"
                    has_limit = True
        if not has_limit:
            assert "min-size=0" in limit_hdr.replace(" ", ""), "If no limits, MUST use min-size=0"

    def test_creation_exceeding_max_size(self, target_url, request):
        """
        §4.1.4: Server might reject uploads exceeding max-size.
        Quote: "When a request is rejected because limits were violated, the response SHOULD include the Upload-Limit header field"
        Expected behavior: 400 or 413 error with Upload-Limit in response.
        """
        _, resp_headers, _, _ = http_request("OPTIONS", target_url, test_name=request.node.name)
        limit_hdr = resp_headers.get(UPLOAD_LIMIT, "")
        max_size = None
        for part in limit_hdr.split(","):
            if "max-size=" in part:
                max_size = int(part.split("=")[1].strip())
        if max_size is None:
            pytest.skip("Server does not advertise max-size")
        headers = {UPLOAD_COMPLETE: TRUE, UPLOAD_LENGTH: str(max_size + 1)}
        status, rej_headers, _, _ = http_request("POST", target_url, headers=headers, body=b"x", test_name=request.node.name)
        assert status in (400, 413), f"Expected rejection for exceeding max-size, got {status}"
        assert UPLOAD_LIMIT in rej_headers, "Rejection response SHOULD include Upload-Limit"

    def test_creation_exceeding_min_append_size(self, target_url, request):
        """
        §4.1.4: min-append-size enforcement.
        Expected behavior: Rejection response SHOULD include Upload-Limit.
        """
        _, resp_headers, _, _ = http_request("OPTIONS", target_url, test_name=request.node.name)
        limit_hdr = resp_headers.get(UPLOAD_LIMIT, "")
        min_append_size = None
        for part in limit_hdr.split(","):
            if "min-append-size=" in part:
                min_append_size = int(part.split("=")[1].strip())
        if min_append_size is None or min_append_size <= 1:
            pytest.skip("Server does not advertise min-append-size > 1")

        upload_uri = create_partial_upload(target_url, test_name=request.node.name)
        headers = {
            UPLOAD_OFFSET: "0",
            UPLOAD_COMPLETE: FALSE,
            CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD,
        }
        body = b"x" * (min_append_size - 1)
        status, rej_headers, _, _ = http_request("PATCH", upload_uri, headers=headers, body=body, test_name=request.node.name)
        assert status in (400, 413), f"Expected rejection for violating min-append-size, got {status}"
        assert UPLOAD_LIMIT in rej_headers, "Rejection response SHOULD include Upload-Limit"

    def test_creation_empty_body_ignores_min_append_size(self, target_url, request):
        """
        §4.1.4: min-append-size Does NOT Apply to Upload Creation With No Content.
        Quote: "This limit does not apply to upload creation requests with no content..."
        Expected behavior: Empty body creation accepted even if min-append-size > 0.
        """
        headers = {UPLOAD_COMPLETE: FALSE, UPLOAD_LENGTH: "100"}
        status, _, _, _ = http_request("POST", target_url, headers=headers, body=b"", test_name=request.node.name)
        assert status == 201, "Empty-body creation MUST be accepted regardless of min-append-size"

    def test_append_completing_below_min_append_size(self, target_url, request):
        """
        §4.1.4: min-append-size Does NOT Apply When Upload-Complete: ?1.
        Quote: "This limit does not apply to... requests completing the upload by including the Upload-Complete: ?1 header field."
        Expected behavior: Completing append accepted even if below min-append-size.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name, upload_length="1")
        headers = {
            UPLOAD_OFFSET: "0",
            UPLOAD_COMPLETE: TRUE,
            CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD,
        }
        status, _, _, _ = http_request("PATCH", upload_uri, headers=headers, body=b"X", test_name=request.node.name)
        assert status in (200, 201, 204), "Completing append MUST be accepted regardless of min-append-size"

    def test_append_exceeding_max_append_size(self, target_url, request):
        """
        §4.1.4, §4.7: Append Exceeding max-append-size Rejected With 413.
        Quote: "413 (Content Too Large) can be resumed after applying appropriate limits (Section 4.1.4)."
        Expected behavior: Append > max-append-size rejected with 413 and Upload-Limit included.
        """
        _, resp_headers, _, _ = http_request("OPTIONS", target_url, test_name=request.node.name)
        limit_hdr = resp_headers.get(UPLOAD_LIMIT, "")
        max_append_size = None
        for part in limit_hdr.split(","):
            if "max-append-size=" in part:
                max_append_size = int(part.split("=")[1].strip())
        if max_append_size is None:
            pytest.skip("Server does not advertise max-append-size")

        upload_uri = create_partial_upload(target_url, test_name=request.node.name)
        headers = {
            UPLOAD_OFFSET: "0",
            UPLOAD_COMPLETE: FALSE,
            CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD,
        }
        body = b"X" * (max_append_size + 1)
        status, rej_headers, _, _ = http_request("PATCH", upload_uri, headers=headers, body=body, test_name=request.node.name)
        assert status == 413, "Append exceeding max-append-size MUST be rejected with 413"
        assert UPLOAD_LIMIT in rej_headers, "413 rejection SHOULD include Upload-Limit"


class TestUploadResourceDeactivation:
    """Tests for Upload Resource Deactivation (§4.5, §4.4.2)."""

    def test_head_after_cancellation(self, target_url, request):
        """
        §4.5: HEAD After DELETE (Resource Deactivation).
        Quote: "the server... SHOULD deactivate the upload resource and reject further interaction with it."
        Expected behavior: After successful DELETE, HEAD should return 404.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name)
        http_request("DELETE", upload_uri, test_name=request.node.name)

        status, _, _, _ = http_request("HEAD", upload_uri, test_name=request.node.name)
        assert status in (404, 410), "HEAD on deactivated resource SHOULD return 404 (or 410)"

    def test_append_after_cancellation(self, target_url, request):
        """
        §4.5: PATCH After DELETE (Resource Deactivation).
        Quote: "the server... SHOULD deactivate the upload resource and reject further interaction with it."
        Expected behavior: After successful DELETE, PATCH should return 404.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name)
        http_request("DELETE", upload_uri, test_name=request.node.name)

        headers = {
            UPLOAD_OFFSET: "0",
            UPLOAD_COMPLETE: FALSE,
            CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD,
        }
        status, _, _, _ = http_request("PATCH", upload_uri, headers=headers, body=b"A", test_name=request.node.name)
        assert status in (404, 410), "PATCH on deactivated resource SHOULD return 404 (or 410)"

    def test_cancellation_delete_nonexistent(self, target_url, request):
        """
        §4.5: DELETE on Non-Existent Upload Resource.
        Expected behavior: Should return 404.
        """
        status, _, _, _ = http_request("DELETE", target_url + "/definitely-nonexistent-id", test_name=request.node.name)
        assert status == 404, "DELETE on non-existent resource SHOULD return 404"


class TestCompletedUploadBehavior:
    """Tests for interacting with completed uploads."""

    def test_offset_retrieval_head_completed_upload(self, target_url, request):
        """
        §4.3.2: Offset Retrieval: HEAD Response MUST Include Upload-Complete and Upload-Offset (for completed upload).
        Quote: "MUST include the Upload-Complete header field... indicating whether a final response was produced"
        Expected behavior: HEAD to a completed upload returns Upload-Complete: ?1 and Upload-Offset equal to total length.
        """
        payload = b"Completed Data"
        headers = {UPLOAD_COMPLETE: TRUE, UPLOAD_LENGTH: str(len(payload)), CONTENT_TYPE: "text/plain"}
        status, resp_headers, _, _ = http_request("POST", target_url, headers=headers, body=payload, test_name=request.node.name)
        loc = resp_headers.get(LOCATION)
        if not loc:
            pytest.skip("Server did not return Location for completed upload creation")
        if not loc.startswith("http"):
            parsed = urlparse(target_url)
            loc = f"{parsed.scheme}://{parsed.netloc}{loc}"

        h_status, h_headers, _, _ = http_request("HEAD", loc, test_name=request.node.name)
        assert h_status in (200, 204), "HEAD on completed upload should succeed"
        assert h_headers.get(UPLOAD_COMPLETE) == TRUE, "HEAD on completed upload MUST return Upload-Complete: ?1"
        assert h_headers.get(UPLOAD_OFFSET) == str(len(payload)), "HEAD on completed upload MUST return Upload-Offset equal to full length"

    def test_append_to_completed_upload(self, target_url, request):
        """
        §4.4.2: Upload-Complete: Append to Already-Completed Upload.
        Expected behavior: The server can replay the final response or reject with 4xx.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name, upload_length="10")
        http_request("PATCH", upload_uri,
            headers={UPLOAD_OFFSET: "0", UPLOAD_COMPLETE: TRUE, CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD},
            body=b"A" * 10, test_name=request.node.name)

        # Re-append to completed
        status, resp_headers, _, _ = http_request("PATCH", upload_uri,
            headers={UPLOAD_OFFSET: "10", UPLOAD_COMPLETE: TRUE, CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD},
            body=b"", test_name=request.node.name)

        assert status in (200, 201, 204, 400, 409, 410, 404), "Append to completed upload should replay success or return 4xx error"


class TestConcurrencyAndRetry:
    """Tests for Concurrency (§4.6) and Retry (§4.7)."""

    def test_concurrency_race_condition(self, target_url, request):
        """§4.6: Server MUST prevent race conditions from concurrent requests."""
        upload_uri = create_partial_upload(target_url, test_name=request.node.name)

        results = []
        def do_patch(i):
            headers = {UPLOAD_OFFSET: "0", UPLOAD_COMPLETE: FALSE, CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD}
            st, _, _, _ = http_request("PATCH", upload_uri, headers=headers, body=b"A" * 32, test_name=request.node.name)
            results.append(st)

        t1 = threading.Thread(target=do_patch, args=(1,))
        t2 = threading.Thread(target=do_patch, args=(2,))
        t1.start()
        t2.start()
        t1.join()
        t2.join()

        successes = [r for r in results if r in (200, 204)]
        assert len(successes) <= 1, "Concurrent patches at the same offset MUST NOT both succeed"

    def test_head_then_append_offset_consistency(self, target_url, request):
        """§4.6: Offset from HEAD MUST be usable for the next append."""
        upload_uri = create_partial_upload(target_url, test_name=request.node.name)
        _, h_headers, _, _ = http_request("HEAD", upload_uri, test_name=request.node.name)
        offset = h_headers.get(UPLOAD_OFFSET, "0")
        headers = {UPLOAD_OFFSET: offset, UPLOAD_COMPLETE: FALSE, CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD}
        status, _, _, _ = http_request("PATCH", upload_uri, headers=headers, body=b"A" * 32, test_name=request.node.name)
        assert status in (200, 204), f"Append at HEAD-reported offset must succeed, got {status}"

    def test_retry_after_409_with_correct_offset(self, target_url, request):
        """§4.7: 409 Conflict can be resumed with the correct offset."""
        upload_uri = create_partial_upload(target_url, test_name=request.node.name)
        status, resp_headers, _, _ = http_request("PATCH", upload_uri,
            headers={UPLOAD_OFFSET: "99", UPLOAD_COMPLETE: FALSE, CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD},
            body=b"A" * 32, test_name=request.node.name)
        assert status == 409
        correct_offset = resp_headers.get(UPLOAD_OFFSET)
        assert correct_offset
        status2, _, _, _ = http_request("PATCH", upload_uri,
            headers={UPLOAD_OFFSET: correct_offset, UPLOAD_COMPLETE: FALSE, CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD},
            body=b"A" * 32, test_name=request.node.name)
        assert status2 in (200, 204), f"Retry with correct offset should succeed, got {status2}"

    def test_concurrent_head_during_patch(self, target_url, request):
        """
        §4.6: Concurrency: Concurrent HEAD While PATCH Is In-Flight.
        Quote: "the server MUST NOT send outdated offsets"
        Expected behavior: HEAD during PATCH returns consistent (not stale) offset.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name)

        # Simulate in-flight by firing patch and head almost together
        head_offsets = []
        def do_head():
            time.sleep(0.01) # Give patch a moment to start processing
            _, h_headers, _, _ = http_request("HEAD", upload_uri, test_name=request.node.name)
            if h_headers.get(UPLOAD_OFFSET):
                head_offsets.append(int(h_headers.get(UPLOAD_OFFSET)))

        t1 = threading.Thread(target=http_request, args=("PATCH", upload_uri), kwargs={"headers": {UPLOAD_OFFSET: "0", UPLOAD_COMPLETE: FALSE, CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD}, "body": b"A" * 64, "test_name": request.node.name})
        t2 = threading.Thread(target=do_head)
        t1.start()
        t2.start()
        t1.join()
        t2.join()

        # The offset returned must be either 0 (before processing) or 64 (after processing)
        if head_offsets:
            assert head_offsets[0] in (0, 64), "Concurrent HEAD MUST NOT return an inconsistent/stale intermediate offset"

    def test_concurrent_delete_during_patch(self, target_url, request):
        """
        §4.6: Concurrency: DELETE While PATCH Is In-Flight.
        Expected behavior: DELETE succeeds and resource is deactivated.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name)
        def do_delete():
            time.sleep(0.01)
            http_request("DELETE", upload_uri, test_name=request.node.name)

        t1 = threading.Thread(target=http_request, args=("PATCH", upload_uri), kwargs={"headers": {UPLOAD_OFFSET: "0", UPLOAD_COMPLETE: FALSE, CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD}, "body": b"A" * 64, "test_name": request.node.name})
        t2 = threading.Thread(target=do_delete)
        t1.start()
        t2.start()
        t1.join()
        t2.join()

        # Resource should be deactivated
        st, _, _, _ = http_request("HEAD", upload_uri, test_name=request.node.name)
        assert st in (404, 410), "Resource MUST be deactivated if deleted while PATCH was in-flight"


class TestResumableUploadLifecycle:
    """Tests for Upload Strategies (§10)."""

    def test_full_resumable_upload_lifecycle(self, target_url, request):
        """
        §3.1 & §10.1: Full lifecycle: create → append → HEAD → resume → complete.
        Quote: "The server SHOULD include the Upload-Complete (Section 4.1.2) header field in the response..."
        Expected behavior: Final PATCH response includes Upload-Complete: ?1.
        """
        upload_uri = create_partial_upload(target_url, test_name=request.node.name)
        status1, _, _, _ = http_request("PATCH", upload_uri,
            headers={UPLOAD_OFFSET: "0", UPLOAD_COMPLETE: FALSE, CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD},
            body=b"A" * 32, test_name=request.node.name)
        assert status1 in (200, 204)

        _, h_headers, _, _ = http_request("HEAD", upload_uri, test_name=request.node.name)
        offset = h_headers.get(UPLOAD_OFFSET)
        assert offset, "HEAD must return Upload-Offset"

        remaining = b"B" * 68
        status2, resp_headers, _, _ = http_request("PATCH", upload_uri,
            headers={UPLOAD_OFFSET: offset, UPLOAD_COMPLETE: TRUE, CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD},
            body=remaining, test_name=request.node.name)
        assert status2 in (200, 201, 204)
        assert resp_headers.get(UPLOAD_COMPLETE) == TRUE, "Final PATCH response MUST include Upload-Complete: ?1"

    def test_careful_upload_creation(self, target_url, request):
        """§10.2: Careful Upload Creation workflow."""
        headers = {UPLOAD_COMPLETE: FALSE, UPLOAD_LENGTH: "100"}
        status, resp_headers, _, _ = http_request("POST", target_url, headers=headers, body=b"", test_name=request.node.name)
        assert status == 201
        upload_uri = resp_headers.get(LOCATION)
        if not upload_uri.startswith("http"):
            parsed = urlparse(target_url)
            upload_uri = f"{parsed.scheme}://{parsed.netloc}{upload_uri}"

        status2, _, _, _ = http_request("PATCH", upload_uri,
            headers={UPLOAD_OFFSET: "0", UPLOAD_COMPLETE: FALSE, CONTENT_TYPE: APPLICATION_PARTIAL_UPLOAD},
            body=b"A" * 32, test_name=request.node.name)
        assert status2 in (200, 204)

    def test_transparent_upgrade_to_resumable(self, target_url, request):
        """
        §10.1.1: Transparent Upgrade to Resumable Uploads.
        Expected behavior: POST with Upload-Complete: ?1 and full body acts as an upgrade.
        """
        payload = b"Full Body Upload"
        headers = {UPLOAD_COMPLETE: TRUE}
        status, resp_headers, _, _ = http_request("POST", target_url, headers=headers, body=payload, test_name=request.node.name)
        assert status in (200, 201), "Transparent upgrade POST MUST succeed"
        assert resp_headers.get(UPLOAD_COMPLETE) == TRUE, "Response to transparent upgrade SHOULD include Upload-Complete: ?1"

    def test_options_with_upload_complete_header(self, target_url, request):
        """
        §4.1.4: OPTIONS Without Upload-Complete Header vs With.
        Quote: "When responding to an OPTIONS request without the Upload-Complete header field..."
        Expected behavior: OPTIONS with Upload-Complete should not confuse the server.
        """
        headers = {UPLOAD_COMPLETE: TRUE}
        status, resp_headers, _, _ = http_request("OPTIONS", target_url, headers=headers, test_name=request.node.name)
        assert status in (200, 204), "OPTIONS request with Upload-Complete MUST succeed"


class TestHttpDigests:
    """Tests for HTTP Digests (RFC 9530)."""

    def test_creation_with_valid_content_digest(self, target_url, request):
        """RFC 9530 Section 2: Valid Content-Digest header in creation request."""
        payload = b"RFC 9530 Digest Test Data"
        digest_bytes = hashlib.sha256(payload).digest()
        b64_digest = base64.b64encode(digest_bytes).decode("ascii")
        content_digest = f"sha-256=:{b64_digest}:"
        headers = {
            UPLOAD_COMPLETE: TRUE,
            UPLOAD_LENGTH: str(len(payload)),
            "Content-Digest": content_digest,
        }
        status, _, _, _ = http_request("POST", target_url, headers=headers, body=payload, test_name=request.node.name)
        assert status in (200, 201)

    def test_creation_with_invalid_content_digest(self, target_url, request):
        """RFC 9530 Section 2: Invalid Content-Digest header."""
        payload = b"RFC 9530 Digest Test Data"
        invalid_digest = "sha-256=:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=:"
        headers = {
            UPLOAD_COMPLETE: TRUE,
            UPLOAD_LENGTH: str(len(payload)),
            "Content-Digest": invalid_digest,
        }
        status, _, _, _ = http_request("POST", target_url, headers=headers, body=payload, test_name=request.node.name)
        assert status in (400, 409)


# CLI Entry Point & Custom Formatted Summary Reporter
if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="RUFH Draft-12 Conformity Test Runner")
    parser.add_argument(
        "--url",
        default="http://localhost:8080/test/api/upload",
        help="Target RUFH upload endpoint URL",
    )
    args = parser.parse_args()

    os.environ["RUFH_URL"] = args.url

    print("=" * 70)
    print("      RUFH (IETF Resumable Uploads for HTTP) Conformity Test Suite")
    print("      Specification: draft-ietf-httpbis-resumable-upload-12")
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
                    err_text = report.longreprtext if hasattr(report, "longreprtext") else str(report.longrepr)
                    self.failed.append((report.nodeid, err_text))

    reporter = CustomReporter()
    pytest.main([__file__, "-q", f"--url={args.url}"], plugins=[reporter])

    total_tests = len(reporter.passed) + len(reporter.failed)
    mod = sys.modules.get("rufh_conformity_test")
    interim_set = INTERIM_RESPONSES_DETECTED
    if mod and hasattr(mod, "INTERIM_RESPONSES_DETECTED"):
        interim_set = interim_set | mod.INTERIM_RESPONSES_DETECTED
    interim_count = len(interim_set)

    print("\n" + "=" * 70)
    print("                       CONFORMITY TEST RESULTS")
    print("=" * 70)
    print(f" Total Tests Executed: {total_tests}")
    print(f" Passed:               {len(reporter.passed)}")
    print(f" Failed:               {len(reporter.failed)}")
    print(f" 104 Interim Responses: {interim_count} tests detected 104 responses")
    print("=" * 70)

    if reporter.failed:
        print("\n[!] DETAILED FAILURE BREAKDOWN FOR AI AGENT / DEVELOPER REMEDIATION:")
        print("-" * 70)
        for idx, (test_id, err_text) in enumerate(reporter.failed, 1):
            print(f"\n{idx}. Test: {test_id}")
            func_name = test_id.split("::")[-1]
            print(f"   Function: {func_name}")
            print(f"   Specification Goal:\n     " + reporter.docs.get(test_id, "").replace("\n", "\n     "))
            print(f"   Diagnostics:\n   " + err_text.replace("\n", "\n   "))
            print("-" * 70)
    else:
        print("\n[✓] ALL RUFH DRAFT-12 CONFORMITY TESTS PASSED SUCCESSFULLY!")

    sys.exit(0 if not reporter.failed else 1)
