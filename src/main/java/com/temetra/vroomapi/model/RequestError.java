package com.temetra.vroomapi.model;

public class RequestError {
    private String error;

    public RequestError(String error) {
        this.error = error;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
