#!/usr/bin/env python3
"""Mutation regression tests for the reviewed source and APK inventory boundaries."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET
import zipfile

spec = importlib.util.spec_from_file_location('verify_embedded_tor', Path(__file__).with_name('verify_embedded_tor.py'))
v = importlib.util.module_from_spec(spec); spec.loader.exec_module(v)


class SourceBoundaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = {p.stem:p.read_text() for p in (v.ROOT / 'src/main/java').rglob('*.java')}
        cls.assets = {p.name:p.read_text() for p in (v.ROOT / 'src/main/assets/ui').iterdir() if p.is_file()}
        cls.build = (v.ROOT / 'build.gradle.kts').read_text()
        v.source_boundaries(cls.sources, cls.assets, cls.build)

    def test_unix_private_socket_directory_required(self): self.mutation('TorSocketPaths','"tor-socks"','"shared-socks"')
    def test_unix_private_socket_mode_required(self): self.mutation('TorSocketPaths','Os.mkdir(directory.getPath(), 0700)','Os.mkdir(directory.getPath(), 0777)')
    def test_unix_socket_owner_required(self): self.mutation('TorSocketPaths','info.st_uid != uid','false')
    def test_unix_socket_group_access_required(self): self.mutation('TorSocketPaths','(info.st_mode & 0077) != 0','false')
    def test_unix_socket_type_required(self): self.mutation('TorSocketPaths','!OsConstants.S_ISSOCK(info.st_mode)','false')
    def test_unix_socket_directory_type_required(self): self.mutation('TorSocketPaths','!OsConstants.S_ISDIR(info.st_mode)','false')
    def test_unix_filesystem_namespace_required(self): self.mutation('AndroidTorSocket','LocalSocketAddress.Namespace.FILESYSTEM','LocalSocketAddress.Namespace.ABSTRACT')
    def test_unix_kernel_credentials_required(self): self.mutation('AndroidTorSocket','peer = socket.getPeerCredentials();','peer = null;')
    def test_unix_no_missing_credentials_required(self): self.mutation('AndroidTorSocket','if (peer == null) throw new IOException("TOR_PEER_UNAVAILABLE");','')
    def test_unix_peer_uid_required(self): self.mutation('TorSocketEndpoint','return uid == peerUid && pid == peerPid;','return pid == peerPid;')
    def test_unix_peer_pid_required(self): self.mutation('TorSocketEndpoint','return uid == peerUid && pid == peerPid;','return uid == peerUid;')
    def test_unix_nonblocking_flag_required(self): self.mutation('AndroidTorSocket','MSG_DONTWAIT = 0x40','MSG_DONTWAIT = 0')
    def test_unix_no_sigpipe_required(self): self.mutation('AndroidTorSocket','MSG_DONTWAIT | MSG_NOSIGNAL','MSG_DONTWAIT')
    def test_unix_connect_cap_required(self): self.mutation('TorSocketChannel','CONNECT_MAX_MS = 1_000','CONNECT_MAX_MS = 60_000')
    def test_unix_post_poll_send_fence_required(self): self.mutation('TorSocketChannel','synchronized (descriptorLock) {\n                check();\n                int count = socket.write','synchronized (descriptorLock) {\n                int count = socket.write')
    def test_unix_shutdown_before_close_required(self): self.mutation('TorSocketChannel','owned.shutdown(); owned.close();','owned.close();')
    def test_unix_no_watchdog_close_required(self): self.mutation('TorSocketChannel','if (socket != null) socket.shutdown();','if (socket != null) socket.close();')
    def test_unix_deadline_check_required(self): self.mutation('TorSocketChannel','System.nanoTime() >= deadline','false')
    def test_unix_lease_before_write_required(self): self.mutation('TorSocketChannel','!ready.getAsBoolean()','false')
    def test_unix_pid_immutable_required(self): self.mutation('TorReadinessGate','processPid != 0 && processPid != sampledPid','false')
    def test_unix_hardwired_listener_match_required(self): self.mutation('TorReadinessGate','candidate.controlListener().equals(listeners)','true')
    def test_unix_ipc_version_required(self): self.mutation('TorProcessProtocol','VERSION = 2','VERSION = 1')
    def test_unix_ipc_pid_required(self): self.mutation('TorProcessProtocol','intBetween(data, "pid", 1, Integer.MAX_VALUE)','true')
    def test_unix_ipc_shape_required(self): self.mutation('TorProcessProtocol','validBase(data, 8)','validBase(data, 9)')
    def test_unix_offline_no_tcp_start_required(self): self.mutation('TorProcessService','SocksPort 0\\n','SocksPort 127.0.0.1:auto\\n')
    def test_unix_one_configured_listener_required(self): self.mutation('TorProcessService','configured.size() != 1','configured.size() < 1')
    def test_unix_config_exact_path_required(self): self.mutation('TorProcessService','!expectedConfiguration.equals(configured.get(0).value)','false')
    def test_unix_socket_before_ready_required(self): self.mutation('TorProcessService','if (socksReady == 1) TorSocketPaths.verify(new TorSocketEndpoint(socksPath, ownUid, ownPid));','')

    def test_unix_exact_child_cleanup_required(self): self.mutation('TorSocketChannel','owner.shutdownGracefully(0,1,TimeUnit.SECONDS).syncUninterruptibly();','')
    def test_unix_early_api_channel_retained(self): self.mutation('OnionApiTransport','channel = (TorSocketChannel)connection.channel();','')
    def test_unix_early_probe_channel_retained(self): self.mutation('FixedOnionProbe','channel = (TorSocketChannel)connection.channel();','')

    def test_unix_unregistered_channel_cleanup_required(self): self.mutation('TorSocketChannel','unsafe().closeForcibly(); return;','close(); return;')
    def test_unix_abort_submission_state_required(self): self.mutation('TorSocketChannel','private boolean closeSubmitted;','private boolean unrelated;')
    def test_unix_abort_idempotency_required(self): self.mutation('TorSocketChannel','if (closeSubmitted || !open) return;','if (!open) return;')
    def test_unix_abort_closed_channel_guard_required(self): self.mutation('TorSocketChannel','if (closeSubmitted || !open) return;','if (closeSubmitted) return;')
    def test_unix_abort_revocation_precedes_submission(self): self.mutation_method('TorSocketChannel','abort','aborted = true;','')
    def test_unix_abort_submission_publication_required(self): self.mutation('TorSocketChannel','closeSubmitted = true;','')
    def test_unix_abort_submission_cannot_reset(self): self.mutation('TorSocketChannel','closeSubmitted = true;','closeSubmitted = false;')
    def test_unix_abort_submission_stays_under_monitor(self): self.mutation_method('TorSocketChannel','abort','            close(); // Owner closes','        }\n        close();\n        synchronized (descriptorLock) { // Owner closes')
    def test_unix_abort_no_future_wait_under_monitor(self): self.mutation_method('TorSocketChannel','abort','            close(); // Owner closes','            close().syncUninterruptibly(); // Owner closes')
    def test_unix_group_cleanup_finally_required(self): self.mutation_method('TorSocketChannel','shutdown','} finally {\n            if (group != null)','} catch (RuntimeException ignored) {\n            if (group != null)')

    def test_unix_shutdown_wakeup_guard_required(self): self.mutation('TorSocketEventLoopGroup','return isShuttingDown() ? null : super.takeTask();','return super.takeTask();')
    def test_unix_single_owner_required(self): self.mutation('TorSocketEventLoopGroup','super(1);','super(0);')
    def test_unix_api_safe_group_required(self): self.mutation('OnionApiTransport','new TorSocketEventLoopGroup()','new OioEventLoopGroup(1)')
    def test_unix_probe_safe_group_required(self): self.mutation('FixedOnionProbe','new TorSocketEventLoopGroup()','new OioEventLoopGroup(1)')

    def test_current_reviewed_source_passes(self):
        v.source_boundaries(self.sources, self.assets, self.build)

    def build_mutation(self, old, new):
        self.assertIn(old,self.build)
        with self.assertRaises(AssertionError):
            v.source_boundaries(self.sources,self.assets,self.build.replace(old,new,1))

    def test_unstripped_signal_packaging_rejected(self): self.build_mutation('jniLibs.keepDebugSymbols += "**/libtor.so"','jniLibs.keepDebugSymbols += "**/libtor.so"\n        jniLibs.keepDebugSymbols += "**/libsignal_jni.so"')
    def test_tor_stripping_rejected(self): self.build_mutation('jniLibs.keepDebugSymbols += "**/libtor.so"','')
    def test_different_ndk_version_rejected(self): self.build_mutation('ndkVersion = "27.3.13750724"','ndkVersion = "29.0.14206865"')
    def test_different_ndk_path_rejected(self): self.build_mutation('ndkPath = signalNdkDir.absolutePath','ndkPath = "/unreviewed/toolchain"')
    def test_missing_preinstalled_tool_check_rejected(self): self.build_mutation('check(signalNdkProperties.isFile && signalStripTool.isFile && signalStripTool.canExecute())','check(true)')
    def test_external_tool_symlink_rejected(self): self.build_mutation('check(signalStripTool.canonicalFile.toPath().startsWith(signalNdkDir.canonicalFile.toPath()))','check(true)')
    def test_changed_strip_tool_pin_rejected(self): self.build_mutation(v.SIGNAL_STRIP_TOOL_SHA256,'0'*64)
    def test_changed_ndk_properties_pin_rejected(self): self.build_mutation(v.SIGNAL_STRIP_PROPERTIES_SHA256,'0'*64)
    def test_missing_toolchain_digest_check_rejected(self): self.build_mutation('check(actual == expected) { "Signal strip toolchain bytes changed;','check(true) { "Signal strip toolchain bytes changed;')

    def mutation(self, file, old, new, assets=False):
        source, web = self.sources.copy(), self.assets.copy()
        target = web if assets else source
        self.assertTrue(old in target[file], 'test fixture changed; review mutation: '+file+' '+old)
        target[file] = target[file].replace(old, new, 1)
        with self.assertRaises(AssertionError): v.source_boundaries(source, web, self.build)

    def mutation_method(self, file, method, old, new, parameters=None):
        source = self.sources.copy(); body = v.method_body(source[file],method,parameters)
        self.assertTrue(old in body,'method fixture changed: '+method+' '+old)
        source[file] = source[file].replace(body,body.replace(old,new,1),1)
        with self.assertRaises(AssertionError): v.source_boundaries(source,self.assets,self.build)

    def test_write_file_sync_required(self): self.mutation_method('KeystoreBlobStore','writeLocked','output.getFD().sync();','')
    def test_commit_attempt_marked_before_finish(self): self.mutation_method('KeystoreBlobStore','writeLocked','commitAttempted = true;\n            file.finishWrite(output);','file.finishWrite(output);\n            commitAttempted = true;')
    def test_no_uncertain_commit_rollback(self): self.mutation_method('KeystoreBlobStore','writeLocked','if (!commitAttempted)','if (true)')
    def test_no_delete_after_commit_attempt(self): self.mutation_method('KeystoreBlobStore','writeLocked','file.finishWrite(output);','file.finishWrite(output);file.delete();')
    def test_committed_envelope_equality_required(self): self.mutation_method('KeystoreBlobStore','writeLocked','!Arrays.equals(envelope, committed)','false')
    def test_write_parent_directory_sync_required(self): self.mutation_method('KeystoreBlobStore','writeLocked','syncDirectory(file.getBaseFile().getParentFile());','')
    def test_recovered_read_file_sync_required(self): self.mutation_method('KeystoreBlobStore','readEnvelope','input.getFD().sync();','')
    def test_recovered_read_directory_sync_required(self): self.mutation_method('KeystoreBlobStore','readLocked','syncDirectory(file.getBaseFile().getParentFile());','')
    def test_read_never_generates_replacement_key(self): self.mutation_method('KeystoreBlobStore','readLocked','key(false)','key(true)')
    def test_directory_no_follow_required(self): self.mutation_method('KeystoreBlobStore','syncDirectory','OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW','OsConstants.O_RDONLY')
    def test_directory_type_check_required(self): self.mutation_method('KeystoreBlobStore','syncDirectory','OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)','true')
    def test_directory_fsync_required(self): self.mutation_method('KeystoreBlobStore','syncDirectory','Os.fsync(descriptor);',';')
    def test_directory_close_failure_is_error(self): self.mutation_method('KeystoreBlobStore','syncDirectory','catch (ErrnoException | RuntimeException ignored) { failed = true; }','catch (ErrnoException | RuntimeException ignored) { failed = false; }')
    def test_directory_failure_cannot_be_success(self): self.mutation_method('KeystoreBlobStore','syncDirectory','if (failed) throw failure(Code.IO_FAILED);','if (false) throw failure(Code.IO_FAILED);')
    def test_locked_file_validity_required(self): self.mutation_method('KeystoreBlobStore','locked','if (!lock.isValid()) throw failure(Code.IO_FAILED);','')
    def test_ratchet_commit_requires_compare_and_save(self): self.mutation('SignalClient','storage.compareAndSave(s.original, s.encode());','storage.save(s.encode());')
    def test_storage_cas_comparison_required(self): self.mutation('MessageCoordinator','!Arrays.equals(expected,previous)','false')

    def test_web_file_access_fails(self): self.mutation('TorTalkActivity','setAllowFileAccess(false)','setAllowFileAccess(true)')
    def test_web_content_access_fails(self): self.mutation('TorTalkActivity','setAllowContentAccess(false)','setAllowContentAccess(true)')
    def test_web_network_access_fails(self): self.mutation('TorTalkActivity','setBlockNetworkLoads(true)','setBlockNetworkLoads(false)')
    def test_web_mixed_content_fails(self): self.mutation('TorTalkActivity','MIXED_CONTENT_NEVER_ALLOW','MIXED_CONTENT_ALWAYS_ALLOW')
    def test_web_late_override_fails(self): self.mutation('TorTalkActivity','settings.setAllowFileAccess(false);','settings.setAllowFileAccess(false);settings.setAllowFileAccess(true);')
    def test_cookie_persistence_fails(self): self.mutation('TorTalkActivity','setAcceptCookie(false)','setAcceptCookie(true)')
    def test_javascript_interface_fails(self): self.mutation('TorTalkActivity','setContentView(contentHost);','web.addJavascriptInterface(this,"unsafe");setContentView(contentHost);')
    def test_eval_bridge_fails(self): self.mutation('TorTalkActivity','setContentView(contentHost);','web.evaluateJavascript("unsafe",null);setContentView(contentHost);')
    def test_wildcard_port_origin_fails(self): self.mutation('TorTalkActivity','Uri.parse(ORIGIN)','Uri.parse("*")')
    def test_missing_incoming_origin_check_fails(self): self.mutation('TorTalkActivity','||!PAGE.equals(web.getUrl())','')
    def test_unknown_asset_fails(self): self.mutation('TorTalkActivity','"/ui/app.js".equals(path)','"/ui/remote.js".equals(path)')
    def test_missing_query_guard_fails(self): self.mutation('TorTalkActivity','uri.getQuery()==null','true')
    def test_secret_view_field_fails(self): self.mutation('TorTalkActivity','state.put("accountId",session==null?"":session.accountId);','state.put("accountId",session==null?"":session.accountToken);')
    def test_raw_response_projection_fails(self): self.mutation('TorTalkActivity','state.put("messages",history.items)','state.put("messages",messages)')
    def test_secret_preference_fails(self): self.mutation('TorTalkActivity','serverConfig.save(canonical,code);','getPreferences(MODE_PRIVATE).edit().putString("session_token",session.sessionToken).apply();serverConfig.save(canonical,code);')
    def test_new_preference_write_fails(self): self.mutation('ServerConfigStore','blob.write(next,current->','preferences.putString("secret",token);blob.write(next,current->')
    def test_other_class_webview_fails(self): self.mutation('SignalProfile','final class SignalProfile {','final class SignalProfile { android.webkit.WebView unsafe;')
    def test_parser_only_java_uri_not_blanket_allowed(self): self.mutation('SignalProfile','final class SignalProfile {','final class SignalProfile { java.net.URI unreviewed;')
    def test_other_class_socket_fails(self): self.mutation('SignalProfile','final class SignalProfile {','final class SignalProfile { java.net.Socket unsafe;')
    def test_per_activity_queue_rejected(self): self.mutation('TorTalkActivity','disk=AccountWorkQueue.INSTANCE','disk=Executors.newSingleThreadExecutor()')
    def test_account_queue_parallelism_rejected(self): self.mutation('AccountWorkQueue','Executors.newSingleThreadExecutor(','Executors.newCachedThreadPool(')
    def test_cleanup_generation_match_required(self): self.mutation('AccountWorkQueue','if(generation==cleanupGeneration)','if(true)')
    def test_cleanup_restore_fence_required(self): self.mutation('AccountWorkQueue','restoreForbidden=true;','restoreForbidden=false;')
    def test_startup_cleanup_fence_required(self): self.mutation('TorTalkActivity','storageBlocked=loginFailed||!AccountWorkQueue.restorationAllowed();','storageBlocked=loginFailed;')
    def test_cleanup_fence_before_enqueue_required(self): self.mutation_method('TorTalkActivity','deleteStoredSession','AccountWorkQueue.beginCleanup()','0','String successMessage')
    def test_cleanup_success_before_restore_required(self): self.mutation_method('TorTalkActivity','deleteStoredSession','storage.clear();AccountWorkQueue.completeCleanup(cleanup);','AccountWorkQueue.completeCleanup(cleanup);storage.clear();','String successMessage')
    def test_origin_cleanup_before_activity_callback_required(self): self.mutation_method('TorTalkActivity','commitServerConfig','storage.clear();','runOnUiThread(()->{});storage.clear();')
    def test_origin_commit_requires_encrypted_store(self): self.mutation_method('TorTalkActivity','commitServerConfig','serverConfig.save(canonical,code);','')
    def test_server_config_cas_required(self): self.mutation('ServerConfigStore','!Arrays.equals(expected,current)','false')
    def test_transport_dns_fails(self): self.mutation('OnionApiTransport','import java.net.InetSocketAddress;','import java.net.InetSocketAddress; import java.net.Socket;')
    def test_transport_resolving_onion_fails(self): self.mutation('OnionApiTransport','InetSocketAddress.createUnresolved(request.host, 80)','new InetSocketAddress(request.host, 80)')
    def test_transport_default_resolver_fails(self): self.mutation('OnionApiTransport','NoopAddressResolverGroup.INSTANCE','DefaultAddressResolverGroup.INSTANCE')
    def test_transport_second_connect_fails(self): self.mutation('OnionApiTransport','while (true) {','bootstrap.connect(unsafe); while (true) {')
    def test_transport_new_channel_id_fails(self): self.mutation('TorSocketChannel','new PrivateChannelId()','DefaultChannelId.newInstance()')
    def test_js_fetch_fails(self): self.mutation('app.js',"'use strict';","'use strict';fetch('/unsafe');",True)
    def test_js_storage_fails(self): self.mutation('app.js',"'use strict';","'use strict';localStorage.setItem('secret','x');",True)
    def test_js_secret_field_fails(self): self.mutation('app.js',"'use strict';","'use strict';state.sessionToken='x';",True)
    def test_js_origin_guard_fails(self): self.mutation('app.js',"(e.origin!==''&&e.origin!=='null')","false",True)
    def test_csp_network_fails(self): self.mutation('index.html',"connect-src 'none'","connect-src https:",True)
    def test_inline_script_fails(self): self.mutation('index.html','</head>','<script>unsafe()</script></head>',True)
    def test_room_projection_requires_membership(self): self.mutation('NativeResponseRules','!account.equals(a)&&!account.equals(b)','false')
    def test_room_projection_cannot_add_secret_field(self): self.mutation('NativeResponseRules','.put("closed",closed)','.put("closed",closed).put("token",account)')
    def test_draft_immutable_room_binding(self): self.mutation('DraftLedger','!entry.room.equals(room)||!entry.text.equals(text)','false')
    def test_draft_document_lifetime_required(self): self.mutation('DraftLedger','entry.document==document&&entries.get(entry.revision)==entry','entries.get(entry.revision)==entry')
    def test_clear_draft_requires_current_document(self): self.mutation('TorTalkActivity','||!draftLedger.current(draft)','')
    def test_native_document_guard_required(self): self.mutation('TorTalkActivity','document!=bridgeDocument||','')
    def test_native_port_guard_required(self): self.mutation('TorTalkActivity','port!=nativePort||','')
    def test_native_handshake_nonce_required(self): self.mutation('TorTalkActivity','"tortalk-port-v1:"+document.nonce','"tortalk-port-v1:constant"')
    def test_document_nonce_injection_required(self): self.mutation('TorTalkActivity','.replace("__TORTALK_BRIDGE_NONCE__",document.nonce)','.replace("__TORTALK_BRIDGE_NONCE__","constant")')
    def test_document_nonce_fresh_generation_required(self): self.mutation('TorTalkActivity','nonce=UUID.randomUUID().toString().replace("-","");','nonce="00000000000000000000000000000000";')
    def test_main_frame_html_guard_required(self): self.mutation('TorTalkActivity','"/ui/index.html".equals(path)&&request.isForMainFrame()','"/ui/index.html".equals(path)&&true')
    def test_asset_userinfo_guard_required(self): self.mutation('TorTalkActivity','uri.getUserInfo()==null','true')
    def test_js_trusted_event_guard_required(self): self.mutation('app.js','e.isTrusted!==true','false',True)
    def test_js_null_source_guard_required(self): self.mutation('app.js','e.source!==null','false',True)
    def test_js_top_frame_guard_required(self): self.mutation('app.js','window.top!==window','false',True)
    def test_js_nonce_match_required(self): self.mutation('app.js',"e.data!=='tortalk-port-v1:'+bridgeNonce",'false',True)
    def test_js_one_port_required(self): self.mutation('app.js','e.ports.length!==1','e.ports.length!==2',True)
    def test_js_stale_port_guard_required(self): self.mutation('app.js','if(port!==selectedPort)return;','',True)
    def test_document_nonce_placeholder_unique(self): self.mutation('index.html','</head>','__TORTALK_BRIDGE_NONCE__</head>',True)
    def test_draft_reset_requires_new_document(self): self.mutation('TorTalkActivity','if(draftDocument!=document)','if(false)')
    def test_message_projection_omitted_outside_chat(self): self.mutation('TorTalkActivity','HistoryWindow.of(route.equals("chat")?messages:new JSONArray(),historyEnd)','HistoryWindow.of(messages,historyEnd)')
    def test_history_measures_encoded_json(self): self.mutation('HistoryWindow','item.toString().length()','((String)text).length()')
    def test_history_json_budget_cannot_expand(self): self.mutation('HistoryWindow','MAX_JSON_CHARACTERS=196608','MAX_JSON_CHARACTERS=500000')
    def test_history_budget_cannot_be_skipped(self): self.mutation('HistoryWindow','if(characters+next>MAX_JSON_CHARACTERS)break;','if(false)break;')
    def test_history_page_end_cannot_skip_messages(self): self.mutation('TorTalkActivity','newerHistory.push(historyEnd);historyEnd=page.start;','newerHistory.push(historyEnd);historyEnd-=40;')
    def test_history_raw_source_cannot_be_projected(self): self.mutation('HistoryWindow','new JSONObject().put("id",id).put("text",text).put("own",own).put("status",status)','raw')
    def test_bridge_outbound_budget_required(self): self.mutation('TorTalkActivity','if(payload.length()>262144)return;','')
    def test_malformed_text_cannot_be_replaced(self): self.mutation_method('SignalClient','checkText','.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)','.onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)')
    def test_utf8_byte_limit_required(self): self.mutation_method('SignalClient','checkText','require(bytes <= MAX_TEXT_BYTES, "text_size");','')
    def test_storage_error_cannot_expire_login(self): self.mutation('CoordinatorFailures','"QUEUED_LOGIN_REQUIRED".equals(code)','"QUEUED_LOGIN_REQUIRED".equals(code)||"CRYPTO_OR_STORAGE_FAILED".equals(code)')
    def test_api30_insets_guard_required(self): self.mutation('TorTalkInsets','if(Build.VERSION.SDK_INT>=30)','if(true)')
    def test_api30_ime_union_required(self): self.mutation('TorTalkInsetsApi30','handledTypes=safeTypes|WindowInsets.Type.ime();','handledTypes=safeTypes;')
    def test_api30_display_cutout_required(self): self.mutation('TorTalkInsetsApi30','safeTypes=WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout();','safeTypes=WindowInsets.Type.systemBars();')
    def test_insets_cannot_be_globally_consumed(self): self.mutation('TorTalkInsetsApi30','return new WindowInsets.Builder(insets)','return WindowInsets.CONSUMED; /* new WindowInsets.Builder(insets) */ new WindowInsets.Builder(insets)')
    def test_zeroed_handled_insets_required(self): self.mutation('TorTalkInsetsApi30','.setInsets(handledTypes,Insets.NONE)','.setInsets(handledTypes,occupied)')
    def test_renderer_fallback_keeps_inset_host(self): self.mutation('TorTalkActivity','contentHost.removeAllViews();contentHost.addView(fallback,new FrameLayout.LayoutParams(-1,-1));','setContentView(fallback);')
    def test_signature_fixture_cannot_be_packaged(self):
        with self.assertRaises(AssertionError): v.source_boundaries(self.sources,self.assets,self.build+'\nsourceSets["main"].java.srcDir("integration/api26-insets-fixture")\n')
    def test_new_source_requires_review(self):
        sources = self.sources.copy(); sources['UnreviewedClient'] = 'class UnreviewedClient {}'
        with self.assertRaises(AssertionError): v.source_boundaries(sources, self.assets, self.build)
    def test_testing_jni_packaging_rule_required(self):
        with self.assertRaises(AssertionError): v.source_boundaries(self.sources,self.assets,self.build.replace('jniLibs.excludes += "**/libsignal_jni_testing.so"',''))
    def test_wildcard_keep_debug_bytes_rejected(self):
        self.build_mutation('jniLibs.keepDebugSymbols += "**/libtor.so"','jniLibs.keepDebugSymbols += "**/*.so"')


class IntegratedFeatureMutations:
    """Prove each feature assertion directly and through the full reviewed inventory."""
    @classmethod
    def setUpClass(cls):
        cls.sources = {p.stem:p.read_text() for p in (v.ROOT / 'src/main/java').rglob('*.java')}
        cls.assets = {p.name:p.read_text() for p in (v.ROOT / 'src/main/assets/ui').iterdir() if p.is_file()}
        cls.build = (v.ROOT / 'build.gradle.kts').read_text()
        # A pre-existing inventory or unrelated guard failure must never make a mutation pass.
        getattr(v, cls.boundary)(cls.sources)
        v.source_boundaries(cls.sources, cls.assets, cls.build)

    def mutation(self, file, old, new, method=None, parameters=None):
        sources = self.sources.copy()
        original = sources[file]
        body = original if method is None else v.method_body(original, method, parameters)
        self.assertIn(old, body, 'test fixture changed; review mutation: '+file+' '+str(method)+' '+old)
        self.assertNotEqual(old, new)
        sources[file] = original.replace(body, body.replace(old, new, 1), 1)
        with self.subTest(boundary=self.boundary):
            with self.assertRaises(AssertionError): getattr(v, self.boundary)(sources)
        with self.subTest(boundary='source_boundaries'):
            with self.assertRaises(AssertionError): v.source_boundaries(sources, self.assets, self.build)


class BlockBoundaryTests(IntegratedFeatureMutations, unittest.TestCase):
    boundary = 'block_boundaries'

    def test_reviewed_block_boundary_passes(self): v.block_boundaries(self.sources)
    def test_block_response_exact_field_count(self): self.mutation('PeerBlockState','value.length()!=5','value.length()<5',method='parse')
    def test_block_response_exact_actor_owned_peer(self): self.mutation('PeerBlockState','!expectedPeer.equals(value.get("peer_id"))','false',method='parse')
    def test_block_boolean_type_required(self): self.mutation('PeerBlockState','!(block instanceof Boolean)','false',method='parse')
    def test_block_integer_revision_type_required(self): self.mutation('PeerBlockState','number instanceof Integer||number instanceof Long','number instanceof Number',method='parse')
    def test_block_revision_upper_bound_required(self): self.mutation('PeerBlockState','revision>Integer.MAX_VALUE','false',method='parse')
    def test_block_legacy_zero_revision_binding(self): self.mutation('PeerBlockState','!id.isEmpty()||isBlocked!=isLegacy','!id.isEmpty()',method='parse')
    def test_block_page_exact_field_count(self): self.mutation('PeerBlockState','body.length()!=2','body.length()<2',method='page')
    def test_block_page_capacity_required(self): self.mutation('PeerBlockState','entries.length()>64','entries.length()>640',method='page')
    def test_block_page_cannot_include_actor(self): self.mutation('PeerBlockState','peer.equals(account)||','',method='page')
    def test_block_page_order_required(self): self.mutation('PeerBlockState','peer.compareTo(previous)<=0','false',method='page')
    def test_block_page_cursor_binding_required(self): self.mutation('PeerBlockState','!next.equals(previous)||','',method='page')
    def test_block_ack_exact_mutation_required(self): self.mutation('PeerBlockState','mutationId.equals(id)&&','',method='acknowledges')
    def test_block_ack_exact_desired_state_required(self): self.mutation('PeerBlockState','blocked==desired&&','',method='acknowledges')
    def test_block_ack_next_revision_required(self): self.mutation('PeerBlockState','(long)revision==(long)expectedRevision+1','revision>=expectedRevision',method='acknowledges')
    def test_block_schema_byte_cap_required(self): self.mutation('PeerBlockJournalSchema','MAX_BYTES = 256 * 1024','MAX_BYTES = 1024 * 1024')
    def test_block_schema_pending_cap_required(self): self.mutation('PeerBlockJournalSchema','MAX_PENDING = 8','MAX_PENDING = 80')
    def test_block_schema_status_cap_required(self): self.mutation('PeerBlockJournalSchema','MAX_STATUSES = 1024','MAX_STATUSES = 10240')
    def test_block_schema_decode_count_bound_required(self): self.mutation('PeerBlockJournalSchema','statusCount > MAX_STATUSES','false',method='decode')
    def test_block_schema_trailing_bytes_rejected(self): self.mutation('PeerBlockJournalSchema','if (input.hasRemaining()) throw failure();','',method='decode')
    def test_block_namespace_includes_origin_and_account(self): self.mutation('PeerBlockJournal','origin + "\\n" + account','account',method='namespace')
    def test_block_loaded_origin_binding_required(self): self.mutation('PeerBlockJournal','if (!origin.equals(record.origin))','if (false)',method='decodeBound')
    def test_block_loaded_account_binding_required(self): self.mutation('PeerBlockJournal','if (!account.equals(record.account))','if (false)',method='decodeBound')
    def test_block_prepare_retains_confirmed_revision_floor(self): self.mutation('PeerBlockJournal','pending.expectedRevision < status.revision','false',method='prepare')
    def test_block_status_cannot_regress(self): self.mutation('PeerBlockJournal','status.revision < previous.revision','false',method='mergeStatus')
    def test_block_equal_revision_cannot_change_status(self): self.mutation('PeerBlockJournal','status.revision == previous.revision && !status.sameStatus(previous)','false',method='mergeStatus')
    def test_block_confirm_ack_exact_saved_mutation(self): self.mutation('PeerBlockJournal','!acknowledgeMutationId.equals(matching.mutationId)','false',method='confirm')
    def test_block_confirm_ack_exact_saved_revision(self): self.mutation('PeerBlockJournal','(long) matching.expectedRevision + 1 != status.revision','false',method='confirm')
    def test_block_status_and_pending_ack_commit_together(self): self.mutation('PeerBlockJournal','commit(previous, pending, statuses)','commit(previous, record.pending, statuses)',method='confirm')
    def test_block_journal_cas_required(self): self.mutation('PeerBlockJournal','!Arrays.equals(expected, previous)','false',method='commit')
    def test_block_cas_validates_loaded_profile(self): self.mutation('PeerBlockJournal','try { decodeBound(previous); }','try { decodeBound(null); }',method='commit')
    def test_block_prepare_required_before_http(self): self.mutation('PeerBlockCoordinator','store.prepare(intent);','',method='persistAndSend')
    def test_block_http_cannot_precede_durable_prepare(self): self.mutation('PeerBlockCoordinator','store.prepare(intent);','api.request("POST","/v1/blocks",new JSONObject(),owner,(status,response,code)->{});store.prepare(intent);',method='persistAndSend')
    def test_block_prepare_readback_required(self): self.mutation('PeerBlockCoordinator','List<PeerBlockJournal.Pending> loaded=store.load();','List<PeerBlockJournal.Pending> loaded=Collections.emptyList();',method='persistAndSend')
    def test_block_native_hold_precedes_disk(self): self.mutation('PeerBlockCoordinator','acceptedHolds.put(intent.peerId,intent);','',method='persistAndSend')
    def test_block_write_failure_cannot_clear_accepted_hold(self): self.mutation('PeerBlockCoordinator','failed=true;ready=false;','acceptedHolds.clear();failed=true;ready=false;',method='storageFailure')
    def test_block_write_failure_fails_closed(self): self.mutation('PeerBlockCoordinator','failed=true;ready=false;','failed=false;ready=true;',method='storageFailure')
    def test_block_send_fence_covers_unready_storage(self): self.mutation('PeerBlockCoordinator','!ready()||','',method='blocksSending')
    def test_block_send_fence_covers_pending_blocks(self): self.mutation('PeerBlockCoordinator','intent!=null&&intent.blocked||','',method='blocksSending')
    def test_block_profile_generation_required(self): self.mutation('PeerBlockCoordinator','profile==profileGeneration','true',method='profileCurrent')
    def test_block_operation_generation_required(self): self.mutation('PeerBlockCoordinator','operation==operationGeneration','true',method='current')
    def test_block_prepare_completion_requires_same_profile(self): self.mutation('PeerBlockCoordinator','if(!profileCurrent(profile))return;','',method='persistAndSend')
    def test_block_http_dispatch_requires_current_operation(self): self.mutation('PeerBlockCoordinator','if(!current(profile,operation))return;','',method='persistAndSend')
    def test_block_server_ack_validation_required(self): self.mutation('PeerBlockCoordinator','!accepted.acknowledges(intent.mutationId,intent.expectedRevision,intent.blocked)||','',method='persistAndSend')
    def test_block_server_state_must_commit_before_success(self): self.mutation('PeerBlockCoordinator','store.confirm(record(state),intent==null?"":intent.mutationId);','',method='persistStatus')
    def test_block_status_page_must_commit(self): self.mutation('PeerBlockCoordinator','store.confirmAll(updates);','',method='page')
    def test_block_retry_preserves_exact_request(self): self.mutation('PeerBlockCoordinator','!same(saved,intent)','false',method='retry')
    def test_block_native_send_guard_required(self): self.mutation('TorTalkActivity','if(!allowPeerTraffic())return;','',method='sendMessage')
    def test_block_native_retry_guard_required(self): self.mutation('TorTalkActivity','if(!allowPeerTraffic())return;','',method='retryPending')
    def test_block_native_poll_guard_required(self): self.mutation('TorTalkActivity','if(!allowPeerTraffic())return;','',method='refreshMessages')
    def test_block_native_open_routes_blocked_peer_to_archive(self): self.mutation('TorTalkActivity','peerBlocks.blocksSending(openingPeer)','false',method='openRoom')


class ArchiveBoundaryTests(IntegratedFeatureMutations, unittest.TestCase):
    boundary = 'archive_boundaries'

    def test_reviewed_archive_boundary_passes(self): v.archive_boundaries(self.sources)
    def test_archive_room_page_cap_required(self): self.mutation('SignalClient','limit <= 40','limit <= 400',method='listLocalRooms')
    def test_archive_message_page_cap_required(self): self.mutation('SignalClient','limit <= 200','limit <= 2000',method='openLocalRoom')
    def test_archive_origin_must_match_loaded_state(self): self.mutation('SignalClient','expectedOrigin.equals(s.text("meta|origin"))','true',method='archiveState')
    def test_archive_account_must_match_loaded_state(self): self.mutation('SignalClient','expectedAccount.equals(s.text("meta|account"))','true',method='archiveState')
    def test_archive_empty_state_cannot_initialize_identity(self): self.mutation('SignalClient','if (s.records.isEmpty()) return s;','if (s.records.isEmpty()) { try { initialize(expectedOrigin, expectedAccount, "demo-device", 1); } catch (Exception failure) { throw new IOException(failure); } return s; }',method='archiveState')
    def test_archive_reads_cannot_commit_state(self): self.mutation('SignalClient','return new LocalHistoryPage(selected.view(), messages, next);','commit(s);return new LocalHistoryPage(selected.view(), messages, next);',method='openLocalRoom')
    def test_archive_selected_room_requires_exact_peer(self): self.mutation('SignalClient','selected.peer.equals(expectedPeer)','true',method='openLocalRoom')
    def test_archive_cursor_must_exist(self): self.mutation('SignalClient','require(bytes != null, "archive_cursor");','',method='openLocalRoom')
    def test_archive_cursor_requires_exact_room(self): self.mutation('SignalClient','anchor.stored.envelope.roomId.equals(roomId)','true',method='openLocalRoom')
    def test_archive_cursor_requires_exact_peer(self): self.mutation('SignalClient','anchor.peer.equals(expectedPeer)','true',method='openLocalRoom')
    def test_archive_page_must_stay_before_anchor(self): self.mutation('SignalClient','anchor != null && compareArchive(message, anchor) >= 0','false',method='openLocalRoom')
    def test_archive_window_memory_cap_required(self): self.mutation('SignalClient','if (latest.size() > limit + 1) latest.poll();','',method='openLocalRoom')
    def test_archive_room_cannot_rebind_peer(self): self.mutation('SignalClient','existing == null || existing.peer.equals(peer)','true',method='archiveRoom')
    def test_archive_message_requires_record_key_binding(self): self.mutation('SignalClient','key.equals((outgoing ? "out|" : "in|") + e.messageId)','true',method='archiveMessage')
    def test_archive_message_requires_profile_account(self): self.mutation('SignalClient','ownAccount.equals(s.text("meta|account"))','true',method='archiveMessage')
    def test_archive_message_requires_profile_device(self): self.mutation('SignalClient','ownDevice.equals(s.text("meta|device"))','true',method='archiveMessage')
    def test_archive_room_projection_stays_read_only(self): self.mutation('SignalJson','.put("read_only",true)','.put("read_only",false)',method='localRooms')
    def test_archive_projection_cannot_expose_ciphertext(self): self.mutation('SignalJson','.put("text",entry.text)','.put("text",entry.envelope.wireBase64())',method='localHistoryMessages')
    def test_archive_projection_requires_room_binding(self): self.mutation('SignalJson','!page.room.roomId.equals(e.roomId)','false',method='localHistoryMessages')
    def test_archive_coordinator_cannot_create_send_readiness(self): self.mutation('MessageCoordinator','!localArchive&&','',method='isReady')
    def test_archive_live_entrypoints_refuse_archive_mode(self): self.mutation('MessageCoordinator','if(localArchive)','if(false)',method='beginLive')
    def test_archive_all_live_entrypoints_use_read_only_gate(self):
        for method, parameters in (
                ('enroll', None), ('openRoom', 'String room,String expectedPeer,Callback done'),
                ('verifyPeer', None), ('replacePeerIdentity', None),
                ('sendText', 'String room,String messageId,String text,Runnable staged,Callback done'),
                ('maintainPrekeys', None), ('retryPending', None), ('poll', None)):
            with self.subTest(method=method):
                self.mutation('MessageCoordinator','long g=beginLive(done);','long g=begin(done);',method=method,parameters=parameters)
    def test_archive_network_boundary_refuses_archive_mode(self): self.mutation('MessageCoordinator','if(localArchive)','if(false)',method='request')
    def test_archive_open_revokes_live_generation(self): self.mutation('MessageCoordinator','cancel();localArchive=true;','localArchive=true;',method='openLocalRoom',parameters='String room,String expectedPeer,String beforeMessageCursor,int limit,Callback done')
    def test_archive_open_cannot_contact_server(self): self.mutation('MessageCoordinator','cancel();localArchive=true;','api.request("GET","/v1/rooms",null,session.sessionToken,session.deviceToken,session.serverOnion,(status,body,code)->{});cancel();localArchive=true;',method='openLocalRoom',parameters='String room,String expectedPeer,String beforeMessageCursor,int limit,Callback done')
    def test_archive_read_result_requires_same_shared_profile(self): self.mutation('MessageCoordinator','core==selected&&','',method='localOperationCurrent')
    def test_archive_read_result_requires_same_account(self): self.mutation('MessageCoordinator','identity.accountId.equals(value.accountId)&&','',method='localOperationCurrent')
    def test_archive_read_result_requires_same_server(self): self.mutation('MessageCoordinator','identity.serverOnion.equals(api.configuredOnion())','true',method='localOperationCurrent')
    def test_archive_success_callback_requires_current_operation(self): self.mutation('MessageCoordinator','if(localOperationCurrent(g,selected,identity))success.accept(value);','success.accept(value);',method='localRead')
    def test_archive_visible_messages_require_current_profile(self): self.mutation('MessageCoordinator','if(localArchive&&!archiveProfileCurrent())return new JSONArray();','',method='messages')
    def test_archive_profile_requires_same_account(self): self.mutation('MessageCoordinator','archiveAccount.equals(value.accountId)&&','',method='archiveProfileCurrent')
    def test_archive_profile_requires_same_server(self): self.mutation('MessageCoordinator','archiveOrigin.equals(api.configuredOnion())','true',method='archiveProfileCurrent')
    def test_archive_native_traffic_gate_required(self): self.mutation('TorTalkActivity','if(localHistoryMode)','if(false)',method='allowPeerTraffic')
    def test_archive_native_enrollment_guard_required(self): self.mutation('TorTalkActivity','if(localHistoryMode||recoveryHoldsEnrollment())return;','if(recoveryHoldsEnrollment())return;',method='maybeEnroll')
    def test_archive_native_poller_guard_required(self): self.mutation('TorTalkActivity','if(!localHistoryMode&&protectionMessage().isEmpty()','if(protectionMessage().isEmpty()')
    def test_archive_native_prekey_maintenance_guard_required(self): self.mutation('TorTalkActivity','if(!localHistoryMode&&!recoveryHoldsEnrollment()&&session!=null&&enrollmentReady','if(!recoveryHoldsEnrollment()&&session!=null&&enrollmentReady')


class RecoveryBoundaryTests(IntegratedFeatureMutations, unittest.TestCase):
    boundary = 'recovery_boundaries'

    def test_reviewed_recovery_boundary_passes(self): v.recovery_boundaries(self.sources)
    def test_recovery_inventory_includes_all_three_classes(self):
        for name in ['PendingDeviceRecoverySchema', 'PendingDeviceRecoveryStore', 'DeviceRecoveryCoordinator']:
            with self.subTest(name=name):
                self.assertIn(name, v.SOURCE_FILES)
                sources = self.sources.copy(); del sources[name]
                with self.assertRaises(AssertionError): v.source_boundaries(sources, self.assets, self.build)
    def test_recovery_encrypted_namespace_required(self): self.mutation('PendingDeviceRecoveryStore','new KeystoreBlobStore(context,"device-recovery")','new KeystoreBlobStore(context,"session")')
    def test_recovery_capability_cannot_be_logged(self): self.mutation('DeviceRecoveryCoordinator','JSONObject body=object(','System.out.println(value.replacementAccountToken);JSONObject body=object(',method='authenticate')
    def test_recovery_capability_cannot_enter_existing_page_field(self): self.mutation('TorTalkActivity','state.put("error",error)','state.put("error",deviceRecovery.pending().replacementAccountToken)',method='publish')
    def test_recovery_capability_cannot_enter_general_session(self): self.mutation('NativeResponseRules','new AppSessionStore.Session(origin,requested,"","","",string(response,"session_token"))','new AppSessionStore.Session(origin,requested,string(response,"account_token"),"","",string(response,"session_token"))',method='session')
    def test_recovery_record_cannot_log_capabilities(self): self.mutation('PendingDeviceRecoveryStore','return "PendingDeviceRecovery([redacted])";','return oldAccountToken;')
    def test_recovery_schema_byte_budget_required(self): self.mutation('PendingDeviceRecoverySchema','MAX_BYTES=4096','MAX_BYTES=40960')
    def test_recovery_schema_requires_distinct_capabilities(self): self.mutation('PendingDeviceRecoverySchema','||fields[4].equals(fields[5])','',method='validate')
    def test_recovery_schema_requires_origin(self): self.mutation('PendingDeviceRecoverySchema','!SessionSchema.isCanonicalOrigin(fields[0])','false',method='validate')
    def test_recovery_schema_requires_replacement_token(self): self.mutation('PendingDeviceRecoverySchema','!SessionSchema.token(fields[5],"gd_acct_")','false',method='validate')
    def test_recovery_schema_rejects_unrecognized_phase(self): self.mutation('PendingDeviceRecoverySchema','if(phase!=1&&phase!=2)throw invalid();','',method='decode')
    def test_recovery_schema_bounds_individual_fields(self): self.mutation('PendingDeviceRecoverySchema','length>LIMITS[i]','false',method='decode')
    def test_recovery_tombstone_must_be_secret_free(self): self.mutation('PendingDeviceRecoverySchema','if(phase==0){if(input.hasRemaining())throw invalid();','if(phase==0){',method='decode')
    def test_recovery_schema_rejects_trailing_bytes(self): self.mutation('PendingDeviceRecoverySchema','if(input.hasRemaining())throw invalid();try{validate(fields);}','try{validate(fields);}',method='decode')
    def test_recovery_prepare_reuses_exact_target(self): self.mutation('PendingDeviceRecoveryStore','!previous.targetDeviceId.equals(target)||','',method='prepare')
    def test_recovery_prepare_reuses_exact_old_capability(self): self.mutation('PendingDeviceRecoveryStore','!previous.oldAccountToken.equals(oldCapability)','false',method='prepare')
    def test_recovery_prepare_cannot_regenerate_existing_checkpoint(self): self.mutation('PendingDeviceRecoveryStore','return previous;}','}',method='prepare')
    def test_recovery_generated_secret_is_wiped(self): self.mutation('PendingDeviceRecoveryStore','finally{wipe(secret);wipe(identity);}','finally{wipe(identity);}',method='prepare')
    def test_recovery_prepared_record_must_commit_before_return(self): self.mutation('PendingDeviceRecoveryStore','writeCompared(before,next);return created;','return created;',method='prepare')
    def test_recovery_cas_cannot_overwrite_another_checkpoint(self): self.mutation('PendingDeviceRecoveryStore','!Arrays.equals(before,current)','false',method='writeCompared')
    def test_recovery_write_invalidates_requests_before_attempt(self): self.mutation('PendingDeviceRecoveryStore','synchronized(MUTATION_LOCK){mutationEpoch++;}','',method='writeCompared')
    def test_recovery_dispatch_requires_current_mutation_epoch(self): self.mutation('PendingDeviceRecoveryStore','if(expected!=mutationEpoch)return false;','',method='dispatchIfCurrent')
    def test_recovery_epoch_check_and_dispatch_share_lock(self): self.mutation('PendingDeviceRecoveryStore','synchronized(MUTATION_LOCK){','{',method='dispatchIfCurrent')
    def test_recovery_confirmation_requires_exact_checkpoint(self): self.mutation('PendingDeviceRecoveryStore','if(!recoveryId.equals(previous.recoveryId))throw fail(Code.STATE_CHANGED);','',method='markConfirmed')
    def test_recovery_key_ack_requires_confirmed_record(self): self.mutation('PendingDeviceRecoveryStore','if(!previous.confirmed)throw fail(Code.UNCONFIRMED);','',method='acknowledge')
    def test_recovery_key_ack_requires_exact_checkpoint(self): self.mutation('PendingDeviceRecoveryStore','if(!recoveryId.equals(previous.recoveryId))throw fail(Code.STATE_CHANGED);','',method='acknowledge')
    def test_recovery_key_ack_writes_secret_free_tombstone(self): self.mutation('PendingDeviceRecoveryStore','PendingDeviceRecoverySchema.encode(null,false)','encode(previous)',method='acknowledge')
    def test_recovery_readable_reset_requires_shown_checkpoint(self): self.mutation('PendingDeviceRecoveryStore','previous==null||!expectedRecoveryId.equals(previous.recoveryId)','previous==null',method='resetAfterConfirmation')
    def test_recovery_corruption_reset_rechecks_storage(self): self.mutation('PendingDeviceRecoveryStore','try{load();throw fail(Code.STATE_CHANGED);}','try{throw fail(Code.CORRUPT_STATE);}',method='resetAfterConfirmation')
    def test_recovery_corruption_reset_invalidates_inflight_http(self): self.mutation('PendingDeviceRecoveryStore','synchronized(MUTATION_LOCK){mutationEpoch++;}blob.clear();','blob.clear();',method='resetAfterConfirmation')
    def test_recovery_start_requires_usable_checkpoint(self): self.mutation('DeviceRecoveryCoordinator','if(!loaded||failed)','if(false)',method='start')
    def test_recovery_http_cannot_precede_checkpoint_write(self): self.mutation('DeviceRecoveryCoordinator','disk.execute(()->{','recover(operation,pending,session,true,callback);disk.execute(()->{',method='start')
    def test_recovery_http_cannot_bypass_persisted_callback(self): self.mutation('DeviceRecoveryCoordinator','preparing=true;listener.changed();','preparing=true;listener.changed();api.request("POST","/v1/devices/"+target+"/recover",new JSONObject(),owner,bound,(status,response,code)->{});',method='start')
    def test_recovery_prepare_callback_checks_operation_generation(self): self.mutation('DeviceRecoveryCoordinator','if(!current(operation))return;','',method='start')
    def test_recovery_cancel_invalidates_operation_generation(self): self.mutation('DeviceRecoveryCoordinator','generation++;busy=false;','busy=false;',method='cancel')
    def test_recovery_dispatch_obeys_current_operation(self): self.mutation('DeviceRecoveryCoordinator','if(!current(operation))return;','',method='dispatch')
    def test_recovery_dispatch_uses_saved_epoch(self): self.mutation('DeviceRecoveryCoordinator','store.dispatchIfCurrent(pendingEpoch,request)','store.dispatchIfCurrent(store.epoch(),request)',method='dispatch')
    def test_recovery_retry_rereads_saved_checkpoint(self): self.mutation('DeviceRecoveryCoordinator','PendingDeviceRecoveryStore.Pending value=store.load();','PendingDeviceRecoveryStore.Pending value=expected;',method='resume')
    def test_recovery_retry_requires_exact_replacement_capability(self): self.mutation('DeviceRecoveryCoordinator','&&a.replacementAccountToken.equals(b.replacementAccountToken)','',method='sameRequest')
    def test_recovery_retry_checks_saved_request_identity(self): self.mutation('DeviceRecoveryCoordinator','if(!sameRequest(expected,value))','if(false)',method='resume')
    def test_recovery_resume_authenticates_with_new_capability_first(self): self.mutation('DeviceRecoveryCoordinator','if(session==null)authenticate(operation,value,true,callback);','if(session==null)authenticate(operation,value,false,callback);',method='resume')
    def test_recovery_context_requires_exact_origin(self): self.mutation('DeviceRecoveryCoordinator','value.serverOnion.equals(origin)&&','',method='matchesContext')
    def test_recovery_context_requires_exact_account(self): self.mutation('DeviceRecoveryCoordinator','&&value.accountId.equals(session.accountId)','',method='matchesContext')
    def test_recovery_unauthorized_requires_exact_401(self): self.mutation('DeviceRecoveryCoordinator','status==401','status>=400',method='unauthorized')
    def test_recovery_unauthorized_requires_no_transport_failure(self): self.mutation('DeviceRecoveryCoordinator','code.isEmpty()&&','',method='unauthorized')
    def test_recovery_unauthorized_requires_exact_body(self): self.mutation('DeviceRecoveryCoordinator','body.length()==1&&','',method='unauthorized')
    def test_recovery_unauthorized_requires_exact_code(self): self.mutation('DeviceRecoveryCoordinator','"UNAUTHORIZED".equals(body.opt("code"))','true',method='unauthorized')
    def test_recovery_auth_success_requires_exact_201(self): self.mutation('DeviceRecoveryCoordinator','status!=201','status<200||status>=300',method='authenticate')
    def test_recovery_old_capability_fallback_requires_explicit_unauthorized(self): self.mutation('DeviceRecoveryCoordinator','unauthorized(status,response,code)&&replacement','status!=201&&replacement',method='authenticate')
    def test_recovery_old_capability_cannot_retry_forever(self): self.mutation('DeviceRecoveryCoordinator','unauthorized(status,response,code)&&replacement','unauthorized(status,response,code)',method='authenticate')
    def test_recovery_authenticated_retry_cannot_repeat_auth_cycle(self): self.mutation('DeviceRecoveryCoordinator','recover(operation,value,session,false,callback);','recover(operation,value,session,true,callback);',method='authenticate')
    def test_recovery_new_capability_selection_cannot_be_reversed(self): self.mutation('DeviceRecoveryCoordinator','replacement?value.replacementAccountToken:value.oldAccountToken','replacement?value.oldAccountToken:value.replacementAccountToken',method='authenticate')
    def test_recovery_authenticated_session_validates_account(self): self.mutation('DeviceRecoveryCoordinator','NativeResponseRules.session(value.serverOnion,value.accountId,response)','NativeResponseRules.session(value.serverOnion,response.optString("account_id"),response)',method='authenticate')
    def test_recovery_post_uses_saved_origin(self): self.mutation('DeviceRecoveryCoordinator','request(value),owner,value.serverOnion','request(value),owner,origin',method='recover')
    def test_recovery_result_requires_exact_200(self): self.mutation('DeviceRecoveryCoordinator','status!=200','status<200||status>=300',method='recover')
    def test_recovery_result_requires_exact_device(self): self.mutation('DeviceRecoveryCoordinator','value.targetDeviceId.equals(body.opt("device_id"))','true',method='validResult')
    def test_recovery_result_requires_exact_recovery_id(self): self.mutation('DeviceRecoveryCoordinator','value.recoveryId.equals(body.opt("recovery_id"))','true',method='validResult')
    def test_recovery_result_requires_exact_fields(self): self.mutation('DeviceRecoveryCoordinator','body.length()==3','body.length()>=3',method='validResult')
    def test_recovery_response_must_validate_before_confirm(self): self.mutation('DeviceRecoveryCoordinator','if(!validResult(body,value))','if(false)',method='recover')
    def test_recovery_success_requires_durable_confirmation(self): self.mutation('DeviceRecoveryCoordinator','store.markConfirmed(value.recoveryId)','value',method='recover')
    def test_recovery_response_cannot_implicitly_destroy_checkpoint(self): self.mutation('DeviceRecoveryCoordinator','if(!validResult(body,value))','store.acknowledge(value.recoveryId);if(!validResult(body,value))',method='recover')
    def test_recovery_storage_error_must_remain_uncertain(self): self.mutation('DeviceRecoveryCoordinator','failed=true;loaded=false;','failed=false;loaded=true;',method='localFailure')
    def test_recovery_key_ack_checks_context(self): self.mutation('DeviceRecoveryCoordinator','||!matchesContext(pending)','',method='acknowledge')
    def test_recovery_key_ack_requires_durable_cleanup_before_reload(self): self.mutation('DeviceRecoveryCoordinator','store.acknowledge(recoveryId);','',method='acknowledge')
    def test_recovery_adapter_keeps_origin_binding(self): self.mutation('TorTalkActivity','if(!origin.equals(api.configuredOnion()))','if(false)')
    def test_recovery_enrollment_hold_required(self): self.mutation('TorTalkActivity','if(localHistoryMode||recoveryHoldsEnrollment())return;','if(localHistoryMode)return;',method='maybeEnroll')
    def test_recovery_maintenance_hold_required(self): self.mutation('TorTalkActivity','if(!localHistoryMode&&!recoveryHoldsEnrollment()&&session!=null&&enrollmentReady','if(!localHistoryMode&&session!=null&&enrollmentReady')
    def test_recovery_unreadable_checkpoint_holds_enrollment(self): self.mutation('TorTalkActivity','!deviceRecovery.loaded()||deviceRecovery.failed()||deviceRecovery.busy()','deviceRecovery.busy()',method='recoveryHoldsEnrollment')
    def test_recovery_key_ack_is_default_off(self): self.mutation('TorTalkActivity','box.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);recorded.setOnCheckedChangeListener','box.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);recorded.setOnCheckedChangeListener',method='showDeviceRecoveryKey')
    def test_recovery_key_display_cannot_save_view_state(self): self.mutation('TorTalkActivity','key.setSaveEnabled(false);','key.setSaveEnabled(true);',method='showDeviceRecoveryKey')
    def test_recovery_key_display_cannot_enable_autofill(self): self.mutation('TorTalkActivity','key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);','',method='showDeviceRecoveryKey')
    def test_recovery_key_ack_cannot_ignore_storage_error(self): self.mutation('TorTalkActivity','if(!code.isEmpty())','if(false)',method='showDeviceRecoveryKey')
    def test_recovery_logout_must_preserve_unacknowledged_checkpoint(self): self.mutation('TorTalkActivity','cancelQr();','cancelQr();deviceRecovery.resetAfterConfirmation(code->{});',method='clearSession')


class JsonBoundaryTests(IntegratedFeatureMutations, unittest.TestCase):
    boundary = 'json_boundaries'

    def test_reviewed_json_boundary_passes(self): v.json_boundaries(self.sources)
    def test_duplicate_object_keys_must_be_rejected(self): self.mutation('JsonBounds','!keys.add(key.toString())||','',method='object')
    def test_duplicate_key_set_must_survive_each_object_member(self): self.mutation('JsonBounds','space();StringBuilder key=new StringBuilder();','keys.clear();space();StringBuilder key=new StringBuilder();',method='object')
    def test_duplicate_keys_compare_decoded_names(self): self.mutation('JsonBounds','!string(key)','!string()',method='object')
    def test_duplicate_unicode_names_decode_full_codepoint(self): self.mutation('JsonBounds','value=(value<<4)|digit;','value=digit;',method='string',parameters='StringBuilder decoded')
    def test_duplicate_escaped_names_preserve_decoded_characters(self): self.mutation('JsonBounds','if(decoded!=null)decoded.append(c);','',method='string',parameters='StringBuilder decoded')
    def test_json_unrecognized_escape_rejected(self): self.mutation('JsonBounds','default:return false;','default:break;',method='string',parameters='StringBuilder decoded')
    def test_json_control_character_rejected(self): self.mutation('JsonBounds','if(c<32)return false;','',method='string',parameters='StringBuilder decoded')
    def test_json_depth_limit_required(self): self.mutation('JsonBounds','if(depth>=32)return false;','',method='value')
    def test_json_trailing_input_rejected(self): self.mutation('JsonBounds','p.value(0)&&p.end()','p.value(0)',method='safe')
    def test_json_response_duplicate_check_cannot_be_removed(self): self.mutation('TorApiClient','if (!JsonBounds.safe(text)) throw new IllegalArgumentException("JSON_BOUNDS");','')
    def test_json_response_duplicate_check_precedes_lenient_decoder(self): self.mutation('TorApiClient','if (!JsonBounds.safe(text)) throw new IllegalArgumentException("JSON_BOUNDS");\n                    JSONTokener tokener = new JSONTokener(text);','JSONTokener tokener = new JSONTokener(text);\n                    if (!JsonBounds.safe(text)) throw new IllegalArgumentException("JSON_BOUNDS");')
    def test_json_response_invalid_utf8_cannot_be_replaced(self): self.mutation('TorApiClient','.onMalformedInput(CodingErrorAction.REPORT)','.onMalformedInput(CodingErrorAction.REPLACE)')
    def test_json_response_requires_top_level_object(self): self.mutation('TorApiClient','!(value instanceof JSONObject) ||','')
    def test_json_ui_duplicate_check_precedes_lenient_decoder(self): self.mutation('TorTalkActivity','||!JsonBounds.safe(data)','')


class ProcessBoundaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = {p.stem:p.read_text() for p in (v.ROOT/'src/main/java').rglob('*.java')}
    def test_current_process_boundary_passes(self): v.process_boundaries(self.sources)
    def mutation(self,file,old,new):
        sources=self.sources.copy();self.assertIn(old,sources[file]);sources[file]=sources[file].replace(old,new,1)
        with self.assertRaises(AssertionError): v.process_boundaries(sources)
    def test_auto_create_binding_rejected(self): self.mutation('EmbeddedTorController','bindService(serviceIntent(),attempt,0)','bindService(serviceIntent(),attempt,Context.BIND_AUTO_CREATE)')
    def test_restart_before_binder_death_rejected(self): self.mutation('EmbeddedTorController','(active==null||active.dead)','true')
    def test_bind_component_identity_required(self): self.mutation('EmbeddedTorController','!TorProcessService.class.getName().equals(name.getClassName())','false')
    def test_binder_death_subscription_required(self): self.mutation('EmbeddedTorController','endpoint.linkToDeath(death,0);','')
    def test_no_binder_no_termination_proof(self): self.mutation('EmbeddedTorController','if(attempt.binder==null)return;','')
    def test_second_termination_claim_rejected(self): self.mutation('EmbeddedTorController','context.stopService(serviceIntent());','context.stopService(serviceIntent());nativeState=NativeStopState.TERMINATED_CONFIRMED;')
    def test_main_reply_uid_required(self): self.mutation('EmbeddedTorController','message.sendingUid!=Process.myUid()||','')
    def test_old_reply_epoch_rejected(self): self.mutation('EmbeddedTorController','if(data.getLong("epoch")!=attempt.epoch)return;','')
    def test_stale_readiness_reply_rejected(self): self.mutation('EmbeddedTorController','now-sampledAt>2_000','now-sampledAt>20_000')
    def test_death_visible_before_main_queue(self): self.mutation('EmbeddedTorController','deathObserved=true;gate.fail(gateEpoch);main.post(()->died(this));','main.post(()->{deathObserved=true;gate.fail(gateEpoch);died(this);});')
    def test_old_death_uses_immutable_gate_epoch(self): self.mutation('EmbeddedTorController','gate.fail(gateEpoch)','gate.fail(readinessEpoch)')
    def test_transport_checks_exact_binder_liveness(self): self.mutation('EmbeddedTorController','endpoint.isBinderAlive()&&gate.isCurrent','gate.isCurrent')
    def test_death_visibility_is_volatile(self): self.mutation('EmbeddedTorController','volatile boolean deathObserved;','boolean deathObserved;')
    def test_no_native_control_in_main(self): self.mutation('EmbeddedTorController','this.context=context;','this.context=context;org.torproject.jni.TorService.getTorrc(context);')
    def test_private_process_identity_before_start_required(self): self.mutation('TorProcessService','if (!verifyOwnProcess())','if (false)')
    def test_initial_network_disable_required(self): self.mutation('TorProcessService','ClientOnly 1\\nDisableNetwork 1\\n','ClientOnly 1\\nDisableNetwork 0\\n')
    def test_config_sync_before_native_start_required(self): self.mutation('TorProcessService','out.getFD().sync();','')
    def test_nonsticky_start_required(self): self.mutation('TorProcessService','return START_NOT_STICKY;','return START_STICKY;')
    def test_foreign_owner_cannot_authorize_malformed_stop(self): self.mutation('TorProcessService','!claimedOwner.getBinder().equals(message.replyTo.getBinder())','false')
    def test_missing_owner_binder_cannot_authorize_stop(self): self.mutation('TorProcessService','message.replyTo == null\n                ||','false\n                ||')
    def test_authentication_marker_before_network_enable(self): self.mutation('TorProcessService','authenticated = marker == 0;','authenticated = true;')
    def test_authentication_marker_under_control_monitor(self): self.mutation('TorProcessService','synchronized (control) {','if (true) {')
    def test_independent_watchdog_required(self): self.mutation('TorProcessService','watchdog = Executors.newSingleThreadScheduledExecutor(','watchdog = controlWorker.newSingleThreadScheduledExecutor(')
    def test_watchdog_never_waits_on_control(self): self.mutation('TorProcessService','case LEASE:\n','case LEASE:\n                getTorControlConnection();')
    def test_process_uid_match_required(self): self.mutation('TorProcessService','process.pid == pid && process.uid == uid','process.pid == pid')
    def test_exact_private_process_name_required(self): self.mutation('TorProcessService','expected.equals(process.processName)','true')
    def test_kill_current_pid_only(self): self.mutation('TorProcessService','Process.killProcess(Process.myPid());','Process.killProcess(123);')
    def test_kill_requires_verified_identity(self): self.mutation('TorProcessService','if (identityVerified && ownPid == Process.myPid()','if (ownPid == Process.myPid()')
    def test_ipc_request_uid_required(self): self.mutation('TorProcessProtocol','message.sendingUid != Process.myUid()','false')
    def test_ipc_descriptors_forbidden(self): self.mutation('TorProcessProtocol','!data.hasFileDescriptors() &&','')
    def test_ipc_exact_types_required(self): self.mutation('TorProcessProtocol','value.getClass() == type','type.isInstance(value)')
    def test_ipc_exact_key_count_required(self): self.mutation('TorProcessProtocol','data.size() == size','data.size() >= size')
    def test_lease_uses_sender_time(self): self.mutation('TorProcessPolicy','leaseDeadline = deadline(sentAt, LEASE_MS);','leaseDeadline = deadline(now, LEASE_MS);')
    def test_stop_cannot_extend_command_deadline(self): self.mutation('TorProcessPolicy','if (commandSequence != 0 && now >= commandDeadline)','if (!stopping && commandSequence != 0 && now >= commandDeadline)')
    def test_duplicate_stop_cannot_extend_stop_deadline(self): self.mutation('TorProcessPolicy','if (stopping) return false;','')


class ProcessManifestTests(unittest.TestCase):
    def fixture(self,merged=False):
        root=ET.parse(v.ROOT/'src/main/AndroidManifest.xml').getroot()
        if merged:
            app=root.find('application');root.set('package','dev.gyeopdam.embeddedtor.experiment');app.set(v.ANDROID+'debuggable','true')
            for child in list(app):
                if child.tag=='service' and child.get(v.TOOLS+'node')=='remove': app.remove(child)
        return root
    def check(self,root,merged=False,fails=False):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'AndroidManifest.xml';ET.ElementTree(root).write(path)
            if fails:
                with self.assertRaises(AssertionError): v.manifest(path,merged)
            else: v.manifest(path,merged)
    def wrapper(self,root): return next(x for x in root.find('application').findall('service') if x.get(v.ANDROID+'name')=='.TorProcessService')
    def test_reviewed_source_manifest(self): self.check(self.fixture())
    def test_reviewed_merged_manifest(self): self.check(self.fixture(True),True)
    def test_main_process_native_service_rejected(self):
        root=self.fixture();self.wrapper(root).attrib.pop(v.ANDROID+'process');self.check(root,fails=True)
    def test_application_cannot_share_tor_process(self):
        root=self.fixture();root.find('application').set(v.ANDROID+'process',':tor');self.check(root,fails=True)
    def test_activity_cannot_share_tor_process(self):
        root=self.fixture();root.find('application').find('activity').set(v.ANDROID+'process',':tor');self.check(root,fails=True)
    def test_different_service_uid_rejected(self):
        root=self.fixture();self.wrapper(root).set(v.ANDROID+'isolatedProcess','true');self.check(root,fails=True)
    def test_exported_native_service_rejected(self):
        root=self.fixture();self.wrapper(root).set(v.ANDROID+'exported','true');self.check(root,fails=True)
    def test_stop_with_task_required(self):
        root=self.fixture();self.wrapper(root).set(v.ANDROID+'stopWithTask','false');self.check(root,fails=True)
    def test_vendor_service_removal_required(self):
        root=self.fixture();next(x for x in root.find('application').findall('service') if x.get(v.TOOLS+'node')=='remove').set(v.TOOLS+'node','merge');self.check(root,fails=True)
    def test_vendor_service_cannot_survive_merge(self):
        root=self.fixture(True);ET.SubElement(root.find('application'),'service',{v.ANDROID+'name':'org.torproject.jni.TorService',v.ANDROID+'exported':'false'});self.check(root,True,True)
    def test_native_service_intent_ingress_rejected(self):
        root=self.fixture();ET.SubElement(self.wrapper(root),'intent-filter');self.check(root,fails=True)


class ContactBoundaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources={p.stem:p.read_text() for p in (v.ROOT/'src/main/java').rglob('*.java')}
        cls.build=(v.ROOT/'build.gradle.kts').read_text()
    def test_reviewed_contact_boundary_passes(self): v.contact_boundaries(self.sources,self.build)
    def mutation(self,file,old,new):
        sources=self.sources.copy();self.assertIn(old,sources[file]);sources[file]=sources[file].replace(old,new,1)
        with self.assertRaises(AssertionError): v.contact_boundaries(sources,self.build)
    def test_payload_cannot_add_secret(self): self.mutation('ContactCard',"+ protocolDeviceId + '\\n' + identityKeyBase64;","+ protocolDeviceId + '\\n' + identityKeyBase64 + sessionToken;")
    def test_exact_origin_required(self): self.mutation('ContactCard','!expectedOrigin.equals(card.serverOnion)','false')
    def test_exact_six_fields_required(self): self.mutation('ContactCard','fields.length != 6','fields.length < 6')
    def test_public_key_canonical_encoding_required(self): self.mutation('ContactCard','!Base64.getEncoder().encodeToString(serializedIdentity).equals(identityKeyBase64)','false')
    def test_pixel_overflow_guard_required(self): self.mutation('ContactQrCodec','(long) width * height','width * height')
    def test_only_qr_decoder_permitted(self): self.mutation('ContactQrCodec','new QRCodeReader().decode(image)','new MultiFormatReader().decode(image)')
    def test_structured_append_rejected(self): self.mutation('ContactQrCodec','metadata.containsKey(ResultMetadataType.STRUCTURED_APPEND_SEQUENCE)','false')
    def test_file_uri_rejected(self): self.mutation('ContactQrImage','!"content".equals(uri.getScheme())','!"file".equals(uri.getScheme())')
    def test_input_byte_cap_required(self): self.mutation('ContactQrImage','output.size()+count>MAX_ENCODED_BYTES','false')
    def test_cancel_closes_blocked_input(self): self.mutation('ContactQrImage','input.close();','')
    def test_header_only_first_decode_required(self): self.mutation('ContactQrImage','bounds.inJustDecodeBounds=true;','bounds.inJustDecodeBounds=false;')
    def test_fixed_supported_image_types_required(self): self.mutation('ContactQrImage','"image/jpeg".equals(bounds.outMimeType)','true')
    def test_actual_pixel_bounds_required(self): self.mutation('ContactQrImage','ContactQrCodec.validateDimensions(width,height);','')
    def test_local_picker_hint_required(self): self.mutation('TorTalkActivity','Intent.EXTRA_LOCAL_ONLY,true','Intent.EXTRA_LOCAL_ONLY,false')
    def test_no_persisted_picker_grant(self): self.mutation('TorTalkActivity','final Uri selected=data.getData();','getContentResolver().takePersistableUriPermission(data.getData(),1);final Uri selected=data.getData();')
    def test_qr_matches_exact_identity(self): self.mutation('TorTalkActivity','card.identityKeyBase64.equals(peer.identityKeyBase64)','true')
    def test_qr_matches_protocol_device(self): self.mutation('TorTalkActivity','card.protocolDeviceId==peer.protocolDeviceId','true')
    def test_import_cannot_auto_pin(self): self.mutation('TorTalkActivity','verifyPeerDialog(card);return;','messaging.verifyPeer(qrPeer,null);return;')
    def test_picker_timeout_required(self): self.mutation('TorTalkActivity','timer.postDelayed(qrTimeout,15000);','')
    def test_account_change_cancels_import(self): self.mutation('TorTalkActivity','private void clearSession(){cancelQr();','private void clearSession(){')
    def test_qr_artifact_pin_required(self):
        with self.assertRaises(AssertionError): v.contact_boundaries(self.sources,self.build.replace('71de5d89341b5fcf5dd89da7f44e84d825d0e084cdf3ec77c9abe26b0f0ceb13','0'*64))
    def test_full_upstream_qr_license_preserved(self):
        self.assertEqual(hashlib.sha256((v.ROOT/'src/main/assets/zxing-LICENSE.txt').read_bytes()).hexdigest(),'3f62881f0566227a24b12e5a754cc79f39aaa94883038e95c94812e1f50af42f')


class SignalNoticeTests(unittest.TestCase):
    def archive(self, body=None, expected=None):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'fixture.zip'
            with zipfile.ZipFile(path,'w') as archive:
                if body is not None: archive.writestr(v.SIGNAL_NOTICE_PATH,body)
            with zipfile.ZipFile(path) as archive:
                if expected is None: v.signal_notice(archive)
                else:
                    with mock.patch.object(v,'SIGNAL_NOTICE_SHA256',expected): v.signal_notice(archive)
    def test_missing_complete_upstream_notice_rejected(self):
        with self.assertRaises(AssertionError): self.archive()
    def test_tampered_complete_upstream_notice_rejected(self):
        with self.assertRaises(AssertionError): self.archive(b'tampered upstream notice')
    def test_matching_complete_upstream_notice_accepted(self):
        body=b'Synthetic full notice fixture'
        self.archive(body,hashlib.sha256(body).hexdigest())
    def test_mutated_notice_against_original_digest_rejected(self):
        body=b'Synthetic full notice fixture'
        with self.assertRaises(AssertionError): self.archive(body+b' changed',hashlib.sha256(body).hexdigest())
    def test_repo_pointer_is_small_and_links_pinned_full_notice(self):
        pointer=(v.ROOT/'src/main/assets/libsignal-NOTICE.md').read_bytes()
        self.assertLess(len(pointer),2048)
        self.assertIn(v.SIGNAL_NOTICE_PATH.encode(),pointer)
        self.assertIn(v.SIGNAL_NOTICE_SHA256.encode(),pointer)
        self.assertEqual(hashlib.sha256(pointer).hexdigest(),'282ecac53096f9d43924095080c54fdfdcbb036ebd20037ce1ea6a907cf48ad3')


class StripProvenanceTests(unittest.TestCase):
    def test_exact_reviewed_report_accepted(self):
        v.strip_provenance(v.SIGNAL_STRIP_REPORT.read_bytes())
    def test_modified_report_rejected(self):
        with self.assertRaises(AssertionError): v.strip_provenance(v.SIGNAL_STRIP_REPORT.read_bytes()+b' ')
    def test_unstripped_mapping_cannot_be_used_for_apk(self):
        with mock.patch.object(v,'SIGNAL_PACKAGED_NATIVE',v.SIGNAL_NATIVE):
            with self.assertRaises(AssertionError): v.strip_provenance(v.SIGNAL_STRIP_REPORT.read_bytes())
    def test_different_packaged_hash_rejected(self):
        changed=v.SIGNAL_PACKAGED_NATIVE.copy();changed['arm64-v8a']='0'*64
        with mock.patch.object(v,'SIGNAL_PACKAGED_NATIVE',changed):
            with self.assertRaises(AssertionError): v.strip_provenance(v.SIGNAL_STRIP_REPORT.read_bytes())


class NativeInventoryTests(unittest.TestCase):
    def check_archive(self, entries, hashes, fails=False):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'fixture.zip'
            with zipfile.ZipFile(path, 'w') as archive:
                for name, body in entries.items(): archive.writestr(name, body)
            if fails:
                with self.assertRaises(AssertionError): v.native_hashes(path,'lib',hashes)
            else: v.native_hashes(path,'lib',hashes)
    def fixture(self):
        body = b'synthetic native fixture'; digest = hashlib.sha256(body).hexdigest()
        return {'lib/x86_64/libtor.so':body,'lib/x86_64/libsignal_jni.so':body}, {'libtor.so':{'x86_64':digest},'libsignal_jni.so':{'x86_64':digest}}
    def test_unstripped_universal_native_budget_rejected(self):
        entries = []
        for abi in ['arm64-v8a','armeabi-v7a','x86','x86_64']:
            item = zipfile.ZipInfo('lib/'+abi+'/libsignal_jni.so'); item.file_size = 112 * 1024 * 1024; entries.append(item)
        with self.assertRaises(AssertionError): v.apk_budget(100*1024*1024,entries)
    def test_oversized_apk_rejected(self):
        item = zipfile.ZipInfo('lib/x86_64/libsignal_jni.so'); item.file_size = 12*1024*1024
        with self.assertRaises(AssertionError): v.apk_budget(161*1024*1024,[item])
    def test_bounded_native_payload_allowed(self):
        item = zipfile.ZipInfo('lib/x86_64/libsignal_jni.so'); item.file_size = 80*1024*1024
        v.apk_budget(96*1024*1024,[item])
    def test_exact_two_library_inventory(self):
        entries, hashes = self.fixture(); self.check_archive(entries,hashes)
    def test_unknown_so_rejected(self):
        entries, hashes = self.fixture(); entries['lib/x86_64/unsafe.so'] = b'x'; self.check_archive(entries,hashes,True)
    def test_testing_jni_rejected(self):
        entries, hashes = self.fixture(); entries['lib/x86_64/libsignal_jni_testing.so'] = b'x'; self.check_archive(entries,hashes,True)
    def test_desktop_jni_rejected(self):
        entries, hashes = self.fixture(); entries['libsignal_jni_amd64.so'] = b'x'; self.check_archive(entries,hashes,True)
    def test_dll_rejected(self):
        entries, hashes = self.fixture(); entries['signal_jni_amd64.dll'] = b'x'; self.check_archive(entries,hashes,True)
    def test_missing_abi_rejected(self):
        entries, hashes = self.fixture(); del entries['lib/x86_64/libsignal_jni.so']; self.check_archive(entries,hashes,True)
    def test_changed_native_bytes_rejected(self):
        entries, hashes = self.fixture(); entries['lib/x86_64/libsignal_jni.so'] = b'changed'; self.check_archive(entries,hashes,True)


if __name__ == '__main__': unittest.main()
