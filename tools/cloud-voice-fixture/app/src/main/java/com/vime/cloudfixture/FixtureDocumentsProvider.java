package com.vime.cloudfixture;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Read-only, fixed test assets exposed solely through the system document picker. */
public final class FixtureDocumentsProvider extends DocumentsProvider {
    private static final String MODEL="x-asr-zh-en-q4_k.gguf";
    private static final String[] ROOTS={"root_id","document_id","title","summary","flags","mime_types","icon"};
    private static final String[] DOCS={"document_id","_display_name","mime_type","flags","_size"};
    @Override public boolean onCreate(){return true;}
    private String file(String id) throws FileNotFoundException {
        return switch(id){case "model"->MODEL;case "zh"->"zh.wav";case "en"->"en.wav";default->throw new FileNotFoundException("Unknown fixture");};
    }
    private boolean hasModel(){try{return Arrays.asList(getContext().getAssets().list("")).contains(MODEL);}catch(IOException e){return false;}}
    @Override public Cursor queryRoots(String[] projection){
        MatrixCursor cursor=new MatrixCursor(projection==null?ROOTS:projection);
        MatrixCursor.RowBuilder row=cursor.newRow();
        for(String col:cursor.getColumnNames())row.add(switch(col){
            case "root_id"->"fixtures";case "document_id"->"root";case "title"->"键忆云测文件";
            case "summary"->"固定模型与合成语音样本";case "flags"->DocumentsContract.Root.FLAG_LOCAL_ONLY;
            case "mime_types"->"application/octet-stream\naudio/wav";case "icon"->android.R.drawable.ic_media_play;default->null;});
        return cursor;
    }
    private void document(MatrixCursor cursor,String id) throws FileNotFoundException {
        boolean directory=id.equals("root");String filename=directory?"键忆云测文件":file(id);
        if(id.equals("model")&&!hasModel())throw new FileNotFoundException("Model asset not included");
        long length=0;
        if(!directory)try(var asset=getContext().getAssets().openFd(filename)){length=asset.getLength();}catch(IOException e){throw new FileNotFoundException("Fixture unavailable");}
        MatrixCursor.RowBuilder row=cursor.newRow();
        for(String col:cursor.getColumnNames())row.add(switch(col){case "document_id"->id;case "_display_name"->filename;
            case "mime_type"->directory?DocumentsContract.Document.MIME_TYPE_DIR:id.equals("model")?"application/octet-stream":"audio/wav";
            case "flags"->0;case "_size"->directory?null:length;default->null;});
    }
    @Override public Cursor queryDocument(String id,String[] projection) throws FileNotFoundException {
        MatrixCursor cursor=new MatrixCursor(projection==null?DOCS:projection);document(cursor,id);return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String order) throws FileNotFoundException {
        if(!parent.equals("root"))throw new FileNotFoundException("Unknown directory");
        MatrixCursor cursor=new MatrixCursor(projection==null?DOCS:projection);
        if(hasModel())document(cursor,"model");document(cursor,"zh");document(cursor,"en");return cursor;
    }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException {
        if(!mode.equals("r"))throw new FileNotFoundException("Read-only fixtures");
        final String asset=file(id);
        try {
            final ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createReliablePipe();
            new Thread(()->{
                try(InputStream input=getContext().getAssets().open(asset);var output=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){
                    byte[] buffer=new byte[65536];int count;
                    while((count=input.read(buffer))!=-1){if(signal!=null)signal.throwIfCanceled();output.write(buffer,0,count);}
                }catch(Exception ignored){try{pipe[1].close();}catch(IOException ignoredAgain){}}
            },"cloud-fixture-read").start();
            return pipe[0];
        }catch(IOException e){throw new FileNotFoundException("Cannot open fixture");}
    }
}
