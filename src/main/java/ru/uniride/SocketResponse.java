package ru.uniride;

public class SocketResponse {
    public String type;
    public Object data;

    public SocketResponse(String type, Object data) {
        this.type = type;
        this.data = data;
    }
}
