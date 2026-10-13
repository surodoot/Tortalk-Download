#!/usr/bin/env python3
"""Synthetic loopback protocol integration. Uses real production SignalClient/JNI.
NOT a Tor-network, Android Keystore, emulator or physical-device test.
"""
import argparse, base64, copy, hashlib, http.client, json, os, secrets, socket, subprocess, tempfile, time
from pathlib import Path


def main():
    p = argparse.ArgumentParser(); p.add_argument('--binary', required=True); p.add_argument('--classpath', required=True); args = p.parse_args()
    join_token = 'tt_join_' + base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip('=')
    raw = bytes(32); checksum = hashlib.sha3_256(b'.onion checksum' + raw + b'\x03').digest()[:2]
    host = base64.b32encode(raw + checksum + b'\x03').decode().lower() + '.onion'
    with tempfile.TemporaryDirectory(prefix='tortalk-signal-pilot-') as td:
        root = Path(td); os.chmod(root, 0o700)
        with socket.socket() as sock: sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
        config = root / 'pilot.json'; config.write_text(json.dumps({'listen':f'127.0.0.1:{port}', 'authority':host, 'state_dir':str(root/'server'), 'registration_token_hash':hashlib.sha256(join_token.encode()).hexdigest()}))
        proc = None
        def request(method, path, body=None, session='', device='', status=200):
            conn = http.client.HTTPConnection('127.0.0.1', port, timeout=10)
            headers = {'Host':host, 'Content-Type':'application/json'}
            if session: headers['Authorization'] = 'Bearer ' + session
            if device: headers['X-TorTalk-Device-Token'] = device
            conn.request(method, path, None if body is None else json.dumps(body), headers)
            response = conn.getresponse(); data = response.read(); conn.close()
            assert response.status == status, f'{method} {path}: status {response.status}, expected {status}'
            return json.loads(data) if data else None
        def start():
            nonlocal proc
            proc = subprocess.Popen([args.binary, '--config', str(config)], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            for _ in range(150):
                if proc.poll() is not None: raise AssertionError('pilot failed to start')
                try: request('GET','/readyz'); return
                except (ConnectionError, OSError): time.sleep(.02)
            raise AssertionError('pilot start timeout')
        def stop():
            if proc and proc.poll() is None: proc.terminate(); proc.wait(timeout=10)
        def peer(name, op, payload, success=True):
            result = subprocess.run(['java','-cp',args.classpath,'dev.gyeopdam.embeddedtor.SignalPilotPeer',str(root/(name+'.state')),op],
                                    input=json.dumps(payload).encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)
            assert (result.returncode == 0) == success, f'{name} {op}: unexpected acceptance/rejection'
            return json.loads(result.stdout) if success else None
        def capability(prefix): return prefix + base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip('=')
        try:
            start(); users = {}
            for name in ['alice','bob']:
                account = 'demo-account-' + secrets.token_hex(16); account_token = capability('gd_acct_'); device = 'demo-device-' + secrets.token_hex(16)
                regq = {'account_id':account,'account_token':account_token}
                signup = dict(regq, registration_token=join_token)
                request('POST','/v1/accounts',regq,status=401)
                request('POST','/v1/accounts',dict(regq, registration_token=capability('tt_join_')),status=401)
                reg = request('POST','/v1/accounts',signup,status=201)
                assert request('POST','/v1/accounts',signup,status=201) == reg, 'registration retry differs'
                session = request('POST','/v1/auth/sessions',regq,status=201)['session_token']
                local = peer(name,'init',{'origin':'http://'+host,'account_id':account,'device_id':device,'count':3})
                deviceq = {'device_id':device,'public_key_base64':local['public_key_base64'],'device_token':local['device_token']}
                enrollment = request('POST','/v1/devices',deviceq,session,status=201)
                assert request('POST','/v1/devices',deviceq,session,status=201) == enrollment
                publication = request('POST',f'/v1/devices/{device}/prekeys',local['publication'],session,local['device_token'])
                assert publication['remaining_pairs'] == 3
                users[name] = {'account':account,'account_token':account_token,'device':device,'session':session,'token':local['device_token'],'local':local}
            a,b = users['alice'],users['bob']; assert a['local']['identity_key_base64'] != b['local']['identity_key_base64']
            inv = request('POST','/v1/invitations',{'invitation_id':'demo-invite-integration','recipient_id':b['account']},a['session'])
            room = request('POST','/v1/invitations/consume',{'invitation_id':inv['invitation']['invitation_id'],'invitation_token':inv['invitation_token'],'idempotency_key':'demo-consume-integration'},b['session'])['room_id']; base = '/v1/rooms/' + room
            # Published public metadata alone is enough to pin a first receiver; no reverse claim.
            devices = request('GET',base+'/devices',session=b['session'])['devices']
            assert len(devices) == 2
            for remote in [a,b]:
                published = next(d for d in devices if d['device_id'] == remote['device'])
                assert published['protocol_device_id'] == remote['local']['protocol_device_id']
                assert published['identity_key_base64'] == remote['local']['identity_key_base64']
            for name,remote in [('alice',b),('bob',a)]:
                peer(name,'pin',{'room_id':room,'account_id':remote['account'],'device_id':remote['device'],'protocol_device_id':remote['local']['protocol_device_id'],'identity_key_base64':remote['local']['identity_key_base64']})
            claim = peer('alice','claim',{'room_id':room,'device_id':b['device']})['claim_id']
            claimq = {'claim_id':claim,'device_id':a['device'],'recipient_device_id':b['device']}
            bundle = request('POST',base+'/prekeys/claim',claimq,a['session'],a['token'])
            assert request('POST',base+'/prekeys/claim',claimq,a['session'],a['token']) == bundle
            acceptq = {'room_id':room,'account_id':b['account'],'device_id':b['device'],'claim_id':claim,'bundle':bundle}
            bad = copy.deepcopy(acceptq); sig = bytearray(base64.b64decode(bad['bundle']['kyber_prekey']['signature_base64'])); sig[0] ^= 1
            bad['bundle']['kyber_prekey']['signature_base64'] = base64.b64encode(sig).decode(); peer('alice','accept',bad,False)
            peer('alice','accept',acceptq)
            text = '독립 키 생성, 실제 Signal PQ 암호화, 재시작 후 안전한 복호화'
            sendq = {'room_id':room,'account_id':b['account'],'device_id':b['device'],'message_id':'demo-message-first','text':text}
            outgoing = peer('alice','send',sendq)
            assert peer('alice','send',sendq) == outgoing, 'durable exact retry changed ciphertext'
            receipt = request('POST',base+'/messages',outgoing,a['session'],a['token'])
            stop(); start()
            assert request('POST',base+'/messages',outgoing,a['session'],a['token']) == receipt, 'server restart retry changed receipt'
            assert request('POST',base+'/prekeys/claim',claimq,a['session'],a['token']) == bundle, 'server restart claim consumed another key'
            peer('alice','accepted',{'message_id':sendq['message_id']})
            inbox = request('GET',base+f"/devices/{b['device']}/inbox",session=b['session'],device=b['token'])['receipts']
            assert len(inbox) == 1
            message = request('GET',base+'/messages/'+sendq['message_id'],session=b['session'],device=b['token'])
            recvq = {'room_id':room,'message_id':sendq['message_id'],'message':message}
            tampered = copy.deepcopy(recvq); rawcipher = bytearray(base64.b64decode(tampered['message']['envelope']['ciphertext_base64'])); rawcipher[-1] ^= 1
            tampered['message']['envelope']['ciphertext_base64'] = base64.b64encode(rawcipher).decode(); peer('bob','receive',tampered,False)
            misplaced = copy.deepcopy(recvq); misplaced['message_id'] = 'demo-message-wrong'; misplaced['message']['receipt']['message_id'] = 'demo-message-wrong'; peer('bob','receive',misplaced,False)
            clear = peer('bob','receive',recvq); assert clear == {'text':text,'duplicate':False,'safe_to_ack':True}
            assert peer('bob','receive',recvq)['duplicate'] is True
            request('POST',base+'/messages/'+sendq['message_id']+'/ack',{},b['session'],b['token'])
            assert request('GET',base+f"/devices/{b['device']}/inbox",session=b['session'],device=b['token'])['receipts'] == []
            request('GET',base+'/messages/'+sendq['message_id'],session=b['session'],device=b['token'],status=410)
            # Ratchet response needs no reverse prekey claim or session reset.
            replyq = {'room_id':room,'account_id':a['account'],'device_id':a['device'],'message_id':'demo-message-response','text':'응답도 실제로 복호화됨'}
            reply = peer('bob','send',replyq); request('POST',base+'/messages',reply,b['session'],b['token'])
            peer('bob','accepted',{'message_id':replyq['message_id']})
            fetched = request('GET',base+'/messages/'+replyq['message_id'],session=a['session'],device=a['token'])
            decoded = peer('alice','receive',{'room_id':room,'message_id':replyq['message_id'],'message':fetched}); assert decoded['text'] == replyq['text']
            request('POST',base+'/messages/'+replyq['message_id']+'/ack',{},a['session'],a['token'])
            # Fresh non-recycled prekey pairs, durable batch retry, signed by same pinned identity.
            assert peer('bob','pending-refill',{})['pending'] is None
            refill = peer('bob','refill',{'count':1}); assert refill == peer('bob','refill',{'count':1})
            assert refill['one_time_prekeys'][0]['key_id'] == 4
            assert peer('bob','pending-refill',{})['pending'] == refill
            replenished = request('POST',f"/v1/devices/{b['device']}/prekeys/replenish",refill,b['session'],b['token'])
            assert replenished['remaining_pairs'] == 3
            assert peer('bob','pending-refill',{})['pending'] == refill, 'accepted refill was lost before local confirmation'
            assert request('POST',f"/v1/devices/{b['device']}/prekeys/replenish",refill,b['session'],b['token']) == replenished
            peer('bob','refill-accepted',{'batch_id':refill['batch_id']})
            assert peer('bob','pending-refill',{})['pending'] is None
            # A lost acceptance response must remain retryable even with zero server capacity.
            full_refill = peer('bob','refill',{'count':17})
            assert [k['key_id'] for k in full_refill['one_time_prekeys']] == list(range(5,22))
            full = request('POST',f"/v1/devices/{b['device']}/prekeys/replenish",full_refill,b['session'],b['token'])
            assert full['remaining_pairs'] == 20 and full['published_pairs'] == 21
            stop(); start()
            assert request('GET',f"/v1/devices/{b['device']}/prekeys",session=b['session'],device=b['token']) == full
            pending_state = (root/'bob.state').read_bytes()
            pending = peer('bob','pending-refill',{})['pending']
            assert pending == full_refill, 'full-pool retry changed the outstanding batch'
            assert (root/'bob.state').read_bytes() == pending_state, 'pending refill getter mutated private state'
            assert request('POST',f"/v1/devices/{b['device']}/prekeys/replenish",pending,b['session'],b['token']) == full
            peer('bob','refill-accepted',{'batch_id':'demo-batch-wrong'},False)
            assert peer('bob','pending-refill',{})['pending'] == full_refill
            peer('bob','refill-accepted',{'batch_id':full_refill['batch_id']})
            assert peer('bob','pending-refill',{})['pending'] is None
            assert peer('alice','history',{}) == {'inbox_count':1,'outbox_count':1,'pending_count':0}
            assert peer('bob','history',{}) == {'inbox_count':1,'outbox_count':1,'pending_count':0}

            # Keep both a relay-queued uncertain send and a local-only send to the old device.
            staleq = dict(sendq, message_id='demo-message-before-recovery', text='이전 기기로 대기 중인 암호문')
            stale = peer('alice','send',staleq)
            request('POST',base+'/messages',stale,a['session'],a['token'])
            offlineq = dict(sendq, message_id='demo-message-offline-old-device', text='전송 전 기기 교체')
            offline = peer('alice','send',offlineq)
            assert peer('alice','emit-stored',{'message_id':offlineq['message_id']}) == offline
            peer('alice','validate-stored',{'message_id':offlineq['message_id']})
            before_recovery = peer('alice','history',{'details':True})
            assert before_recovery['pending_count'] == 2
            assert all(not m['retired'] for m in before_recovery['outbox'])

            # Recovery uses the same synthetic account capability/session, but independent Signal state.
            old_devices = request('GET','/v1/devices',session=b['session'])['devices']
            excluded = [d['protocol_device_id'] for d in old_devices if d['protocol_device_id']]
            assert b['local']['protocol_device_id'] in excluded
            recovered_device = 'demo-device-' + secrets.token_hex(16)
            recovered_init = {'origin':'http://'+host,'account_id':b['account'],'device_id':recovered_device,'count':3,'excluded_protocol_ids':excluded}
            recovered = peer('bob-recovered','init',recovered_init)
            assert peer('bob-recovered','init',recovered_init) == recovered, 'recovery enrollment credentials were not persisted'
            assert recovered['protocol_device_id'] not in excluded
            assert recovered['identity_key_base64'] != b['local']['identity_key_base64']
            assert recovered['device_token'] != b['token']
            assert peer('bob-recovered','history',{}) == {'inbox_count':0,'outbox_count':0,'pending_count':0}
            recovered_deviceq = {'device_id':recovered_device,'public_key_base64':recovered['public_key_base64'],'device_token':recovered['device_token']}
            request('POST','/v1/devices',recovered_deviceq,b['session'],status=409)
            request('POST',f"/v1/devices/{b['device']}/revoke",{'account_token':capability('gd_acct_')},b['session'],status=401)
            # Additional endpoint validation; all identities/tokens remain ephemeral fixtures.
            lost_session = b['session']
            b['session'] = request('POST','/v1/auth/sessions',{'account_id':b['account'],'account_token':b['account_token']},status=201)['session_token']
            recoveryq = {'account_token':b['account_token'],'replacement_account_token':capability('gd_acct_'),'recovery_id':'demo-recovery-integration'}
            recovery_result = {'status':'recovered','device_id':b['device'],'recovery_id':recoveryq['recovery_id']}
            assert request('POST',f"/v1/devices/{b['device']}/recover",recoveryq,b['session']) == recovery_result
            request('GET','/v1/me',session=lost_session,status=401)
            request('POST','/v1/auth/sessions',{'account_id':b['account'],'account_token':b['account_token']},status=401)
            stop(); start()
            assert request('POST',f"/v1/devices/{b['device']}/recover",recoveryq,b['session']) == recovery_result
            b['account_token'] = recoveryq['replacement_account_token']
            print('PASS: atomic device recovery rotated the capability, rejected old phone session/login, retained caller, and retried exactly after real server restart')
            enrolled = request('POST','/v1/devices',recovered_deviceq,b['session'],status=201)
            assert request('POST','/v1/devices',recovered_deviceq,b['session'],status=201) == enrolled
            request('POST',f'/v1/devices/{recovered_device}/prekeys',recovered['publication'],b['session'],recovered['device_token'])
            request('GET',f"/v1/devices/{b['device']}/prekeys",session=b['session'],device=b['token'],status=401)
            owner_devices = request('GET','/v1/devices',session=b['session'])['devices']
            assert next(d for d in owner_devices if d['device_id'] == b['device'])['revoked'] is True
            assert next(d for d in owner_devices if d['device_id'] == recovered_device)['revoked'] is False
            active_devices = request('GET',base+'/devices',session=a['session'])['devices']
            assert {d['device_id'] for d in active_devices} == {a['device'],recovered_device}
            active_bob = next(d for d in active_devices if d['device_id'] == recovered_device)
            assert active_bob['identity_key_base64'] == recovered['identity_key_base64']
            assert active_bob['protocol_device_id'] == recovered['protocol_device_id']
            assert request('GET',base+f'/devices/{recovered_device}/inbox',session=b['session'],device=recovered['device_token'])['receipts'] == []

            recovered_pin = {'room_id':room,'account_id':b['account'],'device_id':recovered_device,'protocol_device_id':recovered['protocol_device_id'],'identity_key_base64':recovered['identity_key_base64']}
            recovered_claim = peer('alice','claim',{'room_id':room,'device_id':recovered_device})['claim_id']
            assert recovered_claim != claim, 'recovered peer reused the retired-device claim'
            recovered_claimq = {'claim_id':recovered_claim,'device_id':a['device'],'recipient_device_id':recovered_device}
            recovered_bundle = request('POST',base+'/prekeys/claim',recovered_claimq,a['session'],a['token'])
            assert request('POST',base+'/prekeys/claim',recovered_claimq,a['session'],a['token']) == recovered_bundle
            recovered_acceptq = {'room_id':room,'account_id':b['account'],'device_id':recovered_device,'claim_id':recovered_claim,'bundle':recovered_bundle}
            state_before_confirmation = (root/'alice.state').read_bytes()
            peer('alice','pin',recovered_pin,False)
            peer('alice','accept',recovered_acceptq,False)
            peer('alice','replace-pin',dict(recovered_pin,expected_old_identity_key_base64=a['local']['identity_key_base64']),False)
            assert (root/'alice.state').read_bytes() == state_before_confirmation, 'rejected identity confirmation changed state'
            assert peer('alice','peer-status',{'account_id':b['account'],'device_id':b['device']}) == {'identity_key_base64':b['local']['identity_key_base64'],'has_session':True}
            # This CLI call stands for explicit old/new fingerprint confirmation, never silent trust.
            peer('alice','replace-pin',dict(recovered_pin,expected_old_identity_key_base64=b['local']['identity_key_base64']))
            assert peer('alice','peer-status',{'account_id':b['account'],'device_id':recovered_device}) == {'identity_key_base64':recovered['identity_key_base64'],'has_session':False}
            after_recovery = peer('alice','history',{'details':True})
            assert after_recovery['inbox'] == before_recovery['inbox'], 'identity replacement discarded received history'
            assert after_recovery['outbox'] == [dict(m,retired=True) for m in before_recovery['outbox']], 'identity replacement rewrote stored ciphertext/history'
            assert after_recovery['pending_count'] == 0 and after_recovery['pending'] == []
            for oldq in [staleq,offlineq]:
                peer('alice','send',oldq,False)
                peer('alice','send',dict(oldq,device_id=recovered_device),False)
                peer('alice','emit-stored',{'message_id':oldq['message_id']},False)
                peer('alice','validate-stored',{'message_id':oldq['message_id']},False)
            assert peer('alice','history',{'details':True}) == after_recovery

            # A newly verified peer still needs a fresh SessionBuilder handshake; no old session survives.
            recovered_sendq = dict(sendq,device_id=recovered_device,message_id='demo-message-recovered-first',text='새 기기에서 새로운 세션으로 복호화')
            peer('alice','send',recovered_sendq,False)
            peer('bob-recovered','pin',{'room_id':room,'account_id':a['account'],'device_id':a['device'],'protocol_device_id':a['local']['protocol_device_id'],'identity_key_base64':a['local']['identity_key_base64']})
            assert peer('bob-recovered','peer-status',{'account_id':a['account'],'device_id':a['device']})['has_session'] is False
            peer('alice','accept',recovered_acceptq)
            assert peer('alice','peer-status',{'account_id':b['account'],'device_id':recovered_device})['has_session'] is True
            recovered_outgoing = peer('alice','send',recovered_sendq)
            assert base64.b64decode(recovered_outgoing['envelope']['ciphertext_base64'])[0] == 3, 'fresh session did not emit a Signal prekey message'
            assert peer('alice','send',recovered_sendq) == recovered_outgoing
            request('POST',base+'/messages',recovered_outgoing,a['session'],a['token'])
            peer('alice','accepted',{'message_id':recovered_sendq['message_id']})
            recovered_message = request('GET',base+'/messages/'+recovered_sendq['message_id'],session=b['session'],device=recovered['device_token'])
            recovered_recvq = {'room_id':room,'message_id':recovered_sendq['message_id'],'message':recovered_message}
            peer('bob','receive',recovered_recvq,False)
            assert peer('bob-recovered','receive',recovered_recvq) == {'text':recovered_sendq['text'],'duplicate':False,'safe_to_ack':True}
            assert peer('bob-recovered','receive',recovered_recvq)['duplicate'] is True
            request('POST',base+'/messages/'+recovered_sendq['message_id']+'/ack',{},b['session'],recovered['device_token'])
            recovered_replyq = dict(replyq,message_id='demo-message-recovered-response',text='복구된 기기의 양방향 응답')
            recovered_reply = peer('bob-recovered','send',recovered_replyq)
            request('POST',base+'/messages',recovered_reply,b['session'],recovered['device_token'])
            peer('bob-recovered','accepted',{'message_id':recovered_replyq['message_id']})
            fetched_reply = request('GET',base+'/messages/'+recovered_replyq['message_id'],session=a['session'],device=a['token'])
            assert peer('alice','receive',{'room_id':room,'message_id':recovered_replyq['message_id'],'message':fetched_reply})['text'] == recovered_replyq['text']
            request('POST',base+'/messages/'+recovered_replyq['message_id']+'/ack',{},a['session'],a['token'])
            final_history = peer('alice','history',{'details':True})
            assert final_history['inbox_count'] == 2 and final_history['outbox_count'] == 4 and final_history['pending_count'] == 0
            assert sum(m['retired'] for m in final_history['outbox']) == 3
            assert all(m in final_history['inbox'] for m in before_recovery['inbox'])
            assert all(m in final_history['outbox'] for m in after_recovery['outbox'])
            assert peer('bob-recovered','history',{}) == {'inbox_count':1,'outbox_count':1,'pending_count':0}
            assert peer('bob','history',{}) == {'inbox_count':1,'outbox_count':1,'pending_count':0}
            # Real ciphertext survives local history while owner-specific server blocks close the room.
            blocked_text = '차단 전 대기 암호문은 다시 살아나지 않음'
            waiting_q = dict(recovered_sendq, message_id='demo-message-before-block', text=blocked_text)
            waiting = peer('alice', 'send', waiting_q)
            request('POST', base+'/messages', waiting, a['session'], a['token'])
            peer('alice', 'accepted', {'message_id':waiting_q['message_id']})
            alice_before_block = (root/'alice.state').read_bytes()
            bob_before_block = (root/'bob-recovered.state').read_bytes()
            block_a = {'peer_id':b['account'],'mutation_id':'demo-block-alice-once','expected_revision':0}
            block_b = {'peer_id':a['account'],'mutation_id':'demo-block-bob-once','expected_revision':0}
            blocked_a = request('POST','/v1/blocks',block_a,a['session'])
            assert blocked_a == {'peer_id':b['account'],'blocked':True,'revision':1,'mutation_id':block_a['mutation_id'],'legacy_hold':False}
            assert request('POST','/v1/blocks',block_a,a['session']) == blocked_a
            assert request('GET','/v1/blocks/'+a['account'],session=b['session'])['blocked'] is False
            request('POST','/v1/blocks',block_b,b['session'])
            request('POST',base+'/messages',waiting,a['session'],a['token'],status=403)
            unblock_a = {'peer_id':b['account'],'mutation_id':'demo-unblock-alice-once','expected_revision':1}
            own_clear = request('POST','/v1/blocks/unblock',unblock_a,a['session'])
            assert own_clear['blocked'] is False and own_clear['revision'] == 2
            request('POST','/v1/invitations',{'invitation_id':'demo-invite-still-blocked','recipient_id':b['account']},a['session'],status=403)
            stop(); start()
            assert request('POST','/v1/blocks/unblock',unblock_a,a['session']) == own_clear
            request('POST','/v1/blocks',block_a,a['session'],status=409)
            assert request('GET','/v1/blocks/'+b['account'],session=a['session']) == own_clear
            assert request('GET','/v1/blocks/'+a['account'],session=b['session'])['blocked'] is True
            request('POST','/v1/blocks/unblock',{'peer_id':a['account'],'mutation_id':'demo-unblock-bob-once','expected_revision':1},b['session'])
            request('GET',base,session=a['session'],status=410)
            request('POST',base+'/messages',waiting,a['session'],a['token'],status=410)
            assert (root/'alice.state').read_bytes() == alice_before_block
            assert (root/'bob-recovered.state').read_bytes() == bob_before_block
            assert waiting['envelope']['ciphertext_base64'].encode() not in (root/'server'/'state.json').read_bytes()
            # A fresh invitation creates a distinct room; reuse verified identities, not old room authority.
            fresh_inv = request('POST','/v1/invitations',{'invitation_id':'demo-invite-after-unblock','recipient_id':b['account']},a['session'])
            fresh_room = request('POST','/v1/invitations/consume',{'invitation_id':fresh_inv['invitation']['invitation_id'],'invitation_token':fresh_inv['invitation_token'],'idempotency_key':'demo-consume-after-unblock'},b['session'])['room_id']
            assert fresh_room != room
            fresh_base = '/v1/rooms/'+fresh_room
            peer('alice','pin',dict(recovered_pin,room_id=fresh_room))
            peer('bob-recovered','pin',{'room_id':fresh_room,'account_id':a['account'],'device_id':a['device'],'protocol_device_id':a['local']['protocol_device_id'],'identity_key_base64':a['local']['identity_key_base64']})
            resumed_text = '새 초대에서만 다시 주고받는 암호화 대화'
            resumed_q = dict(recovered_sendq,room_id=fresh_room,message_id='demo-message-after-unblock',text=resumed_text)
            resumed = peer('alice','send',resumed_q)
            request('POST',fresh_base+'/messages',resumed,a['session'],a['token'])
            peer('alice','accepted',{'message_id':resumed_q['message_id']})
            resumed_message = request('GET',fresh_base+'/messages/'+resumed_q['message_id'],session=b['session'],device=recovered['device_token'])
            assert peer('bob-recovered','receive',{'room_id':fresh_room,'message_id':resumed_q['message_id'],'message':resumed_message})['text'] == resumed_text
            request('POST',fresh_base+'/messages/'+resumed_q['message_id']+'/ack',{},b['session'],recovered['device_token'])
            assert room not in [v['room_id'] for v in request('GET','/v1/rooms',session=a['session'])['rooms']]
            # Server snapshot is opaque for conversation text and never receives private Signal state.
            snapshot = b''.join(f.read_bytes() for f in (root/'server').rglob('*') if f.is_file())
            for cleartext in [text,replyq['text'],staleq['text'],offlineq['text'],recovered_sendq['text'],recovered_replyq['text'],blocked_text,resumed_text]:
                assert cleartext.encode() not in snapshot
            print('PASS: real Signal production bridge + Go /v1 pilot: admission-gated durable signup/device retry, published receiver metadata, independent identity/PQ bundles, explicit identity pins, invitation, atomic claim/retry, signature/ciphertext/context tamper rejection, client/server restart, exact ciphertext retry, two-way decryption, inbox commit before ACK, dedupe, opaque server state, bounded prekey refill, accepted-but-unconfirmed full-pool refill recovery, independently initialized recovered device with historical protocol exclusion, explicit expected-old identity replacement, retained history and retired non-retransmittable old ciphertext, fresh recovery claim/handshake and two-way roundtrip, mutual owner-specific block/CAS/restart, no old-room or ciphertext resurrection, and real decryption after a fresh invitation')
            print('NOT TESTED: Tor network, Android JNI execution, Android Keystore, physical-device lifecycle, screenshots')
        finally: stop()
if __name__ == '__main__': main()
