package ru.uniride;

public class AdminLoginResult {
    public boolean success;
    public String message;
    public String token;

    public AdminLoginResult(boolean success, String message, String token) {
        this.success = success;
        this.message = message;
        this.token = token;
    }
}
