package dev.gyeopdam.embeddedtor;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/** Uses the packaged Android JNI library. No fake crypto provider, network or persistent keys. */
final class AndroidSignalRuntimeChecks {
    private static final String ORIGIN="http://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.onion";
    private static final String A="demo-account-alice",B="demo-account-bob",AD="demo-device-alice",BD="demo-device-bob",ROOM="demo-room-runtime";
    private static final class Memory implements SignalClient.StateStorage {
        byte[] bytes;
        int saves;
        public synchronized byte[] load(){return bytes==null?null:bytes.clone();}
        public synchronized void save(byte[] next){if(bytes!=null)Arrays.fill(bytes,(byte)0);bytes=next.clone();saves++;}
        synchronized void clear(){if(bytes!=null)Arrays.fill(bytes,(byte)0);bytes=null;}
    }
    static void run()throws Exception {
        Memory left=new Memory(),right=new Memory();
        try {
            SignalClient alice=new SignalClient(left),bob=new SignalClient(right);
            alice.initialize(ORIGIN,A,AD,2);bob.initialize(ORIGIN,B,BD,2);
            require(!alice.identityKeyBase64().equals(bob.identityKeyBase64()),"INDEPENDENT_IDENTITIES");
            alice.acceptPeer(ROOM,B,BD,bob.protocolDeviceId(),bob.identityKeyBase64());
            bob.acceptPeer(ROOM,A,AD,alice.protocolDeviceId(),alice.identityKeyBase64());
            alice.acceptBundle(ROOM,B,BD,bob.publication().bundle(0));
            require(!bob.hasSession(A,AD),"RECEIVER_MUST_START_WITHOUT_SESSION");
            SignalClient.Envelope first=alice.stageText(ROOM,B,BD,"demo-message-first","runtime synthetic first");
            require(first.type==3,"PREKEY_CIPHERTEXT_REQUIRED");
            require("runtime synthetic first".equals(bob.receive(first).text),"FIRST_DECRYPT");
            require(new SignalClient(right).receive(first).duplicate,"DURABLE_REPLAY_DEDUP");
            alice=new SignalClient(left);bob=new SignalClient(right);
            SignalClient.Envelope reply=bob.stageText(ROOM,A,AD,"demo-message-reply","runtime synthetic reply");
            require("runtime synthetic reply".equals(alice.receive(reply).text),"RESTART_REPLY_DECRYPT");
            SignalClient.Envelope next=alice.stageText(ROOM,B,BD,"demo-message-next","runtime synthetic next");
            byte[] before=right.load(),cipher=Base64.getDecoder().decode(next.bodyBase64);cipher[cipher.length-1]^=1;
            SignalClient.Envelope tampered=new SignalClient.Envelope(next.roomId,next.messageId,next.senderAccountId,next.senderDeviceId,next.recipientAccountId,next.recipientDeviceId,next.type,Base64.getEncoder().encodeToString(cipher));
            boolean rejected=false;try{bob.receive(tampered);}catch(Exception expected){rejected=true;}
            require(rejected,"TAMPER_REJECTED");require(Arrays.equals(before,right.load()),"FAILED_RECEIVE_ROLLBACK");
            require("runtime synthetic next".equals(bob.receive(next).text),"ORIGINAL_AFTER_TAMPER");
            require(next.wireBase64().equals(new SignalClient(left).stageText(ROOM,B,BD,next.messageId,"runtime synthetic next").wireBase64()),"EXACT_RETRY_CIPHERTEXT");
            Arrays.fill(before,(byte)0);Arrays.fill(cipher,(byte)0);
            archiveReads(left,bob.protocolDeviceId());
        } finally {left.clear();right.clear();}
    }
    private interface Operation {void run()throws Exception;}
    private static void archiveReads(Memory storage,int peerProtocol)throws Exception {
        final String emptyRoom="demo-room-zz-empty",wrongPeer="demo-account-charlie";
        SignalClient client=new SignalClient(storage);
        client.acceptPeer(emptyRoom,B,BD,peerProtocol,client.pinnedIdentityKeyBase64(B));
        byte[] snapshot=storage.load();int saves=storage.saves;
        try {
            SignalClient.LocalRoomPage first=client.listLocalRooms(ORIGIN,A,"","",1);
            require(first.rooms.size()==1&&ROOM.equals(first.rooms.get(0).roomId)
                    &&B.equals(first.rooms.get(0).peerAccountId)&&first.rooms.get(0).messageCount==3,
                    "ARCHIVE_ROOM_ASSOCIATION");
            require(ROOM.equals(first.nextAfterRoomId),"ARCHIVE_ROOM_CURSOR");
            SignalClient.LocalRoomPage second=client.listLocalRooms(ORIGIN,A,B,first.nextAfterRoomId,1);
            require(second.rooms.size()==1&&emptyRoom.equals(second.rooms.get(0).roomId)
                    &&second.rooms.get(0).messageCount==0&&second.nextAfterRoomId.isEmpty(),"ARCHIVE_EMPTY_BOUND_ROOM");
            require(client.listLocalRooms(ORIGIN,A,wrongPeer,"",40).rooms.isEmpty(),"ARCHIVE_PEER_FILTER");
            SignalClient.LocalHistoryPage latest=client.openLocalRoom(ORIGIN,A,ROOM,B,"",2);
            require(latest.messages.size()==2&&!latest.nextBeforeMessageCursor.isEmpty(),"ARCHIVE_HISTORY_PAGE");
            SignalClient.LocalHistoryPage older=client.openLocalRoom(ORIGIN,A,ROOM,B,latest.nextBeforeMessageCursor,2);
            require(older.messages.size()==1&&older.nextBeforeMessageCursor.isEmpty(),"ARCHIVE_HISTORY_TERMINAL");
            Set<String> ids=new HashSet<>();
            for(SignalClient.Incoming value:older.messages)require(ids.add(value.envelope.messageId),"ARCHIVE_PAGE_NO_DUPLICATES");
            for(SignalClient.Incoming value:latest.messages)require(ids.add(value.envelope.messageId),"ARCHIVE_PAGE_NO_DUPLICATES");
            require(ids.size()==3&&ids.contains("demo-message-first")&&ids.contains("demo-message-reply")
                    &&ids.contains("demo-message-next"),"ARCHIVE_PAGE_NO_OMISSIONS");
            expect("unknown_local_room",()->client.openLocalRoom(ORIGIN,A,ROOM,wrongPeer,"",2));
            expect("archive_profile_mismatch",()->client.listLocalRooms(ORIGIN,wrongPeer,"","",1));
            expect("archive_profile_mismatch",()->client.listLocalRooms(
                    "http://bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.onion",A,"","",1));
            expect("archive_cursor",()->client.openLocalRoom(ORIGIN,A,emptyRoom,B,latest.nextBeforeMessageCursor,1));
            expect("archive_page_limit",()->client.listLocalRooms(ORIGIN,A,"","",41));
            expect("archive_page_limit",()->client.openLocalRoom(ORIGIN,A,ROOM,B,"",201));
            require(storage.saves==saves&&Arrays.equals(snapshot,storage.load()),"ARCHIVE_READS_MUST_NOT_SAVE");
        } finally {Arrays.fill(snapshot,(byte)0);}
        Memory absent=new Memory();
        try {
            require(new SignalClient(absent).listLocalRooms(ORIGIN,A,"","",40).rooms.isEmpty(),"ARCHIVE_ABSENT_EMPTY");
            require(absent.saves==0&&absent.load()==null,"ARCHIVE_ABSENT_MUST_NOT_INITIALIZE");
        } finally {absent.clear();}
    }
    private static void expect(String error,Operation operation)throws Exception {
        try{operation.run();throw new AssertionError("ARCHIVE_REJECTION_REQUIRED");}
        catch(IllegalArgumentException rejected){require(error.equals(rejected.getMessage()),"ARCHIVE_FIXED_ERROR_CODE");}
    }
    private static void require(boolean condition,String code){TorTalkRuntimeInstrumentation.require(condition,code);}
}
