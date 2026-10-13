#!/usr/bin/env python3
"""Read-only CI inventory. Does not install, accept licenses, sign, or launch a VM."""
import json
import os
import shutil
import tempfile
from pathlib import Path


def properties(path):
    if not path.is_file() or path.stat().st_size > 65536:
        return {}
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            if key.strip() in {"Pkg.Revision", "AndroidVersion.ApiLevel", "SystemImage.Abi", "SystemImage.TagId"}:
                values[key.strip()] = value.strip()
    return values


def inventory():
    sdk_value = os.environ.get("ANDROID_HOME", "")
    sdk = Path(sdk_value) if sdk_value else None
    images = []
    if sdk and (sdk / "system-images").is_dir():
        for path in sorted((sdk / "system-images").glob("*/*/*/source.properties"))[:100]:
            images.append({"package_path": str(path.parent.relative_to(sdk)), "properties": properties(path)})
    return {
        "scope": "read-only installed-tool inventory; not runtime approval or execution",
        "sdk_configured": bool(sdk),
        "emulator_present": bool(sdk and (sdk / "emulator/emulator").is_file()),
        "emulator_properties": properties(sdk / "emulator/source.properties") if sdk else {},
        "platform_tools_properties": properties(sdk / "platform-tools/source.properties") if sdk else {},
        "installed_system_images": images,
        "temporary_disk_free_bytes": shutil.disk_usage(tempfile.gettempdir()).free,
        "kvm_present": Path("/dev/kvm").exists(),
        "kvm_readable": os.access("/dev/kvm", os.R_OK),
        "kvm_writable": os.access("/dev/kvm", os.W_OK),
        "mutations_performed": [],
    }


if __name__ == "__main__":
    print(json.dumps(inventory(), indent=2))
