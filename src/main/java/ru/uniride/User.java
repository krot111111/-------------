package ru.uniride;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class User {
    public String id;
    public String name;
    public String phone;
    public String tg;
    public String vk;

    public User() {}
}
