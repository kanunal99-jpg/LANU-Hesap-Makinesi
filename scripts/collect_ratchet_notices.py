#!/usr/bin/env python3
"""Package the actual license/copyright notices of the Cargo.lock dependency graph."""
import json
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
metadata = json.loads(subprocess.check_output([
    'cargo', 'metadata', '--locked', '--format-version', '1', '--manifest-path',
    str(root / 'native/ratchet/Cargo.toml')], text=True))
output = root / 'build/ratchet/assets/ratchet-licenses'
output.mkdir(parents=True, exist_ok=True)
index = ['LANU Olm Double Ratchet native engine',
         'Pinned dependency notices. No Signal protocol interoperability claim.', '']
for package in sorted(metadata['packages'], key=lambda x: (x['name'], x['version'])):
    if package['name'] == 'lanu-ratchet':
        continue
    name = package['name'] + '-' + package['version']
    assert re.fullmatch(r'[A-Za-z0-9_.+-]+', name)
    directory = Path(package['manifest_path']).parent
    notices = []
    for path in directory.rglob('*'):
        relative = path.relative_to(directory)
        if len(relative.parts) <= 3 and path.is_file() and re.match(r'^(LICENSE|LICENCE|COPYING|COPYRIGHT|NOTICE)([._-]|$)', path.name, re.I):
            assert path.resolve().is_relative_to(directory.resolve())
            notices.append(path)
    if package.get('license_file'):
        path = directory / package['license_file']
        assert path.resolve().is_relative_to(directory.resolve()) and path.is_file()
        if path not in notices:
            notices.append(path)
    assert notices, f'Missing source license/copyright notices: {name}'
    index.extend([name, f'SPDX: {package.get("license") or "See bundled license"}',
                  f'Source: {package.get("repository") or package.get("source") or "crates.io"}'])
    destination = output / name
    destination.mkdir(exist_ok=True)
    for path in sorted(notices):
        filename = '__'.join(path.relative_to(directory).parts)
        (destination / filename).write_bytes(path.read_bytes())
        index.append(f'  {name}/{filename}')
    index.append('')
(output / 'THIRD_PARTY_NOTICES.txt').write_text('\n'.join(index) + '\n', encoding='utf-8')
print(f'PASS: bundled source license/copyright notices for {len(metadata["packages"])-1} pinned dependencies')
