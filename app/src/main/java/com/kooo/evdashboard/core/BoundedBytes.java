package com.kooo.evdashboard.core;

import java.io.*;

/** A bounded SSH receiver: the transport thread writes directly, without a pipe. */
public final class BoundedBytes extends OutputStream {
    private final int limit;
    private final ByteArrayOutputStream data = new ByteArrayOutputStream();
    private IOException failure;
    public BoundedBytes(int limit) {if (limit < 0) throw new IllegalArgumentException("Negative limit"); this.limit = limit;}
    @Override public synchronized void write(int value) throws IOException {reserve(1); data.write(value);}
    @Override public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
        reserve(length); data.write(bytes, offset, length);
    }
    private void reserve(int length) throws IOException {
        check();
        if (length > limit - data.size()) {failure = new IOException("SSH 输出超过限制"); throw failure;}
    }
    public synchronized void check() throws IOException {if (failure != null) throw failure;}
    public synchronized byte[] bytes() throws IOException {check(); return data.toByteArray();}
}
