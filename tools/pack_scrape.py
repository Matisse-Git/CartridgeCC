"""Build a private Cartridge v1 scrape pack from a JSON manifest and local images.

Usage: python tools/pack_scrape.py manifest.json output.zip [--rom game.gba]
--rom hashes one game into a single-game manifest; ROM bytes are never archived.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile


def build(manifest_path, output_path, rom=None):
    manifest_path, output_path = Path(manifest_path).resolve(), Path(output_path).resolve()
    if manifest_path.stat().st_size > 1024 * 1024:
        raise ValueError("Manifest exceeds 1 MiB")
    data = json.loads(manifest_path.read_text(encoding="utf-8"))
    if data.get("format") != "project-cartridge.scrape-pack" or data.get("version") != 1:
        raise ValueError("Unsupported format/version")
    if rom:
        if len(data.get("games", [])) != 1:
            raise ValueError("--rom requires a single-game manifest")
        digest = hashlib.sha256()
        with Path(rom).open("rb") as source:
            for chunk in iter(lambda: source.read(65536), b""):
                digest.update(chunk)
        data["games"][0]["romSha256"] = digest.hexdigest()
    files = {}
    total = 0
    for asset in data["assets"]:
        path = asset["path"]
        if not re.fullmatch(r"[A-Za-z0-9._/-]{1,240}", path) or path.startswith("/") or any(p in ("", ".", "..") for p in path.split("/")):
            raise ValueError("Unsafe image path")
        source = (manifest_path.parent / path).resolve()
        if not source.is_relative_to(manifest_path.parent) or source.suffix.lower() not in (".png", ".jpg", ".jpeg", ".webp"):
            raise ValueError("Images must stay within the manifest folder")
        if not 0 < source.stat().st_size <= 12 * 1024 * 1024:
            raise ValueError("Image must be at most 12 MiB")
        total += source.stat().st_size
        if total > 256 * 1024 * 1024 or len(files) >= 1024:
            raise ValueError("Pack exceeds supported bounds")
        contents = source.read_bytes()
        asset["sha256"] = hashlib.sha256(contents).hexdigest()
        files[path] = contents
    encoded = json.dumps(data, ensure_ascii=False, indent=2).encode("utf-8")
    if len(encoded) > 1024 * 1024:
        raise ValueError("Manifest exceeds 1 MiB")
    if output_path == manifest_path or output_path in [(manifest_path.parent / name).resolve() for name in files]:
        raise ValueError("Output must not overwrite a source file")
    # Fail if an output exists. Explicitly choose a new path for another private export.
    with zipfile.ZipFile(output_path, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("pack.json", encoded)
        for name, contents in files.items():
            archive.writestr(name, contents)
    if output_path.stat().st_size > 128 * 1024 * 1024:
        output_path.unlink()
        raise ValueError("Compressed pack exceeds 128 MiB")
    return output_path


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest")
    parser.add_argument("output")
    parser.add_argument("--rom")
    args = parser.parse_args()
    print(build(args.manifest, args.output, args.rom))
