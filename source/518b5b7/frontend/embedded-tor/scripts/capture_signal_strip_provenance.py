#!/usr/bin/env python3
"""Capture candidate native-strip evidence using an ALREADY INSTALLED Android NDK.

Never downloads/installs an SDK or accepts a license. This report is evidence for
review, not an automatically approved packaging manifest or an APK release gate.
"""
import argparse
import hashlib
import json
import mmap
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile
from verify_embedded_tor import SIGNAL_AAR_SHA256, SIGNAL_NATIVE, entry_digest, signal_notice

NDK_VERSION = '27.3.13750724'
ABI_ELF = {'arm64-v8a':(2,183), 'armeabi-v7a':(1,40), 'x86':(1,3), 'x86_64':(2,62)}


def file_hash(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''): digest.update(chunk)
    return digest.hexdigest()


def loaded_sections(path, abi):
    """Hash all SHF_ALLOC sections; preserve addresses/types/flags/alignment and NOBITS sizes.

    Debug stripping may change ELF/section headers and nonloaded sections. It may
    not change any mapped code/data, dynamic symbols/strings, relocations or BSS.
    """
    result = {}
    with Path(path).open('rb') as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as elf:
        assert elf[:4] == b'\x7fELF' and len(elf) >= 64, 'invalid ELF'
        elf_class, machine = ABI_ELF[abi]
        assert elf[4] == elf_class and elf[5] == 1 and elf[6] == 1, 'unexpected ELF class/endianness'
        assert struct.unpack_from('<H',elf,16)[0] == 3, 'expected ET_DYN shared library'
        assert struct.unpack_from('<H',elf,18)[0] == machine, 'unexpected ELF architecture'
        if elf_class == 2:
            offset = struct.unpack_from('<Q',elf,40)[0]
            entry_size,count,names_index = struct.unpack_from('<HHH',elf,58)
            fmt = '<IIQQQQIIQQ'
        else:
            offset = struct.unpack_from('<I',elf,32)[0]
            entry_size,count,names_index = struct.unpack_from('<HHH',elf,46)
            fmt = '<IIIIIIIIII'
        assert entry_size == struct.calcsize(fmt) and 0 < count < 65535 and 0 < names_index < count, 'unexpected section table'
        assert offset + count * entry_size <= len(elf), 'section table exceeds file'
        sections = [struct.unpack_from(fmt,elf,offset + index * entry_size) for index in range(count)]
        name_offset,name_size = sections[names_index][4:6]
        assert name_offset + name_size <= len(elf), 'invalid section names'
        names = elf[name_offset:name_offset+name_size]
        for section in sections:
            name_index,kind,flags,address,start,size,link,info,align,entsize = section
            if not flags & 2: continue
            assert name_index < len(names), 'invalid allocated section name'
            end = names.find(b'\0',name_index); assert end >= 0
            name = names[name_index:end].decode('ascii')
            assert name and name not in result, 'duplicate allocated section'
            entry = {'type':kind,'flags':flags,'address':address,'size':size,'alignment':align,'entry_size':entsize}
            if kind != 8:  # SHT_NOBITS occupies memory, not file bytes.
                assert start + size <= len(elf), 'allocated section exceeds file'
                entry['sha256'] = hashlib.sha256(elf[start:start+size]).hexdigest()
            result[name] = entry
        for required in ['.text','.dynsym','.dynstr','.dynamic']:
            assert required in result, 'missing loaded section: ' + required
    return result


def program_headers(path):
    """Preserve loader segment addresses, offsets, sizes, permissions and alignment exactly."""
    with Path(path).open('rb') as stream:
        header = stream.read(64); assert len(header) == 64 and header[:4] == b'\x7fELF' and header[5] == 1
        if header[4] == 2:
            offset = struct.unpack_from('<Q',header,32)[0]
            size,count = struct.unpack_from('<HH',header,54)
            assert size == 56
        else:
            assert header[4] == 1
            offset = struct.unpack_from('<I',header,28)[0]
            size,count = struct.unpack_from('<HH',header,42)
            assert size == 32
        assert 0 < count <= 128 and offset + size * count <= Path(path).stat().st_size, 'invalid program headers'
        stream.seek(offset); return stream.read(size*count)


