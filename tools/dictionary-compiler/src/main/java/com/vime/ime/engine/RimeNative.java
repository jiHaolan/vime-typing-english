package com.vime.ime.engine;

/** Same JNI ABI, separate application and fresh private compiler directories. */
public final class RimeNative {
    static { System.loadLibrary("vime_rime"); }
    public native String initialize(String shared, String user);
    public native String[] candidates(String raw, int offset, int limit);
    public native void shutdown();
}
