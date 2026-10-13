#!/usr/bin/env python3
"""Source/packaging checks only. Never assert native/device bootstrap from these checks."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
TOOLS = '{http://schemas.android.com/tools}'
AAR_SHA256 = '40be745450a8ecfaac8558950bf046f596d7ef6e88107f69334a8c4b6e7d14be'
TOR_NATIVE = {
    'arm64-v8a': '25fb2bb6b7f23db1f7f56eb8444cae365ccdfe75dfa803f72c6bbaad03db63a2',
    'armeabi-v7a': '143bc62f94c64ec87f5bb841f34faa19607c40d6ab1d6aba29ea6e21cf781736',
    'x86': '06574596f6d9397631040de67266364e154d4b03999ae3db6295713d97fa799d',
    'x86_64': '32c440cf42052b2126e49a936399f8eeef64a93ea07451df67cc057eaa145b0e',
}
def manifest(path, merged=False):
    root = ET.parse(path).getroot()
    permission_nodes = [x for x in root if x.tag.startswith('uses-permission')]
    assert all(x.tag == 'uses-permission' for x in permission_nodes), 'conditional permission added'
    permissions = {x.get(ANDROID + 'name') for x in permission_nodes}
    assert permissions == {'android.permission.INTERNET'}, permissions
    app = root.find('application')
    assert app is not None
    assert app.get(ANDROID + 'process') is None, 'application must retain its main process'
    assert app.get(ANDROID + 'allowBackup') == 'false'
    assert app.get(ANDROID + 'fullBackupContent') in {'false', '0', '0x0'}
    assert app.get(ANDROID + 'dataExtractionRules') is not None
    assert app.get(ANDROID + 'usesCleartextTraffic') == 'false'
    services = app.findall('service')
    if not merged:
        removed = [x for x in services if x.get(ANDROID + 'name') == 'org.torproject.jni.TorService']
        assert len(removed) == 1 and removed[0].attrib == {ANDROID + 'name':'org.torproject.jni.TorService', TOOLS + 'node':'remove'}
        services = [x for x in services if x is not removed[0]]
    assert len(services) == 1
    assert services[0].get(ANDROID + 'name') in {'.TorProcessService','dev.gyeopdam.embeddedtor.TorProcessService'}
    assert services[0].get(ANDROID + 'exported') == 'false'
    assert services[0].get(ANDROID + 'process') == ':tor'
    assert services[0].get(ANDROID + 'stopWithTask') == 'true'
    assert services[0].get(ANDROID + 'permission') is None
    assert services[0].get(ANDROID + 'isolatedProcess') in {None,'false'}
    assert services[0].get(ANDROID + 'externalService') in {None,'false'}
    assert not list(services[0]), 'service intent ingress added'
    assert not root.findall('.//action[@' + ANDROID + 'name="android.net.VpnService"]')
    assert not app.findall('provider') and not app.findall('receiver')
    activities = {x.get(ANDROID + 'name').split('.')[-1]: x for x in app.findall('activity')}
    assert set(activities) == {'TorTalkActivity', 'TorTestActivity'}
    assert all(x.get(ANDROID + 'process') is None for x in activities.values()), 'UI must never share the killable Tor process'
    assert activities['TorTalkActivity'].get(ANDROID + 'exported') == 'true'
    assert activities['TorTestActivity'].get(ANDROID + 'exported') == 'false'
    assert not app.findall('activity-alias')
    filters = activities['TorTalkActivity'].findall('intent-filter')
    assert len(filters) == 1 and not activities['TorTestActivity'].findall('intent-filter')
    assert {x.get(ANDROID + 'name') for x in filters[0].findall('action')} == {'android.intent.action.MAIN'}
    assert {x.get(ANDROID + 'name') for x in filters[0].findall('category')} == {'android.intent.category.LAUNCHER'}
    assert not filters[0].findall('data'), 'launcher deep-link ingress added'
    if merged:
        assert app.get(ANDROID + 'debuggable') == 'true'
        assert root.get('package') == 'dev.gyeopdam.embeddedtor.experiment'

SIGNAL_AAR_SHA256 = 'a3977f6563d82334f6a67ca9eaf42a7e2192bb5d06b539fe089dfc9a9502c6f8'
SIGNAL_NOTICE_PATH = 'assets/acknowledgments/libsignal.md'
SIGNAL_NOTICE_SHA256 = '3e2148370a799680508e4a372f4258611b7f5f9ac9965c5504756e78ec2d72e5'
SIGNAL_NATIVE = {
    'arm64-v8a': 'bd935a398d69ddd988c2ae58e003b17f7719df1d8cc345f38847f2862f6ba9f3',
    'armeabi-v7a': '28bfcfd3fa86eb46cf85b3dbe17fc5d1a05b6575d6873e8bd8b61cfe67ab357f',
    'x86': '2c6ab0bf009ed2b9af42cd7247c592226274dcfef20232d78228b43d4df5c92d',
    'x86_64': '4518b37e9224ef09daa6a625449eccf75f42f1ba36ffb2bec4744dd3b2ecd70d',
}
SIGNAL_PACKAGED_NATIVE = {
    'arm64-v8a': 'aae5991d40b4c583271afe37ac1f58c7a5492544bf5bbfdfb1921c678d8dfc81',
    'armeabi-v7a': 'c891c327b6286b184d974bdca5126495d2ce73cd1cf35cd57e6e9dc28cc67080',
    'x86': 'a09dddaa1946edb555779cd42690be902e520de49ffd4da3c65f1708b2e76281',
    'x86_64': '05b921e29c5398285dc46c942ac552cc2ee580c614a3090590366b56e1151ee6',
}
SIGNAL_STRIP_NDK_VERSION = '27.3.13750724'
SIGNAL_STRIP_TOOL_SHA256 = 'b811889ca41db2f6242a6498833e3e1705faf099bb382313f8feb2b070c79e9d'
SIGNAL_STRIP_PROPERTIES_SHA256 = 'fb5eec1ca5dac09442109cf79e9ce49faecd8b312dbc997e88903a394e14994a'
SIGNAL_STRIP_REPORT_SHA256 = '75ad5932c19a489ab4e916d5f04d4a565b6d6b6ca02f34a46a6e3c1f08d3cb9e'
SIGNAL_STRIP_REPORT = ROOT / 'scripts/provenance/signal-strip-0.105.0-ndk27.3.13750724.json'


def strip_provenance(raw):
    """Retain the exact reviewed report from successful GitHub run 37830208166."""
    assert hashlib.sha256(raw).hexdigest() == SIGNAL_STRIP_REPORT_SHA256, 'Signal strip provenance report changed'
    report = json.loads(raw)
    assert report['source_commit'] == '0be94580bab4b9f48cd16053799fe5799517389a'
    assert report['schema'] == 1 and report['libsignal_version'] == '0.105.0'
    assert report['source_aar_sha256'] == SIGNAL_AAR_SHA256
    assert report['ndk_version'] == SIGNAL_STRIP_NDK_VERSION
    assert report['strip_tool_sha256'] == SIGNAL_STRIP_TOOL_SHA256
    assert report['ndk_source_properties_sha256'] == SIGNAL_STRIP_PROPERTIES_SHA256
    assert report['invocation'] == ['llvm-strip','--strip-unneeded','-o','<output>','<pinned-source>']
    assert set(report['abis']) == set(SIGNAL_NATIVE) == set(SIGNAL_PACKAGED_NATIVE)
    for abi, item in report['abis'].items():
        assert item['source_sha256'] == SIGNAL_NATIVE[abi]
        assert item['stripped_sha256'] == SIGNAL_PACKAGED_NATIVE[abi]
        assert item['allocated_sections_unchanged'] and item['program_headers_unchanged'] and item['repeat_identical']
    assert sum(item['stripped_bytes'] for item in report['abis'].values()) == report['stripped_total_bytes'] == 37839608

def entry_digest(archive, name):
    digest = hashlib.sha256()
    with archive.open(name) as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''): digest.update(chunk)
    return digest.hexdigest()

def signal_notice(archive):
    assert archive.namelist().count(SIGNAL_NOTICE_PATH) == 1, 'missing or duplicated complete upstream Signal notice'
    assert entry_digest(archive, SIGNAL_NOTICE_PATH) == SIGNAL_NOTICE_SHA256, 'upstream Signal notice was changed'


def native_hashes(path, prefix, libraries, signal_source=False):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), 'duplicate archive paths'
        expected = {f'{prefix}/{abi}/{library}' for library, hashes in libraries.items() for abi in hashes}
        # Official Signal's pinned source AAR also carries test JNI. Never allow it in an APK.
        allowed = expected | ({f'jni/{abi}/libsignal_jni_testing.so' for abi in SIGNAL_NATIVE} if signal_source else set())
        natives = {n for n in names if n.endswith('.so')}
        assert natives == allowed, ('unexpected native inventory', sorted(natives ^ allowed))
        assert not any(n.endswith(('.dll', '.dylib')) for n in names), 'desktop native artifact'
        for library, hashes in libraries.items():
            for abi, digest in hashes.items():
                assert entry_digest(archive, f'{prefix}/{abi}/{library}') == digest, (library, abi)

MAX_APK_BYTES = 160 * 1024 * 1024
MAX_NATIVE_PAYLOAD_BYTES = 128 * 1024 * 1024


def apk_budget(file_bytes, entries):
    assert 0 < file_bytes <= MAX_APK_BYTES, 'APK exceeds reviewed 160 MiB artifact budget'
    native_bytes = sum(entry.file_size for entry in entries if entry.filename.endswith('.so'))
    assert 0 < native_bytes <= MAX_NATIVE_PAYLOAD_BYTES, 'native payload exceeds reviewed 128 MiB budget; verified stripping required'


def unsigned_apk(path):
    with zipfile.ZipFile(path) as archive:
        apk_budget(path.stat().st_size, archive.infolist())
        assert not any(re.match(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)$', n, re.I) for n in archive.namelist()), 'v1 signature found'
        assert 'assets/guardianproject-tor-android-LICENSE.txt' in archive.namelist()
        assert 'assets/netty-LICENSE.txt' in archive.namelist()
        assert 'assets/netty-NOTICE.txt' in archive.namelist()
        assert 'assets/libsignal-LICENSE.txt' in archive.namelist()
        signal_notice(archive)
        assert not any('jni_testing' in n for n in archive.namelist()), 'test-only libsignal artifact'
        for name in ['ui/index.html', 'ui/styles.css', 'ui/native.css', 'ui/app.js', 'libsignal-LICENSE.txt', 'libsignal-NOTICE.md', 'zxing-LICENSE.txt']:
            assert archive.read('assets/' + name) == (ROOT / 'src/main/assets' / name).read_bytes(), ('packaged asset differs', name)
    with path.open('rb') as stream:
        size = path.stat().st_size
        stream.seek(max(0, size - 65557)); tail = stream.read()
        eocd = tail.rfind(b'PK\x05\x06')
        assert eocd >= 0 and eocd + 22 <= len(tail)
        assert eocd + 22 + struct.unpack_from('<H', tail, eocd + 20)[0] == len(tail), 'invalid ZIP end record'
        central_offset = struct.unpack_from('<I', tail, eocd + 16)[0]
        assert 16 <= central_offset < size
        stream.seek(central_offset - 16)
        assert stream.read(16) != b'APK Sig Block 42', 'APK signing block found'

# These are deliberately reviewed boundaries, not a Java/JS security proof.
SOURCE_FILES = set('''DiagnosticLog FixedOnionProbe PendingAccountSchema TorTalkActivity AppSessionStore
SignalProfile OnionProbeSession TorTestActivity MessageCoordinator TorLifecyclePolicy TorTalkState
SignalClient UiCommandPolicy SessionSchema TorApiClient TorReadinessGate EmbeddedTorController JsonBounds
ServerConfigStore PendingAccountStore SignalJson EncryptedBlobEnvelope OnionApiRequest KeystoreBlobStore
OnionApiTransport InvitationInput NativeResponseRules DraftLedger HistoryWindow CoordinatorFailures TorTalkInsets TorTalkInsetsApi30 AccountWorkQueue
TorProcessService TorProcessProtocol TorProcessPolicy ContactCard ContactQrCodec ContactQrImage
PeerBlockState PeerBlockJournalSchema PeerBlockJournal PeerBlockCoordinator
PendingDeviceRecoverySchema PendingDeviceRecoveryStore DeviceRecoveryCoordinator
TorSocketEndpoint TorSocketPaths TorSocketIo AndroidTorSocket TorSocketChannel TorSocketEventLoopGroup'''.split())


def method_body(source, name, parameters=None):
    """Balanced Java method extraction, ignoring braces inside literals/comments."""
    arguments = '[^)]*' if parameters is None else re.escape(parameters)
    match = re.search(r'\b(?:(?:private|public|protected)\s+)?(?:static\s+)?(?:synchronized\s+)?[\w<>\[\]]+\s+' + re.escape(name) + r'\(' + arguments + r'\)\s*(?:throws\s+[\w.,\s]+)?\{', source)
    assert match, ('missing reviewed method', name)
    start = match.end(); depth = 1; i = start
    while i < len(source):
        if source.startswith('//', i):
            end = source.find('\n', i + 2); i = len(source) if end < 0 else end; continue
        if source.startswith('/*', i):
            end = source.find('*/', i + 2); assert end >= 0; i = end + 2; continue
        if source[i] in '\"\'':
            quote = source[i]; i += 1
            while i < len(source):
                if source[i] == '\\': i += 2; continue
                if source[i] == quote: i += 1; break
                i += 1
            continue
        if source[i] == '{': depth += 1
        if source[i] == '}':
            depth -= 1
            if depth == 0: return source[start:i]
        i += 1
    raise AssertionError(('unbalanced method', name))


def without_comments(source):
    # Preserve quoted URL strings while dropping explanatory comments.
    token = re.compile(r'("(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\')|/\*.*?\*/|//[^\n]*', re.S)
    return token.sub(lambda match: match.group(1) or " ", source)


def storage_boundaries(sources):
    storage = sources['KeystoreBlobStore']
    compact = lambda name: re.sub(r'\s+', '', without_comments(method_body(storage,name)))
    write = compact('writeLocked')
    # The original state can be rolled back only before attempting AtomicFile commit.
    sequence = ['EncryptedBlobEnvelope.encrypt(key,domain,plaintext,limit)', 'output=file.startWrite();',
                'output.write(envelope);', 'output.getFD().sync();', 'commitAttempted=true;',
                'file.finishWrite(output);', 'output=null;', 'byte[]committed=readEnvelope(file);',
                'if(committed==null||!Arrays.equals(envelope,committed))throwfailure(Code.IO_FAILED);',
                'syncDirectory(file.getBaseFile().getParentFile());']
    position = -1
    for step in sequence:
        found = write.find(step,position+1)
        assert found > position, ('durable encrypted write ordering',step)
        position = found
    assert 'booleancommitAttempted=false;' in write
    assert write.count('file.finishWrite(') == 1 and write.count('file.failWrite(') == 1
    assert 'if(output!=null){if(!commitAttempted){try{file.failWrite(output);}' in write
    assert 'else{try{output.close();}' in write
    assert write.endswith('throwfailure(Code.IO_FAILED);}')
    for forbidden in ['file.delete(', '.clear(', 'output.write(plaintext)', 'commitAttempted=false;', 'returnnull;']:
        if forbidden == 'commitAttempted=false;':
            assert write.count(forbidden) == 1
        else: assert forbidden not in write, ('uncertain write reset/escape',forbidden)
    read = compact('readLocked')
    sequence = ['byte[]envelope=readEnvelope(file);','if(envelope==null)returnnull;',
                'syncDirectory(file.getBaseFile().getParentFile());','SecretKeyexisting=key(false);',
                'returnEncryptedBlobEnvelope.decrypt(existing,domain,envelope,limit);']
    position = -1
    for step in sequence:
        found = read.find(step,position+1); assert found > position, ('recovered read release ordering',step); position = found
    for forbidden in ['key(true)', 'writeLocked(', 'file.delete(', '.clear(']: assert forbidden not in read
    raw = compact('readEnvelope')
    assert 'file.openRead()' in raw and 'total>limit+EncryptedBlobEnvelope.OVERHEAD_BYTES' in raw
    assert 0 <= raw.find('input.getFD().sync();') < raw.find('returnbytes.toByteArray();'), 'recovered file data barrier missing or late'
    assert 'base.exists()||newFile(base.getPath()+".bak").exists()||newFile(base.getPath()+".new").exists()' in raw
    assert 'catch(StorageExceptionexpected){throwexpected;}' in raw
    directory = compact('syncDirectory')
    sequence = ['Os.open(directory.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW,0)',
                'OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)', 'Os.fsync(descriptor);',
                'finally{', 'Os.close(descriptor);', 'if(failed)throwfailure(Code.IO_FAILED);']
    position = -1
    for step in sequence:
        found = directory.find(step,position+1); assert found > position, ('directory durability ordering',step); position = found
    assert 'catch(ErrnoException|RuntimeExceptionignored){failed=true;}' in directory
    assert directory.count('failed=true;') >= 3
    locking = compact('locked')
    assert 'context.getNoBackupFilesDir()' in locking and 'synchronized(PROCESS_LOCK)' in locking
    assert 'FileLocklock=channel.lock()' in locking and 'if(!lock.isValid())throwfailure(Code.IO_FAILED);' in locking
    assert 'returnoperation.run(newAtomicFile(base));' in locking
    full = re.sub(r'\s+', '', without_comments(storage))
    assert 'validator.validate(previous);' in full
    assert full.index('validator.validate(previous);') < full.index('writeLocked(file,snapshot);')
    assert 'storage.compareAndSave(s.original,s.encode());' in re.sub(r'\s+', '', sources['SignalClient'])
    coordinator = re.sub(r'\s+', '', sources['MessageCoordinator'])
    assert 'blob.write(next,previous->{if(!Arrays.equals(expected,previous))thrownewKeystoreBlobStore.StorageException(KeystoreBlobStore.Code.IDENTITY_MISMATCH);});' in coordinator


def process_boundaries(sources):
    """Reviewed app-private process lifetime; host checks do not prove physical termination."""
    compact = lambda value: re.sub(r'\s+', '', without_comments(value))
    controller = compact(sources['EmbeddedTorController'])
    service = compact(sources['TorProcessService'])
    protocol = compact(sources['TorProcessProtocol'])
    policy = compact(sources['TorProcessPolicy'])
    for name, raw in sources.items():
        text = without_comments(raw)
        if name != 'TorProcessService':
            for forbidden in ['org.torproject.jni.', 'net.freehaven.tor.control.', 'getTorControlConnection(', 'killProcess(']:
                assert forbidden not in text, ('native process API escaped wrapper',name,forbidden)
        if name != 'EmbeddedTorController':
            assert 'startService(' not in text, ('service start escaped foreground controller',name)
        for forbidden in ['BIND_AUTO_CREATE','startForegroundService(', 'Runtime.getRuntime()', 'System.exit(', 'sendSignal(']:
            assert forbidden not in text, ('unreviewed process lifecycle API',name,forbidden)

    for required in ['booleancanStart(){returnpolicy.canStart()&&(active==null||active.dead);}',
                     'requireMainThread();if(!canStart()||!policy.start())return;',
                     'ComponentNamecomponent=context.startService(serviceIntent());',
                     'attempt.bound=context.bindService(serviceIntent(),attempt,0);',
                     'newIntent(context,TorProcessService.class)',
                     '!TorProcessService.class.getName().equals(name.getClassName())',
                     'endpoint.linkToDeath(death,0);linked=true;',
                     'if(binder!=null&&binder!=endpoint){beginStop(true);return;}',
                     'if(active!=attempt||attempt.dead||attempt.deathObserved||message.sendingUid!=Process.myUid()||!TorProcessProtocol.validReply(message))return;',
                     'if(data.getLong("epoch")!=attempt.epoch)return;',
                     'if(!requested||attempt.stopping||sequence!=attempt.pendingSequence)return;',
                     'if(sampledAt<attempt.pendingAt||sampledAt>now||now-sampledAt>2_000){beginStop(true);return;}',
                     'requested=false;policy.stop();if(failed)gate.fail(readinessEpoch);elsegate.stop();probe.cancel();cancelRequests();',
                     'nativeState=NativeStopState.TERMINATION_UNCONFIRMED;changed();']:
        assert required in controller, ('main Tor incarnation boundary',required)
    assert controller.count('context.startService(') == 1 and controller.count('context.bindService(') == 1
    for required in ['privatevolatileAttemptactive;', 'volatileIBinderbinder;', 'volatilebooleandeathObserved;',
                     'finallonggateEpoch=readinessEpoch;',
                     'deathObserved=true;gate.fail(gateEpoch);main.post(()->died(this));',
                     'returnrequested&&attempt!=null&&!attempt.deathObserved&&endpoint!=null&&endpoint.isBinderAlive()&&gate.isCurrent(lease,SystemClock.elapsedRealtime());']:
        assert required in controller, ('worker-visible exact Binder death revocation',required)
    died = compact(method_body(sources['EmbeddedTorController'],'died'))
    assert 'requireMainThread();if(active!=attempt||attempt.dead)return;' in died
    assert 'if(attempt.binder==null)return;attempt.deathObserved=true;attempt.dead=true;' in died
    assert 'nativeState=NativeStopState.TERMINATED_CONFIRMED;' in died
    assert controller.count('nativeState=NativeStopState.TERMINATED_CONFIRMED;') == 1
    assert controller.count('attempt.dead=true;') == 1
    stopped = compact(method_body(sources['EmbeddedTorController'],'receive'))
    assert 'if(!attempt.stopping||sequence!=attempt.stopSequence)return;' in stopped
    assert 'if(acknowledgedAt<attempt.stopSentAt||acknowledgedAt>SystemClock.elapsedRealtime())return;' in stopped

    for required in ['publicfinalclassTorProcessServiceextendsTorService',
                     'watchdog=Executors.newSingleThreadScheduledExecutor(',
                     'watchdog.scheduleWithFixedDelay(this::watchDeadlines,0,50,TimeUnit.MILLISECONDS);',
                     'controlWorker=Executors.newSingleThreadExecutor(',
                     'if(message.sendingUid!=Process.myUid())returntrue;',
                     'owner.getBinder().linkToDeath(ownerDied,0);',
                     '!currentOwner.getBinder().equals(request.owner.getBinder())',
                     'current.claim(request.epoch,request.seq,request.sentAt,now)',
                     'current.renew(request.epoch,request.seq,request.sentAt,now)',
                     'current.acceptStop(request.epoch,request.seq,request.sentAt,now)',
                     'current.beginCommand(request.seq,SystemClock.elapsedRealtime())',
                     'controlWorker.execute(()->sample(request));',
                     'if(current.finishCommand(request.seq,SystemClock.elapsedRealtime()))',
                     'control.setConf("DisableNetwork","0");', 'control.setConf("DisableNetwork","1");',
                     'returnSTART_NOT_STICKY;', 'finally{terminateOwnProcess();}']:
        assert required in service, ('remote Tor lifetime boundary',required)
    create = compact(method_body(sources['TorProcessService'],'onCreate'))
    assert 'if(!verifyOwnProcess()){stopSelf();return;}' in create
    assert create.index('verifyOwnProcess()') < create.index('watchdog.scheduleWithFixedDelay(') < create.index('super.onCreate();')
    durable_config = 'out.write(config.getBytes(StandardCharsets.US_ASCII));out.getFD().sync();'
    assert durable_config in create
    assert create.index(durable_config) < create.index('super.onCreate();')
    assert 'ClientOnly1\\nDisableNetwork1\\nSocksPort0\\n' in create
    watchdog = compact(method_body(sources['TorProcessService'],'watchDeadlines'))
    for required in ['current.expiry(SystemClock.elapsedRealtime())','caseLEASE:beginStopping(0);return;', 'terminateOwnProcess();']:
        assert required in watchdog, ('independent Tor watchdog',required)
    for forbidden in ['reply(', 'sendError(', '.send(', 'getTorControlConnection(', '.getInfo(', '.setConf(', '.get(', '.join(', '.await(']:
        assert forbidden not in watchdog, ('blocking work in Tor watchdog',forbidden)
    stopping = compact(method_body(sources['TorProcessService'],'beginStopping'))
    assert 'current.stop(SystemClock.elapsedRealtime());' in stopping
    assert stopping.index('current.stop(') < stopping.index('controlWorker.execute(')
    assert 'stopWorkQueued.compareAndSet(false,true)' in stopping
    assert '.interrupt(' not in service and 'shutdownNow(' not in service
    identity = compact(method_body(sources['TorProcessService'],'verifyOwnProcess'))
    for required in ['intpid=Process.myPid();','intuid=Process.myUid();','Stringexpected=getPackageName()+":tor";',
                     'getApplicationInfo().uid!=uid','manager.getRunningAppProcesses()',
                     'process.pid==pid&&process.uid==uid&&expected.equals(process.processName)',
                     'ownPid=pid;ownUid=uid;ownProcessName=process.processName;identityVerified=true;']:
        assert required in identity, ('verified own Tor process identity',required)
    kill = compact(method_body(sources['TorProcessService'],'terminateOwnProcess'))
    assert kill == 'if(identityVerified&&ownPid==Process.myPid()&&ownUid==Process.myUid()&&(getPackageName()+":tor").equals(ownProcessName)){Process.killProcess(Process.myPid());}'
    assert service.count('killProcess(') == 1
    incoming = compact(method_body(sources['TorProcessService'],'receive'))
    owner_check = 'if(claimedOwner!=null&&(message.replyTo==null||!claimedOwner.getBinder().equals(message.replyTo.getBinder())))returntrue;'
    assert owner_check in incoming
    assert incoming.index(owner_check) < incoming.index('TorProcessProtocol.readRequest(message)') < incoming.index('beginStopping(')
    sample = compact(method_body(sources['TorProcessService'],'sample'))
    assert 'synchronized(control){intmarker=getSocksPort();if(marker>0)thrownewIllegalStateException("UNEXPECTED_TCP_SOCKS");authenticated=marker==0;}' in sample
    assert 'if(!authenticated){' in sample
    assert sample.index('synchronized(control)') < sample.index('if(!authenticated){') < sample.index('control.setConf("DisableNetwork","0")')
    assert sample.count('getSocksPort()') == 1

    for required in ['message.sendingUid!=Process.myUid()', 'message.arg1!=0||message.arg2!=0||message.obj!=null',
                     'data.setClassLoader(null);','!data.hasFileDescriptors()&&data.size()==size',
                     'value!=null&&value.getClass()==type','data.getInt("v")==VERSION',
                     'data.getLong("epoch")>0','data.getLong("seq")>0',
                     'validBase(data,4)&&has(data,"sampledAt",Long.class)',
                     'VERSION=2;', 'intBetween(data,"progress",0,100)','intBetween(data,"socksReady",0,1)', 'intBetween(data,"pid",1,Integer.MAX_VALUE)',
                     'intBetween(data,"circuit",0,1)']:
        assert required in protocol, ('bounded primitive Tor IPC',required)
    assert protocol.count('message.sendingUid!=Process.myUid()') == 2
    assert 'COMMAND_MS=2_000,LEASE_MS=4_000,STARTUP_MS=5_000,STOP_MS=2_000;' in policy
    assert policy.count('leaseDeadline=deadline(sentAt,LEASE_MS);') == 2
    assert 'sentAt>=0&&sentAt<=now&&now-sentAt<LEASE_MS' in policy
    assert 'attempt!=epoch||sequence<=lastSequence' in policy
    assert 'sentAt<lastSentAt||!fresh(sentAt,now)' in policy
    expiry = compact(method_body(sources['TorProcessPolicy'],'expiry'))
    assert 'if(commandSequence!=0&&now>=commandDeadline)returnExpiry.COMMAND;' in expiry
    assert expiry.index('now>=commandDeadline') < expiry.index('now>=stopDeadline')
    assert 'if(stopping)returnfalse;stopping=true;stopDeadline=deadline(now,STOP_MS);' in compact(method_body(sources['TorProcessPolicy'],'stop'))


def unix_socket_boundaries(sources):
    """Regression guard for reviewed peer ownership, not an Android/kernel proof."""
    compact = lambda value: re.sub(r'\s+', '', without_comments(value))
    endpoint = compact(sources['TorSocketEndpoint'])
    paths = compact(sources['TorSocketPaths'])
    channel = compact(sources['TorSocketChannel'])
    adapter = compact(sources['AndroidTorSocket'])
    gate = compact(sources['TorReadinessGate'])
    controller = compact(sources['EmbeddedTorController'])
    service = compact(sources['TorProcessService'])
    for name in ['TorSocketEndpoint','TorSocketPaths','TorSocketIo','AndroidTorSocket','TorSocketChannel',
                 'FixedOnionProbe','OnionApiTransport']:
        source = without_comments(sources[name])
        for forbidden in ['NioSocketChannel','NioDatagramChannel','ServerSocket','DatagramSocket',
                          'Namespace.ABSTRACT','Namespace.RESERVED','getByAddress(', 'getByName(',
                          'InetAddress.getLocalHost(', 'Os.socket(', 'Os.connect(', 'setAccessible(',
                          'getDeclaredField(', 'getDeclaredMethod(']:
            assert forbidden not in source, ('unreviewed Unix transport escape',name,forbidden)
    assert 'returnuid==peerUid&&pid==peerPid;' in endpoint
    assert 'return"TorUnixEndpoint";' in endpoint
    for required in ['context.getFilesDir().getCanonicalFile(),"tor-socks"',
                     'Os.mkdir(directory.getPath(),0700)',
                     'Os.lstat(path)', '!OsConstants.S_ISDIR(info.st_mode)',
                     'info.st_uid!=uid', '(info.st_mode&0777)!=0700',
                     '!OsConstants.S_ISSOCK(info.st_mode)', '(info.st_mode&0077)!=0',
                     '(info.st_mode&0200)==0']:
        assert required in paths, ('private filesystem socket boundary',required)
    for method in ['verifyDirectory','verifySocket']:
        assert 'info.st_uid!=uid' in compact(method_body(sources['TorSocketPaths'],method))
    assert 'if(peer==null)thrownewIOException("TOR_PEER_UNAVAILABLE");' in compact(method_body(sources['AndroidTorSocket'],'connect'))
    for required in ['LocalSocketAddress.Namespace.FILESYSTEM',
                     'TorSocketPaths.verify(endpoint);', 'socket.getInputStream();',
                     'descriptor=socket.getFileDescriptor();', 'socket.setSoTimeout(timeoutMillis);',
                     'peer=socket.getPeerCredentials();','if(peer==null)',
                     'MSG_DONTWAIT=0x40,MSG_NOSIGNAL=0x4000;',
                     'Os.recvfrom(descriptor,bytes,0,length,MSG_DONTWAIT,(InetSocketAddress)null)',
                     'Os.sendto(descriptor,bytes,0,length,MSG_DONTWAIT|MSG_NOSIGNAL,(InetAddress)null,0)',
                     'Os.poll(newStructPollfd[]{descriptorEvent},waitMillis)',
                     'Os.shutdown(descriptor,OsConstants.SHUT_RDWR)']:
        assert required in adapter, ('public API26 bounded Unix adapter',required)
    for forbidden in ['getOutputStream(', '.flush(', 'socket.isConnected(', 'newLocalSocket(descriptor)', 'Os.fcntlInt(']:
        assert forbidden not in adapter, ('unreviewed blocking/hidden adapter path',forbidden)
    connect = compact(method_body(sources['TorSocketChannel'],'doConnect'))
    proof = 'if(!endpoint.matches(opened.peerUid(),opened.peerPid()))'
    assert connect.index('opened.connect(waitMillis(CONNECT_MAX_MS));') < connect.index(proof) < connect.index('active=true;')
    assert 'check();active=true;' in connect
    assert 'CONNECT_MAX_MS=1_000,POLL_MAX_MS=100,CHUNK_BYTES=8_192;' in channel
    write = compact(method_body(sources['TorSocketChannel'],'doWriteBytes'))
    assert 'if(!active)thrownewIOException("TOR_PEER_UNVERIFIED")' in write
    assert 'socket.awaitWritable(' in write and 'synchronized(descriptorLock){check();intcount=socket.write(' in write
    assert write.index('socket.awaitWritable(') < write.index('synchronized(descriptorLock){check();intcount=socket.write(')
    assert 'if(count<0||count>bytes.length)' in write and 'source.skipBytes(count);' in write
    assert 'Arrays.fill(bytes,(byte)0);' in write
    abort = compact(method_body(sources['TorSocketChannel'],'abort'))
    assert 'privatebooleancloseSubmitted;' in channel
    assert channel.count('closeSubmitted=true;') == 1 and 'closeSubmitted=false;' not in channel
    # This small critical section intentionally includes enqueue, not future waits.
    # A second cleanup caller must not start owner shutdown while enqueue is pending.
    assert abort == (
        'synchronized(descriptorLock){if(closeSubmitted||!open)return;'
        'aborted=true;if(socket!=null)socket.shutdown();closeSubmitted=true;'
        'try{eventLoop();}catch(IllegalStateExceptionnotRegistered){'
        'unsafe().closeForcibly();return;}close();}'
    ), 'abort must revoke once and serialize close submission under the descriptor monitor'
    for forbidden in ['socket.close(', '.join(', '.await(', '.get(', '.sync(', '.syncUninterruptibly(']:
        assert forbidden not in abort, ('abort cannot wait or release an in-use descriptor',forbidden)
    close = compact(method_body(sources['TorSocketChannel'],'doClose'))
    assert 'synchronized(descriptorLock)' in close and 'socket=null;' in close
    assert 'owned.shutdown();owned.close();' in close
    assert 'if(aborted||!open||!ready.getAsBoolean())' in channel
    assert 'if(System.nanoTime()>=deadline)' in channel
    assert 'Math.min(maximum,Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)))' in channel
    assert 'newPrivateChannelId()' in channel
    shutdown = compact(method_body(sources['TorSocketChannel'],'shutdown'))
    child_stop = 'owner.shutdownGracefully(0,1,TimeUnit.SECONDS).syncUninterruptibly()'
    group_stop = 'group.shutdownGracefully(0,1,TimeUnit.SECONDS).syncUninterruptibly()'
    assert 'owner=channel.eventLoop();' in shutdown and 'channel.abort();' in shutdown
    assert 'finally{if(group!=null)group.shutdownGracefully(' in shutdown
    assert child_stop in shutdown and group_stop in shutdown
    assert shutdown.index('channel.abort();') < shutdown.index(child_stop) < shutdown.index(group_stop)
    for transport_name in ['OnionApiTransport','FixedOnionProbe']:
        transport = compact(sources[transport_name])
        assert 'channel=(TorSocketChannel)connection.channel();' in transport
        assert transport.index('channel=(TorSocketChannel)connection.channel();') < transport.index('connection.addListener(')
        assert transport.count('TorSocketChannel.shutdown(channel,group);') == 1
        assert transport.count('group=newTorSocketEventLoopGroup();') == 1
    event_loop = compact(sources['TorSocketEventLoopGroup'])
    assert 'TorSocketEventLoopGroup(){super(1);}' in event_loop
    assert 'returnnewOwner(this);' in event_loop
    assert 'OwnerextendsThreadPerChannelEventLoop' in event_loop
    assert 'returnisShuttingDown()?null:super.takeTask();' in compact(method_body(sources['TorSocketEventLoopGroup'],'takeTask'))
    assert 'sampledPid<=0||processPid!=0&&processPid!=sampledPid' in gate
    assert 'newTorSocketEndpoint(expectedSocksPath,appUid,processPid)' in gate
    assert 'candidate.controlListener().equals(listeners)' in gate
    assert 'lease.endpoint.equals(endpoint)' in gate
    assert 'socksPath=TorSocketPaths.socksPath(context);' in controller
    assert 'if(pid==Process.myPid()){beginStop(true);return;}' in controller
    assert 'newTorSocketEndpoint(socksPath,Process.myUid(),pid).controlListener()' in controller
    assert 'TorSocketPaths.prepare(this,ownUid);' in service
    assert 'TorSocketPaths.prepareControl(TorService.getTorrc(this),ownUid);' in service
    sample = compact(method_body(sources['TorProcessService'],'sample'))
    for required in ['StringexpectedConfiguration="unix:"+socksPath+"OnionTrafficOnly";',
                     'control.setConf("SocksPort",expectedConfiguration);',
                     'control.getConf("SocksPort")', 'configured.size()!=1',
                     '!"SocksPort".equals(configured.get(0).key)',
                     '!expectedConfiguration.equals(configured.get(0).value)',
                     'TorSocketPaths.verifyDirectoryForPath(socksPath,ownUid);',
                     'if(socksReady==1)TorSocketPaths.verify(newTorSocketEndpoint(socksPath,ownUid,ownPid));']:
        assert required in sample, ('verified Unix configuration/readiness',required)
    assert sample.index('control.setConf("SocksPort",expectedConfiguration);') < sample.index('control.getConf("SocksPort")') < sample.index('!expectedConfiguration.equals(') < sample.index('TorSocketPaths.verifyDirectoryForPath(') < sample.index('control.setConf("DisableNetwork","0")')
    assert sample.index('if(socksReady==1)TorSocketPaths.verify(') < sample.rindex('reply(TorProcessProtocol.sample(')
    assert 'validBase(data,8)' in compact(sources['TorProcessProtocol'])
    assert 'SocksSocketsGroupWritable0\\nControlSocketsGroupWritable0\\n' in service


def contact_boundaries(sources, build):
    compact = lambda text: re.sub(r'\s+', '', without_comments(text))
    card = compact(sources['ContactCard']); codec = compact(sources['ContactQrCodec']); importer = compact(sources['ContactQrImage'])
    for name in ['ContactCard','ContactQrCodec']:
        for forbidden in ['android.', 'java.io.', 'java.net.', 'ContentResolver', 'Intent', 'accountToken', 'sessionToken', 'deviceToken', 'registrationToken']:
            assert forbidden not in without_comments(sources[name]), ('nonpublic or platform access in pure contact codec',name,forbidden)
    encode = compact(method_body(sources['ContactCard'],'encode'))
    assert encode == "returnVERSION+'\\n'+serverOnion+'\\n'+accountId+'\\n'+deviceId+'\\n'+protocolDeviceId+'\\n'+identityKeyBase64;"
    for required in ['MAX_PAYLOAD_CHARS=1024;', 'fields.length!=6', '!VERSION.equals(fields[0])',
                     'protocolDeviceId<1||protocolDeviceId>127', 'serializedIdentity.length!=33||serializedIdentity[0]!=5',
                     '!Base64.getEncoder().encodeToString(serializedIdentity).equals(identityKeyBase64)',
                     '!expectedOrigin.equals(card.serverOnion)', 'OnionApiRequest.canonicalOrigin(origin).equals(origin)']:
        assert required in card, ('strict public contact card',required)
    for required in ['MAX_PIXELS=1_000_000;', 'MAX_DIMENSION=2048;', '(long)width*height>MAX_PIXELS',
                     'pixels.length!=width*height', 'newQRCodeReader().decode(image)',
                     'result.getBarcodeFormat()!=BarcodeFormat.QR_CODE',
                     'metadata.containsKey(ResultMetadataType.STRUCTURED_APPEND_SEQUENCE)',
                     'metadata.containsKey(ResultMetadataType.STRUCTURED_APPEND_PARITY)',
                     'returnContactCard.decode(payload,expectedOrigin);']:
        assert required in codec, ('bounded QR-only codec',required)
    for name,raw in sources.items():
        text=without_comments(raw)
        if name!='ContactQrCodec': assert 'com.google.zxing.' not in text, ('QR library escaped pure codec',name)
        if name!='ContactQrImage':
            assert 'openAssetFileDescriptor(' not in text and 'BitmapFactory.' not in text, ('image decoder escaped bounded importer',name)
        for forbidden in ['takePersistableUriPermission(', 'ACTION_IMAGE_CAPTURE', 'android.hardware.camera', 'androidx.camera']:
            assert forbidden not in text, ('unreviewed camera or persistent image access',name,forbidden)
    for required in ['MAX_ENCODED_BYTES=4*1024*1024;', 'if(uri==null||!"content".equals(uri.getScheme()))throwinvalid();',
                     'resolver.openAssetFileDescriptor(uri,"r",cancellation)',
                     'descriptor.getDeclaredLength()>MAX_ENCODED_BYTES',
                     'output.size()+count>MAX_ENCODED_BYTES', 'cancellation.throwIfCanceled();',
                     'cancellation.setOnCancelListener(()->{try{input.close();}',
                     'finally{cancellation.setOnCancelListener(null);}',
                     'bounds.inJustDecodeBounds=true;', 'bounds.outWidth>8192||bounds.outHeight>8192',
                     '"image/png".equals(bounds.outMimeType)||"image/jpeg".equals(bounds.outMimeType)',
                     'options.inScaled=false;', 'options.inSampleSize*=2;',
                     'ContactQrCodec.validateDimensions(width,height);', 'finally{bitmap.recycle();}']:
        assert required in importer, ('bounded native image import',required)
    decode = compact(method_body(sources['ContactQrImage'],'decode'))
    assert decode.index('ContactQrCodec.validateDimensions(') < decode.index('Bitmapbitmap=BitmapFactory.decodeByteArray(')
    assert decode.index('ContactQrCodec.validateDimensions(width,height);') < decode.index('newint[width*height]')
    ui=compact(sources['TorTalkActivity'])
    picker=compact(method_body(sources['TorTalkActivity'],'beginQrImport'))
    for required in ['qrOrigin=session.serverOnion;qrAccount=session.accountId;qrPeer=peer;',
                     'newIntent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")',
                     '.putExtra(Intent.EXTRA_LOCAL_ONLY,true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)',
                     'startActivityForResult(picker,CONTACT_QR_REQUEST);']:
        assert required in picker, ('explicit transient native picker',required)
    matching=compact(method_body(sources['TorTalkActivity'],'matchesPeer'))
    for required in ['card.serverOnion.equals(session.serverOnion)','card.accountId.equals(peer.accountId)',
                     'card.deviceId.equals(peer.deviceId)','card.protocolDeviceId==peer.protocolDeviceId',
                     'card.identityKeyBase64.equals(peer.identityKeyBase64)']:
        assert required in matching, ('exact peer QR matching',required)
    result=compact(method_body(sources['TorTalkActivity'],'onActivityResult'))
    for required in ['requestCode!=CONTACT_QR_REQUEST||!awaitingQr',
                     '!qrOrigin.equals(session.serverOnion)||!qrAccount.equals(session.accountId)',
                     'timer.postDelayed(qrTimeout,15000);', 'qrWorker.submit(',
                     'generation!=qrGeneration||!flow.complete(operation)',
                     '!boundOrigin.equals(session.serverOnion)||!boundAccount.equals(session.accountId)',
                     'ContactQrImage.read(getContentResolver(),selected,boundOrigin,cancellation)']:
        assert required in result, ('stale QR import result boundary',required)
    show=compact(method_body(sources['TorTalkActivity'],'showImportedQr'))
    assert 'messaging.pendingPeer()!=qrPeer||!selectedRoom.equals(qrPeer.roomId)||!matchesPeer(card,qrPeer)' in show
    assert 'verifyPeerDialog(card);return;' in show and 'messaging.verifyPeer(' not in show and 'replacePeerIdentity(' not in show
    verify=compact(method_body(sources['TorTalkActivity'],'verifyPeerDialog','ContactCard scanned'))
    assert 'if(scanned!=null&&!matchesPeer(scanned,peer))' in verify
    assert 'box.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);' in verify
    assert 'verified.setOnCheckedChangeListener((v,on)->box.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(on));' in verify
    assert 'cancelQr();' in compact(method_body(sources['TorTalkActivity'],'clearSession'))
    assert 'if(!awaitingQr)cancelQr();' in compact(method_body(sources['TorTalkActivity'],'onStop'))
    assert 'cancelQr();qrWorker.shutdownNow();' in compact(method_body(sources['TorTalkActivity'],'onDestroy'))
    for required in ['implementation("com.google.zxing:core:3.5.4")',
                     'check(artifacts.size == 1) { "Unexpected ZXing runtime artifact inventory" }',
                     'check(core.name == "core" && core.moduleVersion.id.version == "3.5.4")',
                     'check(actual == "71de5d89341b5fcf5dd89da7f44e84d825d0e084cdf3ec77c9abe26b0f0ceb13")',
                     'tasks.named("preBuild") { dependsOn(verifyContactQrArtifact) }']:
        assert required in build, ('pinned QR core artifact',required)


def block_boundaries(sources):
    """Actor-owned mutations require durable intent and exact, monotonic server acknowledgement."""
    compact = lambda text: re.sub(r'\s+', '', without_comments(text))
    state=compact(sources['PeerBlockState']); schema=compact(sources['PeerBlockJournalSchema'])
    journal=compact(sources['PeerBlockJournal']); coordinator=compact(sources['PeerBlockCoordinator'])
    storage=compact(sources['KeystoreBlobStore']); ui=compact(sources['TorTalkActivity'])
    for required in ['value.length()!=5||', '!expectedPeer.equals(value.get("peer_id"))',
                     '!(blockinstanceofBoolean)||!(legacyinstanceofBoolean)',
                     '!(numberinstanceofInteger||numberinstanceofLong)||!(mutationinstanceofString)',
                     'revision<0||revision>Integer.MAX_VALUE',
                     'if(revision==0){if(!id.isEmpty()||isBlocked!=isLegacy)throwinvalid();}',
                     'elseif(!validId(id)||isLegacy)throwinvalid();',
                     'mutationId.equals(id)&&blocked==desired&&!legacyHold&&(long)revision==(long)expectedRevision+1',
                     'body.length()!=2||', 'entries.length()>64||', 'peer.equals(account)||peer.compareTo(previous)<=0',
                     'entries.length()!=64||!validId(next)||!next.equals(previous)||next.compareTo(after)<=0']:
        assert required in state, ('strict actor-owned block response',required)
    for required in ['MAX_BYTES=256*1024;', 'MAX_PENDING=8;', 'MAX_STATUSES=1024;',
                     '!SessionSchema.isCanonicalOrigin(origin)||!validId(account)',
                     'bytes.length<20||bytes.length>MAX_BYTES',
                     'count<0||count>MAX_PENDING||statusCount<0||statusCount>MAX_STATUSES',
                     'previous.compareTo(peer)>=0', 'account.equals(peer)',
                     'if(input.hasRemaining())throwfailure();', 'c<33||c>126']:
        assert required in schema, ('bounded canonical block journal schema',required)
    for name in ['PeerBlockJournalSchema','PeerBlockJournal','PeerBlockState']:
        for forbidden in ['TorApiClient','SignalClient','MessageCoordinator','accountToken','sessionToken','deviceToken','registrationToken','privateKey']:
            assert forbidden not in without_comments(sources[name]), ('block metadata escaped its storage boundary',name,forbidden)
    assert 'newKeystoreBlobStore(context,namespace(origin,accountId))' in journal
    for required in ['.digest((origin+"\\n"+account).getBytes(StandardCharsets.US_ASCII))', 'return"blocks-"+newString(value);',
                     'if(!origin.equals(record.origin))throwfailure(Code.ORIGIN_MISMATCH);',
                     'if(!account.equals(record.account))throwfailure(Code.IDENTITY_MISMATCH);',
                     'if(entry.sameRequest(pending))return;throwfailure(Code.PENDING_CONFLICT);',
                     'pending.expectedRevision<status.revision',
                     'status.revision<previous.revision||status.revision==previous.revision&&!status.sameStatus(previous)',
                     '!acknowledgeMutationId.equals(matching.mutationId)||matching.blocked!=status.blocked||(long)matching.expectedRevision+1!=status.revision',
                     'if(changed)commit(previous,pending,statuses);',
                     'if(status.absent())returnfalse;', 'finally{wipe(previous);}']:
        assert required in journal, ('profile-bound immutable block journal',required)
    commit=compact(method_body(sources['PeerBlockJournal'],'commit'))
    for required in ['PeerBlockJournalSchema.encode(origin,account,replacement,statuses)',
                     'bytes.write(encoded,previous->{', 'decodeBound(previous);',
                     'if(!Arrays.equals(expected,previous))', 'rejection[0]=Code.STATE_CHANGED;', 'finally{wipe(encoded);}']:
        assert required in commit, ('atomic block journal compare-and-save',required)
    assert journal.count('bytes.clear()') == 1
    assert 'bytes.clear();' in method_body(sources['PeerBlockJournal'],'resetAfterConfirmation')
    for required in ['MAX_BLOCK_BYTES=256*1024;', 'namespace.matches("blocks-[0-9a-f]{64}")',
                     'this.limit=namespace.matches("blocks-[0-9a-f]{64}")?MAX_BLOCK_BYTES',
                     'this.domain="TorTalk/KeystoreBlob/v1/"+packageName+"/"+namespace;']:
        assert required in storage, ('encrypted block namespace isolation',required)

    for required in ['session.serverOnion.equals(next.serverOnion)&&session.accountId.equals(next.accountId)&&session.sessionToken.equals(next.sessionToken)',
                     'cancel();profileGeneration++;session=next;journal=null;ready=false;failed=false;',
                     'factory.open(next.serverOnion,next.accountId)', 'if(!profileCurrent(profile))return;',
                     'return!closed&&session!=null&&profile==profileGeneration;',
                     'returnprofileCurrent(profile)&&busy&&operation==operationGeneration;',
                     'return!ready()||intent!=null&&intent.blocked||state!=null&&state.blocked;',
                     'a.peerId.equals(b.peerId)&&a.mutationId.equals(b.mutationId)&&a.expectedRevision==b.expectedRevision&&a.blocked==b.blocked',
                     '!observed.retryable(saved.mutationId,saved.expectedRevision)',
                     'old.revision>value.revision||old.revision==value.revision&&(!old.mutationId.equals(value.mutationId)||old.blocked!=value.blocked||old.legacyHold!=value.legacyHold)',
                     'failed=true;ready=false;listener.changed();finish(profile,operation,callback,"BLOCK_STORAGE_FAILED");']:
        assert required in coordinator, ('owned block operation and uncertainty fence',required)
    send=compact(method_body(sources['PeerBlockCoordinator'],'persistAndSend'))
    required_order=['AppSessionStore.Sessionowner=session;', 'acceptedHolds.put(intent.peerId,intent);',
                    'disk.execute(', 'store.prepare(intent);', 'main.execute(', 'if(!profileCurrent(profile))return;',
                    'install(loaded,statuses);', 'if(!current(profile,operation))return;', 'api.request("POST",']
    position=-1
    for required in required_order:
        found=send.find(required,position+1); assert found>position, ('durable block request ordering',required); position=found
    for required in ['body.put("peer_id",intent.peerId).put("mutation_id",intent.mutationId).put("expected_revision",intent.expectedRevision)',
                     'List<PeerBlockJournal.Pending>loaded=store.load();', 'List<PeerBlockJournal.Status>statuses=store.loadStatuses();',
                     'intent.blocked?"/v1/blocks":"/v1/blocks/unblock",body,owner',
                     '!accepted.acknowledges(intent.mutationId,intent.expectedRevision,intent.blocked)||!remember(accepted)',
                     'persistStatus(profile,operation,intent,accepted,callback);']:
        assert required in send, ('exact block request and acknowledgement',required)
    assert send.index('!accepted.acknowledges(') < send.index('persistStatus(')
    assert set(re.findall(r'\.put\("([^"]+)"',send)) == {'peer_id','mutation_id','expected_revision'}
    assert send.count('api.request(') == 1
    confirm=compact(method_body(sources['PeerBlockCoordinator'],'persistStatus'))
    assert 'store.confirm(record(state),intent==null?"":intent.mutationId);' in confirm
    assert confirm.index('store.confirm(record(state),intent==null?"":intent.mutationId);') < confirm.index('main.execute(')
    assert 'if(!profileCurrent(profile))return;install(loaded,statuses);' in confirm
    assert 'finish(profile,operation,code->callback.complete(state,code),"");' in confirm
    assert 'store.confirmAll(updates);' in compact(method_body(sources['PeerBlockCoordinator'],'page'))
    assert '!same(saved,intent)' in compact(method_body(sources['PeerBlockCoordinator'],'retry'))
    failure=compact(method_body(sources['PeerBlockCoordinator'],'storageFailure'))
    for forbidden in ['acceptedHolds.clear(', 'acceptedHolds.remove(', 'clearHold(', 'known.clear(', 'resetAfterConfirmation(']:
        assert forbidden not in failure, ('uncertain block intent was discarded',forbidden)
    assert 'newPeerBlockJournal(getApplicationContext(),origin,account)' in ui
    assert 'api.request(method,path,body,owner.sessionToken,owner.deviceToken,owner.serverOnion,reply::complete),disk,timer::post' in ui
    for name in ['sendMessage','retryPending','refreshMessages']:
        assert 'if(!allowPeerTraffic())return;' in compact(method_body(sources['TorTalkActivity'],name)), ('native block traffic fence',name)
    assert 'if(peerBlocks.blocksSending(openingPeer)){openLocalHistory(room,openingPeer,"");return;}' in compact(method_body(sources['TorTalkActivity'],'openRoom'))
    # Journal recovery/forget is an explicit native confirmation and never a Signal/session reset.
    assert 'setPositiveButton("기록만지우기",(d,w)->' in compact(method_body(sources['TorTalkActivity'],'forgetBlockDialog'))
    assert 'setPositiveButton("위험을이해하고기록삭제",(d,w)->' in compact(method_body(sources['TorTalkActivity'],'resetBlockJournalDialog'))
    for name in ['forgetBlockDialog','resetBlockJournalDialog','blockConfirmed']:
        body=method_body(sources['TorTalkActivity'],name)
        for forbidden in ['clearSession(', 'storage.clear(', 'signal.', 'replacePeerIdentity(', 'sendText(', 'retryPending(']:
            assert forbidden not in body, ('block action erased or retransmitted history',name,forbidden)


def archive_boundaries(sources):
    """Read-only history derives authority from one bound encrypted snapshot, never server display fields."""
    compact=lambda text:re.sub(r'\s+', '', without_comments(text))
    signal=sources['SignalClient']; coordinator=sources['MessageCoordinator']; ui=sources['TorTalkActivity']
    state=compact(method_body(signal,'archiveState'))
    for required in ['origin(expectedOrigin);id(expectedAccount);States=load();', 'if(s.records.isEmpty())returns;',
                     'expectedOrigin.equals(s.text("meta|origin"))&&expectedAccount.equals(s.text("meta|account"))',
                     's.get("local|identity")!=null', 'id(s.text("meta|device"));']:
        assert required in state, ('local archive profile binding',required)
    for method in ['listLocalRooms','openLocalRoom','archiveState','archiveIndex','archiveMessage']:
        body=method_body(signal,method)
        for forbidden in ['commit(', 'initialize(', 'prepareIdentity(', 'bindAccount(', 'acceptPeer(', 'acceptBundle(', 'loadSession(', 'cipher(', '.encrypt(', '.decrypt(', 'storage.save(', 'compareAndSave(']:
            assert forbidden not in body, ('archive mutated or reconstructed Signal state',method,forbidden)
    listing=compact(method_body(signal,'listLocalRooms')); history=compact(method_body(signal,'openLocalRoom'))
    for required in ['limit>=1&&limit<=40,"archive_page_limit"', 'archiveState(expectedOrigin,expectedAccount)',
                     '!peerAccount.isEmpty()&&!room.peer.equals(peerAccount)', 'room.room.compareTo(afterRoomId)<=0',
                     'if(result.size()==limit){more=true;break;}']:
        assert required in listing, ('bounded verified local room page',required)
    for required in ['limit>=1&&limit<=200,"archive_page_limit"', 'archiveState(expectedOrigin,expectedAccount)',
                     'selected!=null&&selected.peer.equals(expectedPeer)',
                     'require(bytes!=null,"archive_cursor");', 'anchor!=null&&compareArchive(message,anchor)>=0',
                     'anchor.stored.envelope.roomId.equals(roomId)&&anchor.peer.equals(expectedPeer)',
                     'if(latest.size()>limit+1)latest.poll();', 'if(more)latest.poll();', 's.get("retired-out|"+m.envelope.messageId)!=null']:
        assert required in history, ('bounded immutable archive history page',required)
    index=compact(method_body(signal,'archiveIndex')); message=compact(method_body(signal,'archiveMessage'))
    assert 'key.startsWith("room|")' in index and 'key.startsWith("in|")||key.startsWith("out|")' in index
    for required in ['validateEnvelope(e);checkText(m.text);',
                     'key.equals((outgoing?"out|":"in|")+e.messageId)',
                     'ownAccount.equals(s.text("meta|account"))&&ownDevice.equals(s.text("meta|device"))&&!peer.equals(ownAccount)']:
        assert required in message, ('authenticated durable archive association',required)
    assert 'existing==null||existing.peer.equals(peer)' in compact(method_body(signal,'archiveRoom'))
    api=compact(coordinator)
    assert 'return!localArchive&&enrolled&&peerReady&&peer!=null&&peer.roomId.equals(room)&&session!=null;' in api
    assert 'if(localArchive){done.complete("LOCAL_ARCHIVE_READ_ONLY");return-1;}' in compact(method_body(coordinator,'beginLive'))
    for name,parameters in [('enroll',None),('openRoom','String room,String expectedPeer,Callback done'),
                            ('verifyPeer',None),('replacePeerIdentity',None),('sendText','String room,String messageId,String text,Runnable staged,Callback done'),
                            ('maintainPrekeys',None),('retryPending',None),('poll',None)]:
        assert compact(method_body(coordinator,name,parameters)).startswith('longg=beginLive(done);'), ('live operation bypasses archive fence',name)
    assert 'done.complete(localArchive?"LOCAL_ARCHIVE_READ_ONLY":"EXPECTED_PEER_REQUIRED")' in api
    assert 'if(localArchive){callback.complete(0,newJSONObject(),"LOCAL_ARCHIVE_READ_ONLY");return;}' in compact(method_body(coordinator,'request'))
    current=compact(method_body(coordinator,'localOperationCurrent'))
    for required in ['current(g)&&core==selected', 'identity.serverOnion.equals(value.serverOnion)',
                     'identity.accountId.equals(value.accountId)', 'identity.serverOnion.equals(api.configuredOnion())']:
        assert required in current, ('archive operation profile/generation fence',required)
    assert compact(method_body(coordinator,'localRead')).count('localOperationCurrent(g,selected,identity)') == 3
    assert 'if(localArchive&&!archiveProfileCurrent())returnnewJSONArray();' in compact(method_body(coordinator,'messages'))
    view_binding=compact(method_body(coordinator,'archiveProfileCurrent'))
    for required in ['core==archiveCore', 'archiveOrigin.equals(value.serverOnion)',
                     'archiveAccount.equals(value.accountId)', 'archiveOrigin.equals(api.configuredOnion())']:
        assert required in view_binding, ('archive cache profile binding',required)
    opened=compact(method_body(coordinator,'openLocalRoom','String room,String expectedPeer,String beforeMessageCursor,int limit,Callback done'))
    assert 'cancel();localArchive=true;archiveCore=core;archiveOrigin=session.serverOnion;archiveAccount=session.accountId;' in opened
    assert opened.index('cancel();localArchive=true;') < opened.index('localRead(')
    assert 'peer=null;peerReady=false;localMessages=newJSONArray();' in opened
    for name,parameters in [('listLocalRooms',None),('openLocalRoom','String room,String expectedPeer,String beforeMessageCursor,int limit,Callback done')]:
        body=method_body(coordinator,name,parameters)
        for forbidden in ['request(', 'enroll(', 'claimId(', 'acceptPeer(', 'acceptBundle(', '.receive(', 'markRelayAccepted(', 'stageText(']:
            assert forbidden not in body, ('archive invoked live work',name,forbidden)
    projection=method_body(sources['SignalJson'],'localHistoryMessages')
    assert set(re.findall(r'\.put\("([^"]+)"',projection)) == {'id','text','own','local_time','status'}
    assert set(re.findall(r'\.put\("([^"]+)"',method_body(sources['SignalJson'],'localRooms'))) == {'room_id','peer_id','message_count','last_message_at','read_only','rooms','next_after'}
    assert '.put("read_only",true)' in compact(method_body(sources['SignalJson'],'localRooms'))
    for required in ['.put("text",entry.text)', '!page.room.roomId.equals(e.roomId)',
                     'expectedAccount.equals(e.recipientAccountId)&&page.room.peerAccountId.equals(e.senderAccountId)']:
        assert required in compact(projection), ('archive plaintext-only projection',required)
    for name in ['sendMessage','retryPending','refreshMessages']:
        assert 'if(!allowPeerTraffic())return;' in compact(method_body(ui,name)), ('native block/archive traffic fence',name)
    allowed=compact(method_body(ui,'allowPeerTraffic'))
    assert 'if(localHistoryMode){' in allowed and 'returnfalse;' in allowed
    assert 'Stringreason=protectionMessage();if(!reason.isEmpty())' in allowed
    protection=compact(method_body(ui,'protectionMessage'))
    for required in ['if(!peerBlocks.ready())', 'if(intent!=null&&intent.blocked)', 'state!=null&&state.blocked']:
        assert required in protection, ('pending/failed/confirmed local block hold',required)
    assert compact(method_body(ui,'maybeEnroll')).startswith('if(localHistoryMode||recoveryHoldsEnrollment())return;')
    native=compact(ui)
    assert 'if(!localHistoryMode&&protectionMessage().isEmpty()&&route.equals("chat")' in native
    assert 'if(!localHistoryMode&&!recoveryHoldsEnrollment()&&session!=null&&enrollmentReady&&!enrolling&&!maintaining' in native
    for required in ['localHistoryMode=retained.archive&&route.equals("chat");',
                     'selectedRoom=retained.archive&&!localHistoryMode?"":retained.room;',
                     'if(localHistoryMode&&route.equals("chat"))openLocalHistory(selectedRoom,localHistoryPeer,localHistoryBefore);']:
        assert required in native, ('archive retention must restore locally',required)
    for name in ['localRoomsDialog','openLocalHistory']:
        body=method_body(ui,name)
        for forbidden in ['!ready()', 'request(', 'maybeEnroll(', 'messaging.openRoom(', 'messaging.poll(', 'messaging.sendText(']:
            assert forbidden not in body, ('local archive acquired network/readiness',name,forbidden)
    assert 'messaging.listLocalRooms(peer,after,40,' in compact(method_body(ui,'localRoomsDialog'))
    native_open=compact(method_body(ui,'openLocalHistory'))
    assert native_open.index('localHistoryMode=true;') < native_open.index('messaging.openLocalRoom(room,peer,before,200,')
    assert 'messaging.leaveLocalArchive();localHistoryMode=false;' in compact(method_body(ui,'leaveLocalHistory'))


def recovery_boundaries(sources):
    """Reviewed durable native recovery transitions; host checks do not prove device durability."""
    compact = lambda value: re.sub(r'\s+', '', without_comments(value))
    store = sources['PendingDeviceRecoveryStore']; coordinator = sources['DeviceRecoveryCoordinator']
    ui = sources['TorTalkActivity']; schema = sources['PendingDeviceRecoverySchema']
    body = lambda source, name: compact(method_body(source, name))

    def ordered(value, steps, label):
        position = -1
        for step in steps:
            found = value.find(step, position + 1)
            assert found > position, (label, step)
            position = found

    # Capabilities live only in the encrypted checkpoint, native input/display and requests.
    assert 'newKeystoreBlobStore(context,"device-recovery")' in compact(store)
    for source in [store, coordinator, schema]:
        clean = without_comments(source)
        for forbidden in ['android.util.Log', 'System.out', 'System.err', 'printStackTrace(',
                          'DiagnosticLog', 'setPrimaryClip(', 'WebMessage', 'FileOutputStream']:
            assert forbidden not in clean, ('recovery secret sink', forbidden)
    assert 'return"PendingDeviceRecovery([redacted])";' in compact(store)
    assert 'return"PendingDeviceRecoveryStore([redacted])";' in compact(store)
    projection = ''.join(method_body(ui, name) for name in ['publish', 'clearDraft'])
    projection += method_body(sources['NativeResponseRules'], 'rooms') + method_body(sources['HistoryWindow'], 'of')
    for secret in ['oldAccountToken', 'replacementAccountToken', 'oldCapability', 'deviceRecovery.pending(',
                   'account_token', 'replacement_account_token']:
        assert secret not in projection, ('recovery capability projected to page', secret)
    assert body(sources['NativeResponseRules'], 'session').count('newAppSessionStore.Session(origin,requested,"","","",string(response,"session_token"))') == 1

    assert 'MAX_BYTES=4096;' in compact(schema) and 'LIMITS={69,80,80,80,51,51};' in compact(schema)
    validation = body(schema, 'validate')
    for required in ['fields.length!=6', '!SessionSchema.isCanonicalOrigin(fields[0])',
                     '!id(fields[1])', '!id(fields[2])', '!id(fields[3])',
                     '!SessionSchema.token(fields[4],"gd_acct_")', '!SessionSchema.token(fields[5],"gd_acct_")',
                     'fields[4].equals(fields[5])']:
        assert required in validation, ('recovery schema identity/capability binding', required)
    decode = body(schema, 'decode')
    for required in ['bytes.length>MAX_BYTES', 'input.getInt()!=MAGIC||input.getInt()!=VERSION',
                     'if(phase==0){if(input.hasRemaining())throwinvalid();returnnewRecord(null,false);}',
                     'if(phase!=1&&phase!=2)throwinvalid();', 'length>LIMITS[i]', 'input.remaining()<length',
                     'if(b<33||b>126)throwinvalid();', 'if(input.hasRemaining())throwinvalid();try{validate(fields);}',
                     'returnnewRecord(fields,phase==2);']:
        assert required in decode, ('bounded recovery checkpoint decode', required)
    assert 'if(fields==null){if(confirmed)throwinvalid();returnByteBuffer.allocate(12).putInt(MAGIC).putInt(VERSION).putInt(0).array();}' in body(schema, 'encode')

    prepare = body(store, 'prepare')
    for required in ['if(previous!=null){', '!previous.serverOnion.equals(origin)',
                     '!previous.accountId.equals(account)||!previous.targetDeviceId.equals(target)||!previous.oldAccountToken.equals(oldCapability)',
                     'throwfail(Code.PENDING_CONFLICT);returnprevious;', 'secret=newbyte[32],identity=newbyte[16]',
                     'SecureRandomrandom=newSecureRandom();', 'while(oldCapability.equals(replacement));',
                     'finally{wipe(secret);wipe(identity);}', 'finally{wipe(before);wipe(next);}']:
        assert required in prepare, ('exact durable recovery preparation', required)
    ordered(prepare, ['before=blob.read();', 'returnprevious;', 'random.nextBytes(secret);',
                      'next=encode(created);', 'writeCompared(before,next);', 'returncreated;'], 'recovery write before release')
    assert body(store, 'dispatchIfCurrent') == 'synchronized(MUTATION_LOCK){if(expected!=mutationEpoch)returnfalse;dispatch.run();returntrue;}'
    assert body(store, 'mutationEpoch') == 'synchronized(MUTATION_LOCK){returnmutationEpoch;}'
    assert body(store, 'writeCompared') == 'synchronized(MUTATION_LOCK){mutationEpoch++;}blob.write(next,current->{if(!Arrays.equals(before,current))thrownewKeystoreBlobStore.StorageException(KeystoreBlobStore.Code.IDENTITY_MISMATCH);});'
    confirmed = body(store, 'markConfirmed')
    ordered(confirmed, ['if(!recoveryId.equals(previous.recoveryId))throwfail(Code.STATE_CHANGED);',
                        'previous.oldAccountToken,previous.replacementAccountToken,true)',
                        'writeCompared(before,next);', 'returnconfirmed;'], 'confirm exact recovery checkpoint')
    acknowledged = body(store, 'acknowledge')
    ordered(acknowledged, ['if(!recoveryId.equals(previous.recoveryId))throwfail(Code.STATE_CHANGED);',
                           'if(!previous.confirmed)throwfail(Code.UNCONFIRMED);',
                           'next=PendingDeviceRecoverySchema.encode(null,false);', 'writeCompared(before,next);'], 'acknowledged key cleanup')
    reset = body(store, 'resetAfterConfirmation')
    for required in ['previous==null||!expectedRecoveryId.equals(previous.recoveryId)',
                     'try{load();throwfail(Code.STATE_CHANGED);}',
                     'if(failure.code!=Code.CORRUPT_STATE&&failure.code!=Code.KEY_UNAVAILABLE)throwfailure;',
                     'synchronized(MUTATION_LOCK){mutationEpoch++;}blob.clear();']:
        assert required in reset, ('explicit bound checkpoint abandonment', required)
    assert compact(store).count('blob.clear();') == 1

    start = body(coordinator, 'start')
    ordered(start, ['if(!loaded||failed)', 'preparing=true;', 'disk.execute(',
                    'store.prepare(bound,owner.accountId,target,oldCapability);', 'long epoch=store.epoch();'.replace(' ', ''),
                    'main.execute(', 'pendingEpoch=epoch;', 'if(!current(operation))return;',
                    'if(!matchesContext(value))', 'recover(operation,value,session,true,callback);'], 'checkpoint before recovery HTTP')
    assert start.count('recover(') == 1 and 'api.request(' not in start and 'authenticate(' not in start
    assert 'return!closed&&busy&&operation==generation;' == body(coordinator, 'current')
    assert 'generation++;busy=false;' == body(coordinator, 'cancel')
    assert 'if(!same)cancel();origin=value;session=next;' in body(coordinator, 'updateContext')
    assert body(coordinator, 'matchesContext') == 'returnvalue.serverOnion.equals(origin)&&(session==null||value.serverOnion.equals(session.serverOnion)&&value.accountId.equals(session.accountId));'
    same = body(coordinator, 'sameRequest')
    for field in ['serverOnion', 'accountId', 'targetDeviceId', 'recoveryId', 'oldAccountToken', 'replacementAccountToken']:
        assert 'a.'+field+'.equals(b.'+field+')' in same, ('recovery retry must reuse checkpoint', field)
    resume = body(coordinator, 'resume')
    ordered(resume, ['store.load();', 'long epoch=store.epoch();'.replace(' ', ''), 'main.execute(',
                     'pendingEpoch=epoch;', 'if(!current(operation))return;', 'if(!sameRequest(expected,value))',
                     'if(!matchesContext(value))', 'if(session==null)authenticate(operation,value,true,callback);'], 'resume exact saved recovery')
    assert body(coordinator, 'dispatch') == 'if(!current(operation))return;if(!store.dispatchIfCurrent(pendingEpoch,request))finish(operation,callback,"RECOVERY_STATE_CHANGED");'
    assert compact(coordinator).count('api.request(') == 2
    assert body(coordinator, 'unauthorized') == 'returnstatus==401&&code.isEmpty()&&body!=null&&body.length()==1&&"UNAUTHORIZED".equals(body.opt("code"));'
    assert body(coordinator, 'request') == 'returnobject("account_token",value.oldAccountToken,"replacement_account_token",value.replacementAccountToken,"recovery_id",value.recoveryId);'
    assert body(coordinator, 'validResult') == 'returnbody!=null&&body.length()==3&&"recovered".equals(body.opt("status"))&&value.targetDeviceId.equals(body.opt("device_id"))&&value.recoveryId.equals(body.opt("recovery_id"));'
    recover = body(coordinator, 'recover')
    for required in ['if(!current(operation))return;', '!owner.serverOnion.equals(value.serverOnion)||!owner.accountId.equals(value.accountId)',
                     'dispatch(operation,callback,()->api.request("POST","/v1/devices/"+value.targetDeviceId+"/recover",request(value),owner,value.serverOnion,',
                     'if(unauthorized(status,body,code)&&mayAuthenticate){authenticate(operation,value,true,callback);return;}',
                     'if(status!=200||!code.isEmpty())']:
        assert required in recover, ('recovery HTTP/authentication binding', required)
    ordered(recover, ['if(!validResult(body,value))', 'disk.execute(', 'store.markConfirmed(value.recoveryId);',
                      'long epoch=store.epoch();'.replace(' ', ''), 'main.execute(', 'pending=accepted;',
                      'pendingEpoch=epoch;', 'finish(operation,callback,"");'], 'persist verified acknowledgement before success')
    authenticate = body(coordinator, 'authenticate')
    for required in ['if(!current(operation))return;if(!matchesContext(value))',
                     'object("account_id",value.accountId,"account_token",replacement?value.replacementAccountToken:value.oldAccountToken)',
                     'dispatch(operation,callback,()->api.request("POST","/v1/auth/sessions",body,null,value.serverOnion,',
                     'if(!current(operation))return;if(unauthorized(status,response,code)&&replacement){authenticate(operation,value,false,callback);return;}',
                     'if(status!=201||!code.isEmpty())',
                     'NativeResponseRules.session(value.serverOnion,value.accountId,response)',
                     'session=authenticated;listener.sessionAuthenticated(authenticated);if(!current(operation))return;recover(operation,value,session,false,callback);']:
        assert required in authenticate, ('new capability first/exact authentication result', required)
    acknowledgement = body(coordinator, 'acknowledge')
    assert '!pending.confirmed||!pending.recoveryId.equals(recoveryId)||!matchesContext(pending)' in acknowledgement
    ordered(acknowledgement, ['store.acknowledge(recoveryId);', 'store.load();', 'main.execute(', 'pending=value;'], 'cleanup only after durable key acknowledgement')
    assert 'failed=true;loaded=false;' in body(coordinator, 'localFailure')
    for name in ['cancel', 'close', 'localFailure', 'recover', 'authenticate']:
        value = body(coordinator, name)
        for forbidden in ['store.acknowledge(', 'store.resetAfterConfirmation(', 'store.prepare(']:
            assert forbidden not in value, ('implicit recovery destruction/replacement', name, forbidden)

    native = compact(ui)
    for required in ['returnPendingDeviceRecoveryStore.mutationEpoch();',
                     'returnPendingDeviceRecoveryStore.dispatchIfCurrent(expected,request);',
                     'if(!origin.equals(api.configuredOnion()))',
                     'api.request(method,path,body,owner==null?"":owner.sessionToken,owner==null?"":owner.deviceToken,origin,reply::complete);']:
        assert required in native, ('native recovery adapter binding', required)
    assert body(ui, 'maybeEnroll').startswith('if(localHistoryMode||recoveryHoldsEnrollment())return;')
    assert 'if(!localHistoryMode&&!recoveryHoldsEnrollment()&&session!=null&&enrollmentReady' in native
    for required in ['!deviceRecovery.loaded()||deviceRecovery.failed()||deviceRecovery.busy()',
                     'pending.serverOnion.equals(session.serverOnion)&&pending.accountId.equals(session.accountId)']:
        assert required in body(ui, 'recoveryHoldsEnrollment'), ('enrollment held by checkpoint', required)
    key = body(ui, 'showDeviceRecoveryKey')
    for required in ['pending.replacementAccountToken', 'key.setSaveEnabled(false);',
                     'key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);',
                     'setCancelable(false)', 'secureDialog(box);',
                     'box.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);recorded.setOnCheckedChangeListener(',
                     'deviceRecovery.acknowledge(pending.recoveryId,code->{if(!code.isEmpty())',
                     'key.setText("");box.dismiss();enrollmentAttempted=false;enrollmentReady=false;']:
        assert required in key, ('explicit native new-key acknowledgement', required)
    assert key.count('key.setText("");') == 2
    for name in ['clearSession', 'deleteStoredSession', 'onDestroy']:
        assert 'resetAfterConfirmation(' not in body(ui, name) and '.acknowledge(' not in body(ui, name)


def json_boundaries(sources):
    """Reject duplicate decoded names before Android JSONTokener can overwrite them."""
    compact = lambda value: re.sub(r'\s+', '', without_comments(value))
    parser = sources['JsonBounds']
    obj = compact(method_body(parser, 'object'))
    assert 'Set<String>keys=newHashSet<>();while(true){' in obj
    assert 'space();StringBuilderkey=newStringBuilder();' in obj
    assert 'if(!string(key)||!keys.add(key.toString())||!take(\':\')||!value(depth))returnfalse;' in obj
    assert 'keys.clear(' not in obj and obj.count('newHashSet<>') == 1
    string = compact(method_body(parser, 'string', 'StringBuilder decoded'))
    for required in ['if(c<32)returnfalse;', "case'\"':case'\\\\':case'/':c=e;break;",
                     "case'b':c='\\b';break;case'f':c='\\f';break;case'n':c='\\n';break;case'r':c='\\r';break;case't':c='\\t';break;",
                     "case'u':intvalue=0;", 'for(inti=0;i<4;i++)',
                     "h>='A'&&h<='F'?h-'A'+10:-1", 'if(digit<0)returnfalse;value=(value<<4)|digit;',
                     'c=(char)value;break;', 'default:returnfalse;', 'if(decoded!=null)decoded.append(c);']:
        assert required in string, ('strict decoded JSON key', required)
    assert 'text.length()>OnionApiRequest.MAX_RESPONSE_BYTES' in compact(method_body(parser, 'safe'))
    assert 'returnp.value(0)&&p.end();' in compact(method_body(parser, 'safe'))
    assert 'if(depth>=32)returnfalse;' in compact(method_body(parser, 'value'))
    api = compact(sources['TorApiClient'])
    assert 'if(!JsonBounds.safe(text))thrownewIllegalArgumentException("JSON_BOUNDS");JSONTokenertokener=newJSONTokener(text);' in api
    assert '.onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)' in api
    assert 'if(!(valueinstanceofJSONObject)||tokener.nextClean()!=0)thrownewIllegalArgumentException("JSON_OBJECT_REQUIRED");' in api
    ui = compact(sources['TorTalkActivity'])
    assert 'if(data==null||data.length()>UiCommandPolicy.MAX_COMMAND_CHARS||!JsonBounds.safe(data))return;try{JSONObjectcommand=newJSONObject(data);' in ui


def source_boundaries(sources, assets, build):
    assert set(sources) == SOURCE_FILES, ('review source inventory changes', sorted(set(sources) ^ SOURCE_FILES))
    storage_boundaries(sources)
    process_boundaries(sources)
    unix_socket_boundaries(sources)
    contact_boundaries(sources,build)
    block_boundaries(sources)
    archive_boundaries(sources)
    recovery_boundaries(sources)
    json_boundaries(sources)
    text_validator = re.sub(r'\s+', '', method_body(sources['SignalClient'],'checkText'))
    for required in ['text.length()<=MAX_TEXT_BYTES','StandardCharsets.UTF_8.newEncoder()',
                     '.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)',
                     '.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)',
                     '.encode(java.nio.CharBuffer.wrap(text)).remaining()',
                     'require(bytes<=MAX_TEXT_BYTES,"text_size")','throwinvalid("text_encoding")']:
        assert required in text_validator, ('strict plaintext integrity',required)
    assert 'getBytes(' not in text_validator
    auth_failures = re.sub(r'\s+', '', method_body(sources['CoordinatorFailures'],'expiresSession'))
    assert auth_failures == 'return"AUTH_REQUIRED".equals(code)||"LOGIN_REQUIRED".equals(code)||"QUEUED_AUTH_REQUIRED".equals(code)||"QUEUED_LOGIN_REQUIRED".equals(code);'

    source = '\n'.join(sources.values())
    for disallowed in ['ProcessBuilder', 'Runtime.getRuntime()', 'VpnService', 'startForegroundService',
                       'SharedPreferences', 'getSharedPreferences', 'getPreferences(', '.putString(', 'java.net.http',
                       'HttpURLConnection', 'URLConnection', 'Authenticator', 'getByName(', 'getAllByName(',
                       'SHA3-256', 'HexFormat', 'DefaultChannelId.newInstance', 'addJavascriptInterface',
                       'evaluateJavascript', 'loadData', 'javascript:', 'setWebContentsDebuggingEnabled(true)',
                       'getPrimaryClip', 'okhttp', 'Cronet', 'java.nio.channels.SocketChannel']:
        assert disallowed not in source, disallowed
    for name, raw in sources.items():
        text = without_comments(raw)
        network_types = set(re.findall(r'java\.net\.[A-Za-z0-9_*]+', text))
        expected_network = {
            'FixedOnionProbe': {'java.net.InetSocketAddress'},
            'OnionApiTransport': {'java.net.InetSocketAddress'},
            'AndroidTorSocket': {'java.net.InetAddress', 'java.net.InetSocketAddress'},
            'TorSocketEndpoint': {'java.net.SocketAddress'},
            'TorSocketChannel': {'java.net.SocketAddress'},
        }
        assert network_types == expected_network.get(name, set()), (name, network_types)
        if name not in {'FixedOnionProbe', 'OnionApiTransport', 'TorSocketChannel', 'TorSocketEventLoopGroup'}:
            assert 'io.netty.' not in text, ('network library outside reviewed transport', name)
        if name != 'AndroidTorSocket':
            for forbidden in ['Os.sendto(', 'Os.recvfrom(', 'Os.poll(', 'Os.shutdown(', 'LocalSocket(']:
                assert forbidden not in text, ('Unix socket I/O escaped authenticated adapter', name, forbidden)
        if name != 'TorTalkActivity':
            for api in ['WebView', 'WebMessagePort', 'EditText', 'getPreferences(', 'android.webkit.']:
                assert api not in text, (name, api)
        if name == 'ContactQrImage':
            assert set(re.findall(r'android\.net\.[A-Za-z0-9_*]+',text)) == {'android.net.Uri'}
        elif name == 'AndroidTorSocket':
            assert set(re.findall(r'android\.net\.[A-Za-z0-9_*]+',text)) == {'android.net.LocalSocket','android.net.LocalSocketAddress','android.net.Credentials'}
        else: assert 'android.net.' not in text or name == 'TorTalkActivity', name

    inset_host = re.sub(r'\s+', '', without_comments(sources['TorTalkInsets']))
    assert 'if(Build.VERSION.SDK_INT>=30)TorTalkInsetsApi30.install(activity,host);' in inset_host
    assert 'host.addView(content,newFrameLayout.LayoutParams(-1,-1));' in inset_host
    assert 'WindowInsets' not in inset_host and 'android.graphics.Insets' not in inset_host
    inset_adapter = re.sub(r'\s+', '', without_comments(sources['TorTalkInsetsApi30']))
    for rule in ['@TargetApi(30)', 'activity.getWindow().setDecorFitsSystemWindows(false);',
                 'safeTypes=WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout();',
                 'handledTypes=safeTypes|WindowInsets.Type.ime();', 'Insetsoccupied=insets.getInsets(handledTypes);',
                 'host.setPadding(occupied.left,occupied.top,occupied.right,occupied.bottom);',
                 'newWindowInsets.Builder(insets)', '.setInsets(handledTypes,Insets.NONE)',
                 '.setInsetsIgnoringVisibility(safeTypes,Insets.NONE)', '.setDisplayCutout(null)',
                 'host.requestApplyInsets();']:
        assert rule in inset_adapter, ('reviewed API30 inset adapter',rule)
    assert 'WindowInsets.CONSUMED' not in inset_adapter
    assert 'api26-insets-fixture' not in build, 'signature-only fixture must never enter Android source sets'

    probe = sources['FixedOnionProbe']; transport = sources['OnionApiTransport']; request = sources['OnionApiRequest']
    for text, target in [(probe, 'HOST'), (transport, 'request.host')]:
        compact = re.sub(r'\s+', '', text)
        assert 'AndroidTorSocket::new' in text
        assert '.channelFactory(()->newTorSocketChannel(endpoint,this::allowed,deadline,sockets))' in compact
        assert 'NioSocketChannel' not in text and '127.0.0.1' not in text
        assert 'resolver(NoopAddressResolverGroup.INSTANCE)' in text
        assert f'InetSocketAddress.createUnresolved({target}, 80)' in text
        assert len(re.findall(r'bootstrap\.connect\(', text)) == 1
        assert text.count('new Socks5ProxyHandler(endpoint)') == 1
        assert not re.search(r'\b(?:Socket|ServerSocket|DatagramSocket|URL|URI)\s*\(', text)
    assert 'xao2lxsmia2edq2n5zxg6uahx6xox2t7bfjw6b5vdzsxi7ezmqob6qid.onion' in probe
    assert 'HttpMethod.GET, "/"' in probe
    assert set(re.findall(r'request\.headers\(\)\.set\(HttpHeaderNames\.([A-Z_]+)', probe)) == {'HOST', 'CONTENT_LENGTH', 'CONNECTION'}
    assert 'HttpObjectAggregator(OnionApiRequest.MAX_RESPONSE_BYTES)' in transport and 'request.destroyBody()' in transport
    assert 'private static final Pattern ORIGIN = Pattern.compile("http://[a-z2-7]{56}\\\\.onion(?::80)?/?")' in request
    assert 'CREDENTIAL_ORIGIN_MISMATCH' in request and '!this.origin.equals(canonicalOrigin(boundOrigin))' in request
    assert 'MAX_REQUEST_BYTES = 64 * 1024' in request and 'MAX_RESPONSE_BYTES = 96 * 1024' in request
    assert 'REDIRECT_REFUSED' in sources['TorApiClient'] and 'controller.current(lease)' in sources['TorApiClient']

    ui = sources['TorTalkActivity']; compact_ui = re.sub(r'\s+', '', ui)
    account_queue = re.sub(r'\s+', '', without_comments(sources['AccountWorkQueue']))
    for required in ['privatestaticfinalExecutorWORKER=Executors.newSingleThreadExecutor(',
                     'staticfinalExecutorINSTANCE=task->WORKER.execute(task);',
                     'staticsynchronizedlongbeginCleanup(){restoreForbidden=true;return++cleanupGeneration;}',
                     'staticsynchronizedvoidcompleteCleanup(longgeneration){if(generation==cleanupGeneration)restoreForbidden=false;}',
                     'staticsynchronizedbooleanrestorationAllowed(){return!restoreForbidden;}']:
        assert required in account_queue, ('process-owned account cleanup queue',required)
    assert 'privatefinalExecutordisk=AccountWorkQueue.INSTANCE;' in compact_ui
    assert 'disk.shutdown' not in compact_ui and 'shutdown' not in account_queue
    assert 'storageBlocked=loginFailed||!AccountWorkQueue.restorationAllowed();' in compact_ui
    assert 'if(!configurationFailed&&!storageBlocked&&candidate!=null&&candidate.serverOnion.equals(api.configuredOnion()))' in compact_ui
    cleanup = re.sub(r'\s+', '', method_body(ui,'deleteStoredSession','String successMessage'))
    assert 'finallongcleanup=AccountWorkQueue.beginCleanup();storageBlocked=true;disk.execute(' in cleanup
    assert 'try{storage.clear();AccountWorkQueue.completeCleanup(cleanup);runOnUiThread(' in cleanup
    assert cleanup.count('AccountWorkQueue.completeCleanup(') == 1
    config_commit = re.sub(r'\s+', '', without_comments(method_body(ui,'commitServerConfig')))
    for required in ['if(changing)clearSession();', 'finallongcleanup=changing?AccountWorkQueue.beginCleanup():0;',
                     'try{serverConfig.save(canonical,code);saved=true;}',
                     'if(changing){try{storage.clear();AccountWorkQueue.completeCleanup(cleanup);}',
                     'if(!committed){', 'if(!api.configureOnion(canonical))']:
        assert required in config_commit, ('durable server origin commit',required)
    assert config_commit.index('serverConfig.save(') < config_commit.index('storage.clear();') < config_commit.index('runOnUiThread(')
    assert 'private static final String ORIGIN="https://appassets.androidplatform.net";' in ui
    assert 'private static final String PAGE=ORIGIN+"/ui/index.html";' in ui
    for required in ['setJavaScriptEnabled(true)', 'setAllowFileAccess(false)', 'setAllowContentAccess(false)',
                     'setAllowFileAccessFromFileURLs(false)', 'setAllowUniversalAccessFromFileURLs(false)',
                     'setBlockNetworkLoads(true)', 'setBlockNetworkImage(true)',
                     'setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW)', 'setDomStorageEnabled(false)',
                     'setDatabaseEnabled(false)', 'setCacheMode(WebSettings.LOAD_NO_CACHE)',
                     'setJavaScriptCanOpenWindowsAutomatically(false)', 'setSupportMultipleWindows(false)',
                     'setSaveFormData(false)', 'setGeolocationEnabled(false)', 'setWebContentsDebuggingEnabled(false)',
                     'setAcceptCookie(false)', 'setAcceptThirdPartyCookies(web,false)', 'request.deny()',
                     'handler.cancel()', 'IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS', 'FLAG_SECURE']:
        assert required in ui, ('missing WebView restriction', required)
    # Positive matching plus one setter occurrence prevents a later permissive override.
    for name in ['setJavaScriptEnabled', 'setAllowFileAccess', 'setAllowContentAccess', 'setAllowFileAccessFromFileURLs',
                 'setAllowUniversalAccessFromFileURLs', 'setBlockNetworkLoads', 'setBlockNetworkImage', 'setMixedContentMode',
                 'setDomStorageEnabled', 'setDatabaseEnabled', 'setCacheMode', 'setJavaScriptCanOpenWindowsAutomatically',
                 'setSupportMultipleWindows', 'setSaveFormData', 'setGeolocationEnabled', 'setWebContentsDebuggingEnabled',
                 'setAcceptCookie', 'setAcceptThirdPartyCookies']:
        assert len(re.findall(r'\b' + name + r'\(', ui)) == 1, ('duplicate WebView override', name)
    assert ui.count('web.loadUrl(') == 1 and 'web.loadUrl(PAGE)' in ui
    assert 'contentHost=TorTalkInsets.host(this,web);setContentView(contentHost);' in ui
    assert 'contentHost.removeAllViews();contentHost.addView(fallback,new FrameLayout.LayoutParams(-1,-1));' in ui
    assert ui.count('postWebMessage(') == 1
    assert 'web.postWebMessage(newWebMessage("tortalk-port-v1:"+document.nonce,newWebMessagePort[]{channel[1]}),Uri.parse(ORIGIN));' in compact_ui
    assert ui.count('Uri.parse(') == 1 and ui.count('createWebMessageChannel(') == 1
    assert 'if(destroyed||!foreground||document!=bridgeDocument||port!=nativePort||web==null||!PAGE.equals(web.getUrl())||message.getPorts()!=null&&message.getPorts().length!=0)return;' in compact_ui
    assert 'privatevolatileBridgeDocumentbridgeDocument;' in compact_ui
    assert 'nonce=UUID.randomUUID().toString().replace("-","");' in compact_ui
    assert 'synchronized(documentLock){document=newBridgeDocument(++documentGeneration);bridgeDocument=document;}' in compact_ui
    assert 'if(draftDocument!=document){draftLedger.reset();draftDocument=document;}' in compact_ui
    inject = re.sub(r'\s+', '', method_body(ui, 'assetBytes'))
    assert 'if(!path.equals("/ui/index.html"))returnstream;' in inject
    assert 'out.size()+n>16384' in inject
    assert '.replace("__TORTALK_BRIDGE_NONCE__",document.nonce)' in inject
    assert 'data.length()>UiCommandPolicy.MAX_COMMAND_CHARS||!JsonBounds.safe(data)' in compact_ui
    assert 'if(!UiCommandPolicy.allows(action))return;' in compact_ui
    assert compact_ui.count('shouldOverrideUrlLoading(') == 2
    assert re.search(r'shouldOverrideUrlLoading\([^)]*\)\{returntrue;\}', compact_ui)
    asset = re.sub(r'\s+', '', method_body(ui, 'asset'))
    for required in ['"GET".equals(request.getMethod())', '"https".equals(uri.getScheme())',
                     '"appassets.androidplatform.net".equals(uri.getHost())', 'uri.getPort()==-1',
                     'uri.getUserInfo()==null', 'uri.getQuery()==null', 'uri.getFragment()==null', '403,"Blocked"']:
        assert required in asset, ('asset boundary', required)
    assert set(re.findall(r'"(/ui/[^"\\]+)"\.equals\(path\)', asset)) == {'/ui/index.html', '/ui/styles.css', '/ui/native.css', '/ui/app.js'}
    assert '("/ui/index.html".equals(path)&&request.isForMainFrame())' in asset
    assert '||(!request.isForMainFrame()&&(' in asset
    assert set(assets) == {'index.html', 'styles.css', 'native.css', 'app.js'}, 'unreviewed packaged UI asset'
    assert 'loadedConfig=serverConfig.load();' in compact_ui
    assert 'if(config!=null)api.configureOnion(config.serverOnion);' in compact_ui
    config_store = re.sub(r'\s+', '', sources['ServerConfigStore'])
    assert 'newKeystoreBlobStore(context,"config")' in config_store
    assert 'blob.write(next,current->{if(!Arrays.equals(expected,current))thrownewKeystoreBlobStore.StorageException(KeystoreBlobStore.Code.IDENTITY_MISMATCH);});' in config_store

    # The only native->JS channels are explicitly projected view state and a plaintext draft clear.
    publish = method_body(ui, 'publish'); clear = method_body(ui, 'clearDraft')
    expected_fields = {'authenticated','accountId','connection','connected','busy','error','rooms','messages',
                       'historyLimited','historyCanOlder','historyCanNewer','historyPage','roomId','cryptoReady','peerId','sendClosed',
                       'historyOnly','protectionMessage'}
    assert 'state.put("historyOnly",localHistoryMode)' in publish
    assert 'state.put("protectionMessage",protectionMessage())' in publish
    assert set(re.findall(r'state\.put\("([^"\\]+)"', publish)) == expected_fields
    for forbidden in ['accountToken', 'sessionToken', 'deviceToken', 'registrationToken', 'pendingRecovery',
                      'identityKey', 'privateKey', 'publication', 'toString( session', 'getString(']:
        assert forbidden not in publish + clear, ('secret or raw response in JS projection', forbidden)
    assert ui.count('nativePort.postMessage(') == 2
    assert publish.count('nativePort.postMessage(new WebMessage(payload))') == 1
    assert 'new JSONObject().put("type","clear_draft").put("draftId",draft.revision).put("text",draft.text)' in clear
    assert '!draftLedger.current(draft)' in clear
    assert 'nativePort.close();nativePort=null;' in method_body(ui, 'closePort')
    assert 'HistoryWindow.of(route.equals("chat")?messages:new JSONArray(),historyEnd)' in publish
    assert 'state.put("messages",history.items)' in publish
    assert 0 <= publish.find('if(payload.length()>262144)return;') < publish.find('nativePort.postMessage(')
    history_projection = method_body(sources['HistoryWindow'],'of')
    history_compact = re.sub(r'\s+', '', without_comments(sources['HistoryWindow']))
    for rule in ['MAX_MESSAGES=40;', 'MAX_JSON_CHARACTERS=196608;',
                 'for(inti=end-1;i>=0&&reverse.size()<MAX_MESSAGES;i--)',
                 'item.toString().length()+(reverse.isEmpty()?0:1)',
                 'if(characters+next>MAX_JSON_CHARACTERS)break;',
                 'reverse.add(item);characters+=next;start=i;',
                 'if(end>0&&reverse.isEmpty())throwinvalid();',
                 'for(inti=reverse.size()-1;i>=0;i--)items.put(reverse.get(i));',
                 'returnnewHistoryWindow(start,end,total,items);',
                 '!(textinstanceofString)||((String)text).length()>4096',
                 '!UiCommandPolicy.validOpaqueId((String)id)', '!validStatus((String)status)']:
        assert rule in history_compact, ('measured contiguous history projection',rule)
    assert set(re.findall(r'\.put\("([^"\\]+)"',history_projection)) == {'id','text','own','status'}
    assert 'newerHistory.push(historyEnd);historyEnd=page.start;' in ui
    assert 'historyEnd=newerHistory.isEmpty()?-1:newerHistory.pop();' in ui
    assert 'if(!route.equals("chat"))state.put("rooms",rooms)' in publish
    room_projection = method_body(sources['NativeResponseRules'], 'rooms')
    assert 'return NativeResponseRules.rooms(source,session.accountId);' in method_body(ui, 'projectRooms')
    projected = room_projection + history_projection
    assert 'new JSONObject().put("id",id).put("text",text).put("own",own).put("status",status)' in history_projection
    room_compact = re.sub(r'\s+', '', room_projection)
    assert 'newJSONObject().put("room_id",id).put("peer_id",account.equals(a)?b:a).put("send_until",until).put("closed",closed)' in room_compact
    assert 'members.length()!=2' in room_compact and '!account.equals(a)&&!account.equals(b)' in room_compact
    assert 'source.length()>512' in room_compact and '!seen.add(id)' in room_compact
    assert set(re.findall(r'\.put\("([^"\\]+)"', room_projection)) == {'room_id','peer_id','send_until','closed'}
    session_rule = method_body(sources['NativeResponseRules'], 'session')
    assert '!requested.equals(string(response,"account_id"))' in session_rule
    assert 'new AppSessionStore.Session(origin,requested,"","","",string(response,"session_token"))' in session_rule
    draft_rule = re.sub(r'\s+', '', sources['DraftLedger'])
    for rule in ['entries.size()>64','number<=highest','!entry.room.equals(room)||!entry.text.equals(text)',
                 'entry.document==document&&entries.get(entry.revision)==entry','entries.clear();highest=0;document++']:
        assert rule in draft_rule, ('draft idempotency/lifetime rule',rule)
    assert 'draftLedger.bind(draftId,selectedRoom,text)' in ui
    for forbidden in ['accountToken','sessionToken','deviceToken','registrationToken','pendingRecovery','identityKey','privateKey']:
        assert forbidden not in projected
    js = assets['app.js']; html = assets['index.html']; web = '\n'.join(assets.values())
    for forbidden in ['fetch(', 'XMLHttpRequest', 'WebSocket', 'EventSource', 'sendBeacon', 'localStorage',
                      'sessionStorage', 'indexedDB', 'document.cookie', 'window.open(', 'eval(', 'new Function(',
                      'account_token', 'session_token', 'device_token', 'registration_token', 'accountToken',
                      'sessionToken', 'deviceToken', 'registrationToken', 'pendingRecoveryToken',
                      'oldAccountToken', 'replacementAccountToken', 'oldCapability', 'replacement_account_token']:
        assert forbidden not in web, ('network/storage/secret in packaged UI', forbidden)
    js_compact = re.sub(r'\s+', '', js)
    # Android's native postWebMessage sender is empty/opaque, not its target origin.
    # The per-document nonce authenticates this bootstrap together with trusted/null
    # native source, top-level exact page, one transferred port and stale-port rejection.
    assert "if(location.href!=='https://appassets.androidplatform.net/ui/index.html'||window.top!==window||e.isTrusted!==true||e.source!==null||(e.origin!==''&&e.origin!=='null')||!/^[a-f0-9]{32}$/.test(bridgeNonce)||e.data!=='tortalk-port-v1:'+bridgeNonce||e.ports.length!==1)return;" in js_compact
    assert "constnonceMeta=document.querySelector('meta[name=\"tortalk-bridge-nonce\"]');" in js_compact
    assert "constbridgeNonce=nonceMeta?nonceMeta.content:'';" in js_compact
    assert 'if(port){port.onmessage=null;port.close();}port=e.ports[0];constselectedPort=port;' in js_compact
    assert 'port.onmessage=event=>{if(port!==selectedPort)return;' in js_compact
    assert html.count('__TORTALK_BRIDGE_NONCE__') == 1
    assert '<meta name="tortalk-bridge-nonce" content="__TORTALK_BRIDGE_NONCE__">' in html
    for required in ["default-src 'none'", "script-src 'self'", "connect-src 'none'", "frame-src 'none'",
                     "child-src 'none'", "object-src 'none'", "base-uri 'none'", "form-action 'none'"]:
        assert required in html, ('CSP missing', required)
    assert re.findall(r'<script\s+src="([^"]+)"\s+defer></script>', html) == ['/ui/app.js']
    assert html.count('<script') == 1 and 'unsafe-inline' not in html and 'unsafe-eval' not in html
    assert 'escape(m.text)' in js and 'escape(r.peer_id' in js
    for required in ['org.signal:libsignal-android:0.105.0', 'isCoreLibraryDesugaringEnabled = true',
                     'jniLibs.keepDebugSymbols += "**/libtor.so"', 'jniLibs.excludes += "**/libsignal_jni_testing.so"',
                     'ndkVersion = "27.3.13750724"', 'ndkPath = signalNdkDir.absolutePath',
                     'providers.environmentVariable("ANDROID_HOME").orNull',
                     'file("$it/ndk/27.3.13750724")', 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip',
                     'check(signalNdkProperties.isFile && signalStripTool.isFile && signalStripTool.canExecute())',
                     'check(signalStripTool.canonicalFile.toPath().startsWith(signalNdkDir.canonicalFile.toPath()))',
                     'check(actual == expected) { "Signal strip toolchain bytes changed;',
                     SIGNAL_STRIP_TOOL_SHA256, SIGNAL_STRIP_PROPERTIES_SHA256,
                     'libsignal_jni_*.so', 'libsignal_jni_*.dylib', 'signal_jni_*.dll', SIGNAL_AAR_SHA256,
                     '520a16906c45e1629ed05c98b12556322ccc489a82016bba921954e312c882c4']:
        assert required in build, ('Signal packaging rule', required)
    assert re.findall(r'jniLibs\.keepDebugSymbols\s*\+=\s*"([^"]+)"',build) == ['**/libtor.so'], 'unreviewed native strip exclusion'
    assert build.index('check(signalNdkProperties.isFile') < build.index('android {'), 'NDK must be checked before Android configuration'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--aar', type=Path, help='Resolved official tor-android 0.4.9.13.2 AAR')
    parser.add_argument('--signal-aar', type=Path, help='Resolved official libsignal-android 0.105.0 AAR')
    parser.add_argument('--apk', type=Path, help='Built unsigned debug APK')
    parser.add_argument('--merged-manifest', type=Path, help='Plain XML emitted by apkanalyzer manifest print APK')
    args = parser.parse_args()
    strip_provenance(SIGNAL_STRIP_REPORT.read_bytes())
    manifest(ROOT / 'src/main/AndroidManifest.xml')
    rules = ET.parse(ROOT / 'src/main/res/xml/backup_exclusions.xml').getroot()
    for kind in ['cloud-backup', 'device-transfer']:
        section = rules.find(kind)
        assert section is not None
        assert {(x.get('domain'), x.get('path')) for x in section.findall('exclude')} == {(domain, '.') for domain in ['root', 'file', 'database', 'sharedpref', 'external']}
    build = (ROOT / 'build.gradle.kts').read_text()
    assert 'tor-android:0.4.9.13.2' in build and 'jtorctl:0.4.5.7' in build
    assert 'signingConfig = null' in build and 'it.enable = false' in build
    assert 'release(37)' in build and 'minorApiLevel = 1' in build
    sources = {p.stem:p.read_text() for p in (ROOT / 'src/main/java').rglob('*.java')}
    assets = {p.name:p.read_text() for p in (ROOT / 'src/main/assets/ui').iterdir() if p.is_file()}
    source_boundaries(sources, assets, build)
    source = '\n'.join(sources.values())
    for asset, digest in {
        'libsignal-LICENSE.txt': '0d96a4ff68ad6d4b6f1f30f713b18d5184912ba8dd389f86aa7710db079abcb0',
        'libsignal-NOTICE.md': '282ecac53096f9d43924095080c54fdfdcbb036ebd20037ce1ea6a907cf48ad3',
        'zxing-LICENSE.txt': '3f62881f0566227a24b12e5a754cc79f39aaa94883038e95c94812e1f50af42f',
        'netty-LICENSE.txt': 'aac73b3148f6d1d7111dbca32099f68d26c644c6813ae1e4f05f6579aa2663fe',
        'netty-NOTICE.txt': '64e368e79be71463922ebb102b33088667cf975edeed8df7f68efe9f89835ca1',
    }.items():
        assert hashlib.sha256((ROOT / 'src/main/assets' / asset).read_bytes()).hexdigest() == digest, asset
    assert 'context.unbindService(attempt)' in sources['EmbeddedTorController']
    assert 'SocksPort 0' in source and 'SocksPort 127.0.0.1' not in source
    assert 'HTTPTunnelPort 0' in source and 'DNSPort 0' in source and 'ControlPort 0' in source
    assert 'controller.stop();' in source and 'FLAG_SECURE' in source
    assert 'getInfo("status/circuit-established")' in source and 'getInfo("net/listeners/socks")' in source
    assert 'getInfo("status/bootstrap-phase")' in source
    diagnostics = sources['DiagnosticLog']
    for forbidden in ['java.io', 'java.net', 'android.util.Log', 'Throwable', 'Exception', 'getMessage(', 'getStackTrace(', 'setPrimaryClip']:
        if forbidden in ['Throwable', 'Exception']:
            assert not re.search(r'\b' + forbidden + r'\s+[a-zA-Z_]', re.sub(r'/\*.*?\*/|//[^\n]*', '', diagnostics, flags=re.S))
        else: assert forbidden not in diagnostics, forbidden
    assert 'MAX_ENTRIES = 128' in diagnostics and 'MAX_CHARACTERS = 8192' in diagnostics
    activity = sources['TorTestActivity']
    assert activity.count('clipboard.setPrimaryClip(clip)') == 1
    assert 'diagnostics.snapshot()' in activity and 'setPositiveButton(R.string.log_copy_confirm' in activity
    assert 'getPrimaryClip' not in activity and 'setSaveEnabled(false)' in activity
    assert 'android.content.extra.IS_SENSITIVE' in activity and 'FLAG_SECURE' in activity
    print('PASS: bounded diagnostics, explicit copy, no clipboard reads or diagnostic storage/network')
    print('PASS: narrow reviewed UI, native secret projection, Tor-only transport and source/manifest boundaries')
    if args.aar:
        assert hashlib.sha256(args.aar.read_bytes()).hexdigest() == AAR_SHA256, 'Tor AAR digest mismatch'
        native_hashes(args.aar, 'jni', {'libtor.so':TOR_NATIVE})
        print('PASS: official pinned Tor AAR and four ABI byte hashes')
    if args.signal_aar:
        assert hashlib.sha256(args.signal_aar.read_bytes()).hexdigest() == SIGNAL_AAR_SHA256, 'Signal AAR digest mismatch'
        native_hashes(args.signal_aar, 'jni', {'libsignal_jni.so':SIGNAL_NATIVE}, signal_source=True)
        with zipfile.ZipFile(args.signal_aar) as archive: signal_notice(archive)
        print('PASS: official pinned Signal AAR and four production ABI byte hashes')
    if args.apk:
        assert args.merged_manifest, '--apk requires --merged-manifest'
        native_hashes(args.apk, 'lib', {'libtor.so':TOR_NATIVE, 'libsignal_jni.so':SIGNAL_PACKAGED_NATIVE})
        unsigned_apk(args.apk)
        print('PASS: unsigned APK; exact original Tor and verified NDK-stripped Signal hashes across four ABIs; no desktop/test JNI')
    if args.merged_manifest:
        manifest(args.merged_manifest, merged=True)
        print('PASS: merged manifest permissions, private Tor service and backup restriction')
    print('NOT TESTED HERE: Android native loading, Tor bootstrap, Keystore/runtime lifecycle, real onion exchange or two-device E2EE')

if __name__ == '__main__': main()