def capture(aar, ndk, report, source_commit):
    aar,ndk,report = Path(aar).resolve(),Path(ndk).resolve(),Path(report).resolve()
    assert source_commit and re.fullmatch('[0-9a-f]{40}', source_commit), 'explicit checked-out commit required'
    assert file_hash(aar) == SIGNAL_AAR_SHA256, 'official Signal AAR digest mismatch'
    properties = ndk / 'source.properties'
    assert properties.is_file(), 'preinstalled NDK missing; no automatic install permitted'
    match = re.search(r'^Pkg\.Revision\s*=\s*(\S+)\s*$',properties.read_text(),re.M)
    assert match and match.group(1) == NDK_VERSION, 'unexpected preinstalled NDK version'
    tool = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'
    assert tool.is_file() and os.access(tool,os.X_OK), 'preinstalled Linux NDK llvm-strip missing'
    assert tool.resolve().is_relative_to(ndk), 'NDK tool resolves outside its reviewed installation'
    version = subprocess.run([str(tool),'--version'],check=True,capture_output=True,text=True,timeout=10).stdout.strip()
    assert 'LLVM' in version and len(version) <= 4096, 'unexpected strip tool version output'
    evidence = {
        'schema':1,'status':'candidate_evidence_requires_review_before_packaging',
        'source_commit':source_commit,'libsignal_version':'0.105.0','source_aar_sha256':SIGNAL_AAR_SHA256,
        'ndk_version':NDK_VERSION,'ndk_source_properties_sha256':file_hash(properties),
        'strip_tool_relative_path':str(tool.relative_to(ndk)),
        'strip_tool_resolved_relative_path':str(tool.resolve().relative_to(ndk)),
        'strip_tool_sha256':file_hash(tool),'strip_tool_version':version,
        'invocation':['llvm-strip','--strip-unneeded','-o','<output>','<pinned-source>'],
        'runner_image':os.environ.get('ImageVersion','unknown'),'abis':{},
    }
    with zipfile.ZipFile(aar) as archive, tempfile.TemporaryDirectory(prefix='tortalk-signal-strip-') as temporary:
        names = archive.namelist(); assert len(names) == len(set(names)), 'duplicate AAR entries'
        signal_notice(archive)
        expected = {f'jni/{abi}/{name}.so' for abi in SIGNAL_NATIVE for name in ['libsignal_jni','libsignal_jni_testing']}
        assert {n for n in names if n.endswith('.so')} == expected, 'unexpected source JNI inventory'
        for abi, source_hash in SIGNAL_NATIVE.items():
            entry = f'jni/{abi}/libsignal_jni.so'
            assert entry_digest(archive,entry) == source_hash, 'pinned source JNI mismatch: ' + abi
            source = Path(temporary) / (abi + '-source.so'); output = Path(temporary) / (abi + '-stripped.so')
            with archive.open(entry) as src, source.open('wb') as dst: shutil.copyfileobj(src,dst,1024*1024)
            before = loaded_sections(source,abi); before_headers = program_headers(source)
            subprocess.run([str(tool),'--strip-unneeded','-o',str(output),str(source)],check=True,capture_output=True,timeout=60)
            after = loaded_sections(output,abi)
            assert before == after, 'stripping changed loaded ELF content: ' + abi
            assert before_headers == program_headers(output), 'stripping changed loader program headers: ' + abi
            assert 0 < output.stat().st_size < source.stat().st_size, 'strip did not reduce native size'
            # A second identical invocation must reproduce exact output bytes, independent of path.
            second = Path(temporary) / (abi + '-repeat.so')
            subprocess.run([str(tool),'--strip-unneeded','-o',str(second),str(source)],check=True,capture_output=True,timeout=60)
            output_hash = file_hash(output); assert output_hash == file_hash(second), 'nondeterministic strip output'
            evidence['abis'][abi] = {'source_sha256':source_hash,'source_bytes':source.stat().st_size,
                'stripped_sha256':output_hash,'stripped_bytes':output.stat().st_size,
                'allocated_sections_unchanged':True,'allocated_sections':before,'program_headers_unchanged':True,
                'program_headers_sha256':hashlib.sha256(before_headers).hexdigest(),'repeat_identical':True}
    evidence['source_total_bytes'] = sum(item['source_bytes'] for item in evidence['abis'].values())
    evidence['stripped_total_bytes'] = sum(item['stripped_bytes'] for item in evidence['abis'].values())
    report.parent.mkdir(parents=True,exist_ok=True)
    report.write_text(json.dumps(evidence,indent=2,sort_keys=True)+'\n')
    print('CAPTURED candidate evidence:',report.name)
    print('Signal JNI bytes:',evidence['source_total_bytes'],'->',evidence['stripped_total_bytes'])
    print('All four ABIs retain identical allocated ELF content; repeated strip outputs match.')
    print('NOT APPROVED FOR PACKAGING until tool/output hashes are reviewed and pinned. No APK built.')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--aar',type=Path,required=True)
    p.add_argument('--ndk',type=Path,required=True,help='Existing installation; this script never installs it')
    p.add_argument('--report',type=Path,required=True)
    p.add_argument('--source-commit',required=True)
    a = p.parse_args(); capture(a.aar,a.ndk,a.report,a.source_commit)

if __name__ == '__main__': main()
