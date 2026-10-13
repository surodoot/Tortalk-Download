#!/usr/bin/env python3
"""Pure parser/invariant tests. No NDK installation or real native execution."""
from pathlib import Path
import struct
import tempfile
import unittest
from capture_signal_strip_provenance import loaded_sections, program_headers


def fixture():
    elf = bytearray(1024)
    elf[:16] = b'\x7fELF\x02\x01\x01' + bytes(9)
    struct.pack_into('<HHI',elf,16,3,62,1)
    struct.pack_into('<Q',elf,32,64)
    struct.pack_into('<HH',elf,54,56,1)
    struct.pack_into('<IIQQQQQQ',elf,64,1,5,0,4096,4096,200,200,16384)
    struct.pack_into('<Q',elf,40,512)
    struct.pack_into('<HHH',elf,58,64,7,6)
    names = b'\0.text\0.dynsym\0.dynstr\0.dynamic\0.bss\0.shstrtab\0'
    elf[256:256+len(names)] = names
    definitions = [('',0,0,0,0,0,0,0,0,0),
                   ('.text',1,6,4096,128,16,0,0,16,0),
                   ('.dynsym',11,2,4112,144,24,3,0,8,24),
                   ('.dynstr',3,2,4136,168,16,0,0,1,0),
                   ('.dynamic',6,3,4152,184,16,3,0,8,16),
                   ('.bss',8,3,8192,200,128,0,0,16,0),
                   ('.shstrtab',3,0,0,256,len(names),0,0,1,0)]
    for index,(name,*fields) in enumerate(definitions):
        name_index = 0 if not name else names.index(name.encode()+b'\0')
        struct.pack_into('<IIQQQQIIQQ',elf,512+64*index,name_index,*fields)
    elf[128:144] = bytes(range(16))
    elf[168:184] = b'JNI_OnLoad\0' + bytes(5)
    return elf


class ElfInvariantTests(unittest.TestCase):
    def inspect(self, body, abi='x86_64'):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'synthetic.so'; path.write_bytes(body); return loaded_sections(path,abi)
    def test_program_header_alignment_is_recorded(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'synthetic.so'; before=fixture(); path.write_bytes(before); original=program_headers(path)
            after=bytearray(before); struct.pack_into('<Q',after,64+48,4096); path.write_bytes(after)
            self.assertNotEqual(original,program_headers(path))
    def test_loaded_sections_and_bss(self):
        sections = self.inspect(fixture()); self.assertEqual(set(sections),{'.text','.dynsym','.dynstr','.dynamic','.bss'})
        self.assertEqual(sections['.bss']['size'],128); self.assertNotIn('sha256',sections['.bss'])
    def test_nonloaded_padding_may_change(self):
        before = fixture(); after = bytearray(before); after[400] = 3
        self.assertEqual(self.inspect(before),self.inspect(after))
    def test_mapped_code_change_detected(self):
        before = fixture(); after = bytearray(before); after[129] ^= 1
        self.assertNotEqual(self.inspect(before),self.inspect(after))
    def test_mapped_metadata_change_detected(self):
        before = fixture(); after = bytearray(before); struct.pack_into('<Q',after,512+64+16,4097)
        self.assertNotEqual(self.inspect(before),self.inspect(after))
    def test_dynamic_symbols_change_detected(self):
        before = fixture(); after = bytearray(before); after[145] ^= 1
        self.assertNotEqual(self.inspect(before),self.inspect(after))
    def test_wrong_architecture_rejected(self):
        with self.assertRaises(AssertionError): self.inspect(fixture(),'arm64-v8a')
    def test_bad_magic_rejected(self):
        body = fixture(); body[0] = 0
        with self.assertRaises(AssertionError): self.inspect(body)
    def test_bad_section_table_rejected(self):
        body = fixture(); struct.pack_into('<Q',body,40,4096)
        with self.assertRaises(AssertionError): self.inspect(body)
    def test_missing_dynamic_section_rejected(self):
        body = fixture(); struct.pack_into('<Q',body,512+64*4+8,0)
        with self.assertRaises(AssertionError): self.inspect(body)
    def test_oversized_loaded_section_rejected(self):
        body = fixture(); struct.pack_into('<Q',body,512+64+32,100000)
        with self.assertRaises(AssertionError): self.inspect(body)


if __name__ == '__main__': unittest.main()
