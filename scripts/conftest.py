import os
import pytest

def pytest_addoption(parser):
    """Register --url CLI option for pytest."""
    parser.addoption(
        "--url",
        action="store",
        default=os.environ.get("RUFH_URL", "http://localhost:8080/test/api/upload"),
        help="Target RUFH upload endpoint URL",
    )
