package de.adraxah.blockoverview;

public final class HttpError extends Exception {
    public final int status;
    public HttpError(int status, String message) { super(message); this.status = status; }
}
