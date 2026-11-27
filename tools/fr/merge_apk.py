#!/usr/bin/env python3
"""Build the French APK: the Java code and resources of this repository's -PjavaOnly build,
plus the native libraries and assets of the official release APK of the same version.

usage: merge_apk.py built.apk official.apk out.apk   (then zipalign and apksigner)"""
import shutil, sys, zipfile

built, official, out = sys.argv[1:4]
SIG = ('.SF', '.RSA', '.DSA', '.EC')

def signature(n):
    return n.startswith('META-INF/') and (n.endswith(SIG) or n == 'META-INF/MANIFEST.MF')

def copy(src, info, dst):
    zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
    zi.compress_type = info.compress_type
    zi.external_attr = info.external_attr
    with src.open(info) as r, dst.open(zi, 'w', force_zip64=True) as w:
        shutil.copyfileobj(r, w, 1 << 20)

B, O = zipfile.ZipFile(built), zipfile.ZipFile(official)
mine = set(B.namelist())
taken = 0
with zipfile.ZipFile(out, 'w', allowZip64=True) as Z:
    for i in B.infolist():
        if not signature(i.filename):
            copy(B, i, Z)
    for i in O.infolist():
        n = i.filename
        if n in mine or signature(n):
            continue
        if (n.startswith('classes') and n.endswith('.dex')) or n.startswith('res/') \
                or n in ('resources.arsc', 'AndroidManifest.xml'):
            continue
        copy(O, i, Z)
        taken += 1
libs = [n for n in O.namelist() if n.startswith('lib/')]
if not any(n.endswith('libff7recomp.so') or n.endswith('.so') for n in libs):
    sys.exit('the official APK has no native libraries')
print('merged: %d entries from the build, %d from the official APK (%d native libraries)'
      % (len(mine), taken, len(libs)))
