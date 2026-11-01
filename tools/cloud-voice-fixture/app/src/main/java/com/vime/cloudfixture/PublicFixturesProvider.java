package com.vime.cloudfixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.Semaphore;

/** Public, immutable licensed model/synthetic samples only. No user files or recordings. */
public final class PublicFixturesProvider extends ContentProvider {
    private static final Semaphore READERS=new Semaphore(2);
    @Override public boolean onCreate(){return true;}
    private String asset(Uri uri){
        if(!"com.vime.cloudfixture.publicassets".equals(uri.getAuthority()) || uri.getQuery()!=null || uri.getFragment()!=null || uri.getPathSegments().size()!=1)throw new IllegalArgumentException("Unknown public fixture");
        return switch(uri.getLastPathSegment()){case "model"->"x-asr-zh-en-q4_k.gguf";case "zh"->"zh.wav";case "en"->"en.wav";default->throw new IllegalArgumentException("Unknown public fixture");};
    }
    @Override public String getType(Uri uri){return asset(uri).endsWith(".wav")?"audio/wav":"application/octet-stream";}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
        String name=asset(uri);long size;
        try(var fd=getContext().getAssets().openFd(name)){size=fd.getLength();}catch(IOException e){throw new IllegalArgumentException("Fixture unavailable");}
        MatrixCursor cursor=new MatrixCursor(projection==null?new String[]{"_display_name","_size"}:projection);
        var row=cursor.newRow();for(String col:cursor.getColumnNames())row.add(switch(col){case "_display_name"->name;case "_size"->size;default->null;});return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
        final String name;
        try{name=asset(uri);}catch(IllegalArgumentException e){throw new FileNotFoundException("Unknown public fixture");}
        if(!"r".equals(mode)||!READERS.tryAcquire())throw new FileNotFoundException("Read-only or busy");
        final ParcelFileDescriptor[] pipe;
        try{pipe=ParcelFileDescriptor.createReliablePipe();}catch(IOException e){READERS.release();throw new FileNotFoundException("Pipe unavailable");}
        Thread reader=new Thread(()->{
            try(var input=getContext().getAssets().open(name);var output=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){
                byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
            }catch(Exception ignored){try{pipe[1].close();}catch(IOException ignoredAgain){}}
            finally{READERS.release();}
        },"public-fixed-sample");reader.setDaemon(true);
        try{reader.start();}catch(RuntimeException failure){READERS.release();for(var fd:pipe)try{fd.close();}catch(IOException ignored){}throw new FileNotFoundException("Reader unavailable");}
        return pipe[0];
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("Read-only");}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new UnsupportedOperationException("Read-only");}
    @Override public int delete(Uri uri,String where,String[] args){throw new UnsupportedOperationException("Read-only");}
}
