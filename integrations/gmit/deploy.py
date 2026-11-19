#!/usr/bin/env python3
"""Deploy only the checked-in file list; preserve originals and verify rollback."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
from zipfile import ZipFile


SOURCE = Path(__file__).resolve().parents[2]
MANIFEST = Path(__file__).with_name('deployment.json')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None


def under(root, relative):
    if Path(relative).is_absolute() or '..' in Path(relative).parts:
        raise ValueError('Unsafe relative path: ' + relative)
    target = root / relative
    target.resolve().relative_to(root.resolve())
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', required=True, type=Path)
    parser.add_argument('--extractor-jar', type=Path, metavar='BUILT_JAR',
                        help='Also deploy the rebuilt MeaningMiner module to the allowlisted runtime loader path')
    action = parser.add_mutually_exclusive_group()
    action.add_argument('--apply', action='store_true')
    action.add_argument('--rollback', type=Path, metavar='RECEIPT')
    args = parser.parse_args()
    runtime = args.runtime.resolve()
    if runtime == SOURCE or not (runtime / 'bin/Modular.jar').is_file():
        raise ValueError('Runtime must be a separate existing Greta package')
    manifest = json.loads(MANIFEST.read_text())
    files = manifest['files']
    extractor = manifest['extractor_jar']
    if args.rollback:
        if args.extractor_jar:
            parser.error('--rollback does not take a build artifact')
        receipt = json.loads(args.rollback.read_text())
        if Path(receipt['runtime']).resolve() != runtime:
            raise ValueError('Receipt belongs to another runtime')
        for row in receipt['files']:
            if row['path'] not in files and row['path'] != extractor:
                raise ValueError('Receipt path not in the explicit deployment manifest')
            dst = under(runtime, row['path'])
            if digest(dst) != row['deployed_sha256']:
                raise ValueError('Refusing to overwrite later edits: ' + row['path'])
            if row['original_sha256']:
                backup = under(args.rollback.parent, 'originals/' + row['path'])
                if digest(backup) != row['original_sha256']:
                    raise ValueError('Original backup checksum mismatch')
        for row in receipt['files']:
            dst = under(runtime, row['path'])
            if row['original_sha256']:
                shutil.copy2(under(args.rollback.parent, 'originals/' + row['path']), dst)
            else:
                dst.unlink()
            assert digest(dst) == row['original_sha256']
        print('Restored deployment files; retained receipt, evidence, and backups.')
        return
    sources = {relative: under(SOURCE, relative) for relative in files}
    if args.extractor_jar:
        with ZipFile(args.extractor_jar) as archive:
            package = 'greta/auxiliary/MeaningMiner/'
            required = {package + name for name in ('ImageSchemaExtractor.class', 'SentenceMarkerOffsets.class')}
            if not required.issubset(archive.namelist()):
                raise ValueError('Build the MeaningMiner extractor with utterance-wide marker support first')
        if args.extractor_jar.resolve() == under(runtime, extractor).resolve():
            raise ValueError('The build artifact must be separate from the deployment target')
        sources[extractor] = args.extractor_jar.resolve()
    rows = []
    for relative, src in sources.items():
        dst = under(runtime, relative)
        if not src.is_file() or dst.is_dir():
            raise ValueError('Invalid deployment file: ' + relative)
        rows.append({'path': relative, 'original_sha256': digest(dst), 'deployed_sha256': digest(src)})
    if not args.apply:
        print(json.dumps({'mode': 'plan', 'files': rows}, indent=2))
        return
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    backup_root = runtime / 'bin/GMIT/local' / ('deploy-' + stamp)
    backup_root.mkdir(parents=True, exist_ok=False)
    # Persist all original bytes and the receipt before the first replacement.
    for row in rows:
        if row['original_sha256']:
            backup = under(backup_root, 'originals/' + row['path'])
            backup.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(under(runtime, row['path']), backup)
            assert digest(backup) == row['original_sha256']
    receipt_path = backup_root / 'receipt.json'
    receipt_path.write_text(json.dumps({'schema_version': 1, 'runtime': str(runtime), 'files': rows}, indent=2) + '\n')
    for row in rows:
        dst = under(runtime, row['path'])
        if digest(dst) != row['original_sha256']:
            raise ValueError('Target changed during deployment: ' + row['path'])
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(sources[row['path']], dst)
        assert digest(dst) == row['deployed_sha256']
    print('Deployed and verified ' + str(len(rows)) + ' files.')
    print('Receipt: ' + str(receipt_path))


if __name__ == '__main__':
    main()
