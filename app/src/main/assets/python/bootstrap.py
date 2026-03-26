import os
import sys
from typing import Dict


def initialize(runtime_root: str) -> Dict[str, str]:
    runtime_root = os.path.abspath(runtime_root)
    site_packages = os.path.join(runtime_root, "site-packages")
    for candidate in (site_packages, runtime_root):
        if candidate not in sys.path:
            sys.path.insert(0, candidate)
    return {
        "runtime_root": runtime_root,
        "site_packages": site_packages,
    }
