package dev.gyeopdam.embeddedtor;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.Supplier;

/** One immutable message ID per composer revision in one trusted document lifetime. */
final class DraftLedger {
    static final class Entry {
        final String revision,room,text,messageId;
        private final long document;
        Entry(String revision,String room,String text,String message,long document){this.revision=revision;this.room=room;this.text=text;messageId=message;this.document=document;}
    }
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
    private final Supplier<String> ids;
    private long highest,document;
    DraftLedger(){this(()->"demo-message-"+UUID.randomUUID());}
    DraftLedger(Supplier<String> ids){this.ids=ids;}
    Entry bind(String revision,String room,String text){
        if(!UiCommandPolicy.validDraftId(revision)||!UiCommandPolicy.validOpaqueId(room)||text==null||text.isEmpty()||text.getBytes(StandardCharsets.UTF_8).length>4096)throw invalid();
        Entry entry=entries.get(revision);
        if(entry!=null){if(!entry.room.equals(room)||!entry.text.equals(text))throw invalid();return entry;}
        long number=Long.parseLong(revision);if(number<=highest)throw invalid();highest=number;
        String messageId=ids.get();if(!UiCommandPolicy.validOpaqueId(messageId))throw invalid();
        entry=new Entry(revision,room,text,messageId,document);entries.put(revision,entry);
        if(entries.size()>64)entries.remove(entries.keySet().iterator().next());return entry;
    }
    boolean current(Entry entry){return entry!=null&&entry.document==document&&entries.get(entry.revision)==entry;}
    void reset(){entries.clear();highest=0;document++;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("INVALID_DRAFT_REVISION");}
}
