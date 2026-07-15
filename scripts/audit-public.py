#!/usr/bin/env python3
"""Fail on files/secrets that must never enter the public repository."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SKIP_DIRS = {'.git', 'node_modules', 'build', '.gradle', 'dist'}
FORBIDDEN_NAMES = {
    '.env', 'google-services.json', 'local.properties',
}
FORBIDDEN_SUFFIXES = {'.apk', '.aab', '.jks', '.keystore', '.p12', '.pem', '.key'}
PATTERNS = {
    'private key': re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    'GitHub token': re.compile(r'\b(?:ghp|github_pat)_[A-Za-z0-9_]{20,}\b'),
    'Google API key': re.compile(r'\bAIza[0-9A-Za-z_-]{30,}\b'),
    'authorization bearer': re.compile(r'(?i)authorization\s*[:=]\s*["\']?bearer\s+[A-Za-z0-9._-]{12,}'),
    'credential URL': re.compile(r'https?://[^\s/@:]+:[^\s/@]+@'),
    'private LAN address': re.compile(r'(?<![\d.])(?:192\.168|10\.\d|172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}(?![\d.])'),
}

problems = []
for path in ROOT.rglob('*'):
    if any(part in SKIP_DIRS for part in path.relative_to(ROOT).parts):
        continue
    if not path.is_file():
        continue
    rel = path.relative_to(ROOT)
    if rel == Path('scripts/audit-public.py'):
        continue
    if path.name in FORBIDDEN_NAMES or path.suffix.lower() in FORBIDDEN_SUFFIXES:
        problems.append(f'forbidden file: {rel}')
        continue
    if path.suffix.lower() in {'.png', '.wav', '.jar'}:
        continue
    try:
        text = path.read_text(encoding='utf-8')
    except UnicodeDecodeError:
        problems.append(f'unreviewed binary: {rel}')
        continue
    for label, pattern in PATTERNS.items():
        if pattern.search(text):
            problems.append(f'{label}: {rel}')

if problems:
    print('PUBLIC AUDIT FAILED')
    print('\n'.join(f'- {item}' for item in problems))
    sys.exit(1)
print('PUBLIC AUDIT OK')
